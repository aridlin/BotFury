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

    record Work(String id, Kind kind, String payload, List<String> needs, Set<String> locks) {
        Work {
            if (!validId(id) || kind == null || payload == null || payload.length() > 8192
                    || needs == null || locks == null || needs.size() > 256 || locks.size() > 128
                    || needs.stream().anyMatch(dep -> !validId(dep))
                    || locks.stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 240)) {
                throw new IllegalArgumentException("Invalid project job");
            }
            needs = List.copyOf(needs);
            locks = Set.copyOf(locks);
        }
    }

    record Job(Work work, State state, int attempts, String worker, UUID lease, long expiresAt,
               String evidence) {}
    record Project(String id, String world, List<Job> jobs) {
        Project { jobs = List.copyOf(jobs); }
    }
    record Assignment(String project, Job job) {}
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
            jobs.add(new Job(item, State.WAITING, 0, null, null, 0, null));
        }
        commit(new Project(id, world, jobs));
    }

    synchronized Assignment claim(String world, String worker, Set<Kind> kinds, long now) throws IOException {
        if (world == null || !validId(worker) || kinds == null || kinds.isEmpty()) {
            throw new IllegalArgumentException("Invalid worker or world");
        }
        expire(now);
        Set<String> reserved = new HashSet<>();
        for (Project project : projects.values()) {
            for (Job job : project.jobs) {
                if (job.state != State.LEASED) continue;
                if (worker.equalsIgnoreCase(job.worker)) {
                    // A claim response can be lost. Repeating a claim returns the original assignment.
                    return world.equals(project.world) && kinds.contains(job.work.kind)
                            ? new Assignment(project.id, job) : null;
                }
                if (world.equals(project.world)) reserved.addAll(job.work.locks);
            }
        }
        for (Project project : projects.values()) {
            if (!world.equals(project.world)) continue;
            Set<String> done = new HashSet<>();
            for (Job job : project.jobs) if (job.state == State.DONE) done.add(job.work.id);
            for (Job job : project.jobs) {
                if (job.state != State.WAITING || !kinds.contains(job.work.kind)
                        || !done.containsAll(job.work.needs) || !Collections.disjoint(reserved, job.work.locks)) continue;
                Job assigned = new Job(job.work, State.LEASED, job.attempts + 1, worker,
                        UUID.randomUUID(), now + LEASE_MS, null);
                replace(project, assigned);
                return new Assignment(project.id, assigned);
            }
        }
        return null;
    }

    synchronized Job renew(String projectId, String jobId, UUID token, long now) throws IOException {
        Project project = projects.get(projectId);
        Job job = find(project, jobId);
        if (!owns(job, token, now)) return null;
        Job renewed = new Job(job.work, State.LEASED, job.attempts, job.worker,
                job.lease, now + LEASE_MS, null);
        replace(project, renewed);
        return renewed;
    }

    synchronized boolean finish(String projectId, String jobId, UUID token, boolean success,
                                String evidence, long now) throws IOException {
        if (evidence == null || evidence.isBlank() || evidence.length() > 2048) {
            throw new IllegalArgumentException("Report an observed outcome or failure reason");
        }
        Project project = projects.get(projectId);
        Job job = find(project, jobId);
        if (job != null && job.state == State.DONE) {
            return success && Objects.equals(job.lease, token) && job.evidence.equals(evidence);
        }
        if (!owns(job, token, now)) return false;
        Job result = success
                ? new Job(job.work, State.DONE, job.attempts, job.worker, job.lease, 0, evidence)
                : retry(job, evidence);
        replace(project, result);
        return true;
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
                    jobs.set(i, retry(job, "Worker lease expired"));
                }
            }
            if (jobs != null) {
                next.put(project.id, new Project(project.id, project.world, jobs));
                changed = true;
            }
        }
        if (changed) persist(next);
    }

    private static Job retry(Job job, String reason) {
        State state = job.attempts < MAX_ATTEMPTS ? State.WAITING : State.FAILED;
        return new Job(job.work, state, job.attempts, null, null, 0, reason);
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
        commit(new Project(project.id, project.world, jobs));
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
