package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** The coordinator persists each transition before acknowledging it to a worker. */
final class ProjectQueue {
    enum Kind { EXCAVATE, GATHER, CRAFT, SMELT, TRANSFER, BUILD, VERIFY }
    enum State { WAITING, LEASED, DONE, FAILED }

    record Work(String id, Kind kind, String payload, List<String> needs, Set<String> locks, String sameWorkerAs) {
        Work(String id, Kind kind, String payload, List<String> needs, Set<String> locks) {
            this(id, kind, payload, needs, locks, null);
        }
        Work {
            if (!validId(id) || kind == null || payload == null || payload.length() > 8192
                    || needs == null || locks == null || needs.size() > 256 || locks.size() > 128
                    || needs.stream().anyMatch(dep -> !validId(dep))
                    || sameWorkerAs != null && !needs.contains(sameWorkerAs)
                    || locks.stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 240)) {
                throw new IllegalArgumentException("Invalid project job");
            }
            needs = List.copyOf(needs);
            locks = Set.copyOf(locks);
        }
    }

    record Job(Work work, State state, int attempts, String worker, UUID lease, long expiresAt,
               String evidence, ChestObservation chest) {}
    record ChestReport(String location, Map<String, Integer> items) {
        ChestReport {
            if (location == null || !location.matches("block:-?[0-9]+,-?[0-9]+,-?[0-9]+")
                    || items == null || items.size() > 27
                    || items.entrySet().stream().anyMatch(item -> !item.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || item.getValue() == null || item.getValue() < 1 || item.getValue() > 1728)
                    || items.values().stream().mapToInt(Integer::intValue).sum() > 1728) {
                throw new IllegalArgumentException("Invalid chest observation");
            }
            items = Map.copyOf(items);
        }
    }
    record ChestObservation(ChestReport report, long observedAt) {}
    record Repeat(int batch, String item, String chest, int completed, ChestObservation lastStock, boolean cancelled) {}
    record Project(String id, String world, List<Job> jobs, Repeat repeat) {
        Project { jobs = List.copyOf(jobs); }
    }
    record Assignment(String project, String world, Job job) {}
    private record Board(int schema, Map<String, Project> projects) {}

    private static final Gson JSON = new Gson();
    private static final long LEASE_MS = 15_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_PROJECTS = 32;
    private final Path journal;
    private Map<String, Project> projects;

    ProjectQueue(Path journal) throws IOException {
        this.journal = journal.toAbsolutePath();
        try {
            Board board = JSON.fromJson(Files.readString(this.journal), Board.class);
            if (board == null || board.schema != 1 || board.projects == null) {
                throw new IOException("Invalid project journal schema");
            }
            projects = new LinkedHashMap<>(board.projects);
        } catch (NoSuchFileException ignored) {
            projects = new LinkedHashMap<>();
        } catch (JsonParseException | IllegalArgumentException error) {
            throw new IOException("Invalid project journal", error);
        }
    }

    synchronized List<Project> snapshot(long now) throws IOException {
        expire(now);
        return List.copyOf(projects.values());
    }

    synchronized void submit(String id, String world, List<Work> work) throws IOException {
        submit(id, world, work, null);
    }

    synchronized void submitOutput(String id, String world, List<Work> work, Repeat repeat) throws IOException {
        if (projects.values().stream().anyMatch(project -> project.world.equals(world) && project.repeat != null
                && !project.repeat.cancelled && project.repeat.chest.equals(repeat.chest))) {
            throw new IllegalArgumentException("An output order already reserves this chest");
        }
        submit(id, world, work, repeat);
    }

    synchronized boolean cancelOutput(String world, String chest) throws IOException {
        for (Project project : projects.values()) {
            var repeat = project.repeat;
            if (!project.world.equals(world) || repeat == null || repeat.cancelled || !repeat.chest.equals(chest)) continue;
            var cancelled = new Repeat(repeat.batch, repeat.item, repeat.chest, repeat.completed, repeat.lastStock, true);
            commit(new Project(project.id, project.world, project.jobs, cancelled));
            return true;
        }
        return false;
    }

    private void submit(String id, String world, List<Work> work, Repeat repeat) throws IOException {
        if (!validId(id) || world == null || world.isBlank() || world.length() > 240
                || work == null || work.isEmpty() || work.size() > 256) {
            throw new IllegalArgumentException("Invalid project");
        }
        if (projects.containsKey(id) || projects.size() >= MAX_PROJECTS) {
            throw new IllegalArgumentException("Project already exists or the journal is full");
        }
        // Submit in topological order. This rejects both missing predecessors and cycles.
        Set<String> defined = new HashSet<>();
        List<Job> jobs = new ArrayList<>();
        for (Work item : work) {
            if (item == null || !defined.containsAll(item.needs) || !defined.add(item.id)) {
                throw new IllegalArgumentException("Jobs must be unique and follow their dependencies");
            }
            jobs.add(new Job(item, State.WAITING, 0, null, null, 0, null, null));
        }
        commit(new Project(id, world, jobs, repeat));
    }

    synchronized Assignment claim(String world, String worker, Set<Kind> kinds, long now) throws IOException {
        if (world == null || !validId(worker) || kinds == null || kinds.isEmpty()) {
            throw new IllegalArgumentException("Invalid worker or world");
        }
        expire(now);
        Set<String> reserved = new HashSet<>();
        Map<String, String> outputChests = new HashMap<>();
        for (Project project : projects.values()) {
            if (world.equals(project.world) && project.repeat != null && !project.repeat.cancelled) {
                outputChests.put(project.repeat.chest, project.id);
            }
        }
        for (Project project : projects.values()) {
            for (Job job : project.jobs) {
                if (job.state != State.LEASED) continue;
                if (worker.equalsIgnoreCase(job.worker)) {
                    // A claim response can be lost. Repeating a claim returns the original assignment.
                    return world.equals(project.world) && kinds.contains(job.work.kind) && !cancelled(project)
                            ? new Assignment(project.id, project.world, job) : null;
                }
                if (world.equals(project.world)) reserved.addAll(job.work.locks);
            }
        }
        for (Project project : projects.values()) {
            if (!world.equals(project.world) || cancelled(project)) continue;
            Set<String> done = new HashSet<>();
            for (Job job : project.jobs) if (job.state == State.DONE) done.add(job.work.id);
            for (Job job : project.jobs) {
                if (job.state != State.WAITING || !kinds.contains(job.work.kind)
                        || !done.containsAll(job.work.needs) || !Collections.disjoint(reserved, job.work.locks)) continue;
                if (job.work.locks.stream().anyMatch(key -> outputChests.containsKey(key)
                        && !project.id.equals(outputChests.get(key)))) continue;
                if (job.work.sameWorkerAs != null
                        && !worker.equalsIgnoreCase(find(project, job.work.sameWorkerAs).worker)) continue;
                Job assigned = new Job(job.work, State.LEASED, job.attempts + 1, worker,
                        UUID.randomUUID(), now + LEASE_MS, null, job.chest);
                replace(project, assigned);
                return new Assignment(project.id, project.world, assigned);
            }
        }
        return null;
    }

    synchronized Job renew(String projectId, String jobId, UUID token, long now) throws IOException {
        Project project = projects.get(projectId);
        Job job = find(project, jobId);
        if (!owns(job, token, now) || cancelled(project)) return null;
        Job renewed = new Job(job.work, State.LEASED, job.attempts, job.worker,
                job.lease, now + LEASE_MS, null, job.chest);
        replace(project, renewed);
        return renewed;
    }

    synchronized boolean finish(String projectId, String jobId, UUID token, boolean success,
                                String evidence, long now) throws IOException {
        return finish(projectId, jobId, token, success, evidence, null, now);
    }

    synchronized boolean finish(String projectId, String jobId, UUID token, boolean success,
                                String evidence, ChestReport chest, long now) throws IOException {
        if (evidence == null || evidence.isBlank() || evidence.length() > 2048) {
            throw new IllegalArgumentException("Report an observed outcome or failure reason");
        }
        Project project = projects.get(projectId);
        Job job = find(project, jobId);
        if (job != null && job.state == State.DONE) {
            return success && Objects.equals(job.lease, token) && job.evidence.equals(evidence)
                    && Objects.equals(job.chest == null ? null : job.chest.report, chest);
        }
        if (!owns(job, token, now)) return false;
        if (chest != null && (job.work.kind != Kind.TRANSFER || !job.work.locks.contains(chest.location)
                || project.repeat != null && !project.repeat.chest.equals(chest.location))) {
            throw new IllegalArgumentException("Chest report does not match the leased transfer");
        }
        if (success && job.work.kind == Kind.TRANSFER && chest == null) {
            throw new IllegalArgumentException("Successful transfer requires a chest observation");
        }
        var observation = chest == null ? job.chest : new ChestObservation(chest, now);
        Job result = success
                ? new Job(job.work, State.DONE, job.attempts, job.worker, job.lease, 0, evidence, observation)
                : retry(job, evidence, observation);
        if (success && project.repeat != null && !project.repeat.cancelled && job.work.id.equals("inspect")) {
            prepareOutputDeposit(project, result);
        } else if (success && project.repeat != null && job.work.id.equals("deposit")) {
            var repeat = project.repeat;
            var next = new Repeat(repeat.batch, repeat.item, repeat.chest, repeat.completed + 1, observation, repeat.cancelled);
            var reset = repeat.cancelled
                    ? project.jobs.stream().map(entry -> entry == job ? result : entry).toList()
                    : project.jobs.stream().map(entry -> new Job(entry.work, State.WAITING, 0,
                            null, null, 0, null, null)).toList();
            commit(new Project(project.id, project.world, reset, next));
        } else replace(project, result);
        return true;
    }

    private void prepareOutputDeposit(Project project, Job inspected) throws IOException {
        var repeat = project.repeat;
        int current = inspected.chest.report.items.getOrDefault(repeat.item, 0);
        var jobs = new ArrayList<Job>();
        for (Job entry : project.jobs) {
            if (entry.work.id.equals("inspect")) jobs.add(inspected);
            else if (entry.work.id.equals("deposit")) {
                Work old = entry.work;
                if (current + repeat.batch > 1728) {
                    jobs.add(new Job(old, State.FAILED, 0, null, null, 0, "Output chest is full", null));
                    continue;
                }
                var payload = com.google.gson.JsonParser.parseString(old.payload).getAsJsonObject();
                payload.addProperty("stock", current + repeat.batch);
                var next = new Work(old.id, old.kind, JSON.toJson(payload), old.needs, old.locks, old.sameWorkerAs);
                jobs.add(new Job(next, State.WAITING, 0, null, null, 0, null, null));
            } else jobs.add(entry);
        }
        commit(new Project(project.id, project.world, jobs, repeat));
    }

    synchronized Map<String, ChestObservation> chestStock(String world) {
        Map<String, ChestObservation> latest = new TreeMap<>();
        for (Project project : projects.values()) {
            if (!project.world.equals(world)) continue;
            if (project.repeat != null && project.repeat.lastStock != null) {
                var last = project.repeat.lastStock;
                latest.merge(last.report.location, last, (a, b) -> a.observedAt > b.observedAt ? a : b);
            }
            for (Job job : project.jobs) {
                if (job.chest != null) latest.merge(job.chest.report.location, job.chest,
                        (a, b) -> a.observedAt > b.observedAt ? a : b);
            }
        }
        return latest;
    }

    private void expire(long now) throws IOException {
        var next = new LinkedHashMap<>(projects);
        boolean changed = false;
        for (Project project : projects.values()) {
            List<Job> jobs = null;
            for (int i = 0; i < project.jobs.size(); i++) {
                Job job = project.jobs.get(i);
                if (job.state == State.LEASED && job.expiresAt <= now) {
                    if (jobs == null) jobs = new ArrayList<>(project.jobs);
                    jobs.set(i, retry(job, "Worker lease expired", job.chest));
                }
            }
            if (jobs != null) {
                next.put(project.id, new Project(project.id, project.world, jobs, project.repeat));
                changed = true;
            }
        }
        if (changed) persist(next);
    }

    private static Job retry(Job job, String reason, ChestObservation chest) {
        State state = job.attempts < MAX_ATTEMPTS ? State.WAITING : State.FAILED;
        return new Job(job.work, state, job.attempts, null, null, 0, reason, chest);
    }

    private static boolean cancelled(Project project) {
        return project.repeat != null && project.repeat.cancelled;
    }

    private static boolean owns(Job job, UUID token, long now) {
        return job != null && job.state == State.LEASED && token != null
                && token.equals(job.lease) && job.expiresAt > now;
    }

    private static Job find(Project project, String id) {
        if (project == null) return null;
        return project.jobs.stream().filter(job -> job.work.id.equals(id)).findFirst().orElse(null);
    }

    private void replace(Project project, Job changed) throws IOException {
        var jobs = project.jobs.stream().map(job -> job.work.id.equals(changed.work.id) ? changed : job).toList();
        commit(new Project(project.id, project.world, jobs, project.repeat));
    }

    private void commit(Project project) throws IOException {
        var next = new LinkedHashMap<>(projects);
        next.put(project.id, project);
        persist(next);
    }

    private void persist(Map<String, Project> next) throws IOException {
        Path parent = journal.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "botfury-projects-", ".tmp");
        try {
            byte[] bytes = JSON.toJson(new Board(1, next)).getBytes(StandardCharsets.UTF_8);
            try (var output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temp, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            projects = next;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }
}
