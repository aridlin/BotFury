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
    static final int MAX_JOBS = 4096;
    enum Kind { EXCAVATE, GATHER, CRAFT, SMELT, TRANSFER, BUILD, VERIFY, INSPECT, BARRIER }
    enum State { WAITING, LEASED, DONE, FAILED }

    record Cleanup(String site, String afterSettled, List<String> alsoSettled) {
        Cleanup(String site, String afterSettled) {
            this(site, afterSettled, List.of());
        }

        Cleanup {
            alsoSettled = alsoSettled == null ? List.of() : List.copyOf(alsoSettled);
            if (alsoSettled.size() > MAX_JOBS || alsoSettled.stream().anyMatch(id -> !validId(id))) {
                throw new IllegalArgumentException("Invalid temporary support cleanup dependencies");
            }
            if (site == null || !site.matches("block:-?[0-9]+,-?[0-9]+,-?[0-9]+") || !validId(afterSettled)) {
                throw new IllegalArgumentException("Invalid temporary support cleanup");
            }
        }
    }

    record Work(String id, Kind kind, String payload, List<String> needs, Set<String> locks, String sameWorkerAs,
                Cleanup cleanup, String constructionMaterial) {
        Work(String id, Kind kind, String payload, List<String> needs, Set<String> locks, String sameWorkerAs,
             Cleanup cleanup) {
            this(id, kind, payload, needs, locks, sameWorkerAs, cleanup, null);
        }
        Work(String id, Kind kind, String payload, List<String> needs, Set<String> locks, String sameWorkerAs) {
            this(id, kind, payload, needs, locks, sameWorkerAs, null);
        }

        Work(String id, Kind kind, String payload, List<String> needs, Set<String> locks) {
            this(id, kind, payload, needs, locks, null);
        }
        Work {
            if (!validId(id) || kind == null || payload == null || payload.length() > 8192
                    || needs == null || locks == null || needs.size() > MAX_JOBS || locks.size() > 128
                    || kind == Kind.BARRIER && (!payload.isEmpty() || !locks.isEmpty()
                    || sameWorkerAs != null || cleanup != null || constructionMaterial != null)
                    || needs.stream().anyMatch(dep -> !validId(dep))
                    || sameWorkerAs != null && !needs.contains(sameWorkerAs)
                    || cleanup != null && (kind != Kind.EXCAVATE || needs.size() != 1
                    || sameWorkerAs != null || !locks.contains(cleanup.site))
                    || constructionMaterial != null && (kind != Kind.TRANSFER || needs.size() != 1
                    || sameWorkerAs != null || !constructionMaterial.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                    || locks.stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 240)) {
                throw new IllegalArgumentException("Invalid project job");
            }
            needs = List.copyOf(needs);
            locks = Set.copyOf(locks);
        }
    }

    record Job(Work work, State state, int attempts, String worker, UUID lease, long expiresAt,
               String evidence, ChestObservation chest, ResourceSite resourceSite) {
        Job(Work work, State state, int attempts, String worker, UUID lease, long expiresAt,
            String evidence, ChestObservation chest) {
            this(work, state, attempts, worker, lease, expiresAt, evidence, chest, null);
        }
    }
    record ResourceSite(int x, int y, int z) {
        ResourceSite {
            if (x < -30_000_000 || x > 30_000_000 || z < -30_000_000 || z > 30_000_000 || y < -2048 || y > 2048) {
                throw new IllegalArgumentException("Invalid gathering site");
            }
        }
    }
    record ChestReport(String location, Map<String, Integer> items, Integer free) {
        ChestReport(String location, Map<String, Integer> items) { this(location, items, null); }

        ChestReport {
            if (location == null || !location.matches("block:-?[0-9]+,-?[0-9]+,-?[0-9]+")
                    || items == null || items.size() > 54 || free != null && (free < 0 || free > 3456)
                    || items.entrySet().stream().anyMatch(item -> !item.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || item.getValue() == null || item.getValue() < 1 || item.getValue() > 3456)
                    || items.values().stream().mapToInt(Integer::intValue).sum() > 3456) {
                throw new IllegalArgumentException("Invalid chest observation");
            }
            items = Map.copyOf(items);
        }
    }
    record ChestObservation(ChestReport report, long observedAt) {}
    record Repeat(int batch, String item, String chest, int completed, ChestObservation lastStock,
                  boolean cancelled, int capacity) {
        Repeat(int batch, String item, String chest, int completed, ChestObservation lastStock, boolean cancelled) {
            this(batch, item, chest, completed, lastStock, cancelled, 1728);
        }

        int limit() { return capacity > 0 ? capacity : 1728; }
    }
    record Project(String id, String world, List<Job> jobs, Repeat repeat, String supplyChest,
                   Map<String, Integer> supplyRecoveries) {
        Project {
            jobs = List.copyOf(jobs);
            supplyRecoveries = supplyRecoveries == null ? Map.of() : Map.copyOf(supplyRecoveries);
        }

        Project withJobs(List<Job> updated) {
            return new Project(id, world, updated, repeat, supplyChest, supplyRecoveries);
        }
    }
    record Assignment(String project, String world, Job job) {}
    private record Board(int schema, Map<String, Project> projects) {}
    private record PickupTarget(String direction, String item, int stock) {}

    private static final Gson JSON = new Gson();
    private static final long LEASE_MS = 15_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long SUPPLIER_OFFLINE_MS = 60_000;
    private static final int MAX_SUPPLY_RECOVERIES = 2;
    private static final int MAX_PROJECTS = 32;
    private static final int MAX_DEPENDENCIES = 250_000;
    private final Path journal;
    private final Path archive;
    private final Map<String, Long> workerSeen = new HashMap<>();
    private Map<String, Project> projects;

    ProjectQueue(Path journal) throws IOException {
        this.journal = journal.toAbsolutePath();
        archive = this.journal.resolveSibling(this.journal.getFileName() + ".history");
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
        submit(id, world, work, null, null);
    }

    synchronized void submitOutput(String id, String world, List<Work> work, Repeat repeat) throws IOException {
        checkStorage(world, repeat.chest);
        submit(id, world, work, repeat, null);
    }

    synchronized void submitSupplied(String id, String world, List<Work> work, String chest) throws IOException {
        checkStorage(world, chest);
        submit(id, world, work, null, chest);
    }

    private void checkStorage(String world, String chest) {
        if (projects.values().stream().anyMatch(project -> project.world.equals(world)
                && chest.equals(reservedStorage(project)))) {
            throw new IllegalArgumentException("An active project already reserves this chest");
        }
    }

    private static String reservedStorage(Project project) {
        if (project.repeat != null && !project.repeat.cancelled) return project.repeat.chest;
        if (project.supplyChest == null) return null;
        var blocked = blockedJobs(project);
        for (Job job : project.jobs) {
            if (!blocked.contains(job.work.id) && (job.state == State.WAITING || job.state == State.LEASED)) {
                return project.supplyChest;
            }
        }
        return null;
    }

    private static Set<String> blockedJobs(Project project) {
        Set<String> blocked = new HashSet<>();
        for (Job job : project.jobs) {
            if (job.state == State.FAILED || (job.state == State.WAITING && job.work.needs.stream().anyMatch(blocked::contains))) {
                blocked.add(job.work.id);
            }
        }
        return blocked;
    }

    synchronized boolean cancelOutput(String world, String chest) throws IOException {
        for (Project project : projects.values()) {
            var repeat = project.repeat;
            if (!project.world.equals(world) || repeat == null || repeat.cancelled || !repeat.chest.equals(chest)) continue;
            var cancelled = new Repeat(repeat.batch, repeat.item, repeat.chest, repeat.completed, repeat.lastStock, true, repeat.capacity);
            commit(new Project(project.id, project.world, project.jobs, cancelled, project.supplyChest, project.supplyRecoveries));
            return true;
        }
        return false;
    }

    private void submit(String id, String world, List<Work> work, Repeat repeat, String supplyChest) throws IOException {
        if (!validId(id) || world == null || world.isBlank() || world.length() > 240
                || work == null || work.isEmpty() || work.size() > MAX_JOBS) {
            throw new IllegalArgumentException("Invalid project");
        }
        if (projects.containsKey(id) || readArchived(id) != null) {
            throw new IllegalArgumentException("Project already exists");
        }
        // Submit in topological order. This rejects both missing predecessors and cycles.
        int dependencyCount = 0;
        Set<String> defined = new HashSet<>();
        List<Job> jobs = new ArrayList<>();
        for (Work item : work) {
            if (item == null || !defined.containsAll(item.needs)
                    || item.cleanup != null && (repeat != null
                    || !defined.contains(item.cleanup.afterSettled) || !defined.containsAll(item.cleanup.alsoSettled))
                    || !defined.add(item.id)) {
                throw new IllegalArgumentException("Jobs must be unique and follow their dependencies");
            }
            dependencyCount += item.needs.size() + (item.cleanup == null ? 0 : item.cleanup.alsoSettled.size());
            if (dependencyCount > MAX_DEPENDENCIES) throw new IllegalArgumentException("Project has too many dependencies");
            jobs.add(new Job(item, State.WAITING, 0, null, null, 0, null, null));
        }
        var project = settleBarriers(new Project(id, world, jobs, repeat, supplyChest, Map.of()));
        var next = new LinkedHashMap<>(projects);
        if (next.size() >= MAX_PROJECTS) {
            var finished = next.values().stream().filter(ProjectQueue::terminal).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("All " + MAX_PROJECTS + " project slots are active"));
            // The old active copy remains authoritative until the new journal replaces it.
            writeAtomically(archive.resolve(finished.id + ".json"), finished);
            next.remove(finished.id);
        }
        next.put(id, project);
        persist(next);
    }

    private static boolean terminal(Project project) {
        if (project.jobs.stream().anyMatch(job -> job.state == State.LEASED)) return false;
        if (project.repeat != null) return project.repeat.cancelled;
        var blocked = blockedJobs(project);
        return project.jobs.stream().noneMatch(job -> job.state == State.WAITING && !blocked.contains(job.work.id));
    }

    synchronized List<String> history() throws IOException {
        try (var paths = Files.list(archive)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - 5))
                    .filter(ProjectQueue::validId)
                    .filter(id -> !projects.containsKey(id))
                    .sorted().toList();
        } catch (NoSuchFileException ignored) {
            return List.of();
        }
    }

    synchronized Project archived(String id) throws IOException {
        if (!validId(id)) throw new IllegalArgumentException("Invalid project ID");
        return projects.containsKey(id) ? null : readArchived(id);
    }

    private Project readArchived(String id) throws IOException {
        try {
            var restored = JSON.fromJson(Files.readString(archive.resolve(id + ".json")), Project.class);
            if (restored == null || !id.equals(restored.id) || !terminal(restored)) {
                throw new IOException("Invalid archived project " + id);
            }
            return restored;
        } catch (NoSuchFileException ignored) {
            return null;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid archived project " + id, invalid);
        }
    }

    synchronized Assignment claim(String world, String worker, Set<Kind> kinds, long now) throws IOException {
        if (world == null || !validId(worker) || kinds == null || kinds.isEmpty()) {
            throw new IllegalArgumentException("Invalid worker or world");
        }
        markSeen(world, worker, now);
        expire(now);
        recoverMissingSuppliers(world, now);
        Set<String> reserved = new HashSet<>();
        Map<String, String> projectReservations = new HashMap<>();
        for (Project project : projects.values()) {
            if (!world.equals(project.world)) continue;
            var storage = reservedStorage(project);
            if (storage != null) projectReservations.putIfAbsent(storage, project.id);
            var blocked = blockedJobs(project);
            for (Job job : project.jobs) {
                if (job.work.cleanup != null && (job.state == State.WAITING || job.state == State.LEASED)
                        && !blocked.contains(job.work.id)) projectReservations.putIfAbsent(job.work.cleanup.site, project.id);
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
        // TAKE measures an inventory minimum. The same blocks cannot satisfy two unfinished batches.
        var carried = pendingBuildMaterials(world, worker);
        Project ownedOrder = null;
        String ownedLane = null;
        for (Project project : projects.values()) {
            if (!world.equals(project.world) || cancelled(project) || project.repeat == null) continue;
            var pinned = project.jobs.stream().filter(job -> job.state == State.WAITING
                    && job.work.sameWorkerAs != null && worker.equalsIgnoreCase(find(project, job.work.sameWorkerAs).worker)
                    && find(project, job.work.sameWorkerAs).state == State.DONE).findFirst();
            if (pinned.isPresent()) {
                ownedOrder = project;
                ownedLane = lane(pinned.get().work.id);
                break;
            }
        }
        for (Project project : projects.values()) {
            if (!world.equals(project.world) || cancelled(project) || (ownedOrder != null && ownedOrder != project)) continue;
            Set<String> done = new HashSet<>();
            for (Job job : project.jobs) if (job.state == State.DONE) done.add(job.work.id);
            var blocked = blockedJobs(project);
            String deposit = project.repeat == null ? null : pendingDeposit(project, done);
            for (Job job : project.jobs) {
                if (job.work.constructionMaterial != null && carried.contains(job.work.constructionMaterial)) continue;
                if (ownedLane != null && !ownedLane.equals(lane(job.work.id))) continue;
                if (deposit != null && job.work.kind == Kind.TRANSFER && !deposit.equals(job.work.id)) continue;
                if (job.state != State.WAITING || job.work.kind == Kind.BARRIER
                        || job.expiresAt > now || !kinds.contains(job.work.kind)
                        || !done.containsAll(job.work.needs) || !Collections.disjoint(reserved, job.work.locks)) continue;
                if (job.work.cleanup != null && (!settled(job.work.cleanup.afterSettled, done, blocked)
                        || job.work.cleanup.alsoSettled.stream().anyMatch(id -> !settled(id, done, blocked)))) continue;
                if (job.work.locks.stream().anyMatch(key -> projectReservations.containsKey(key)
                        && !project.id.equals(projectReservations.get(key)))) continue;
                if (job.work.sameWorkerAs != null
                        && !worker.equalsIgnoreCase(find(project, job.work.sameWorkerAs).worker)) continue;
                Job assigned = new Job(job.work, State.LEASED, job.attempts + 1, worker,
                        UUID.randomUUID(), now + LEASE_MS, null, job.chest, job.resourceSite);
                replace(project, assigned);
                return new Assignment(project.id, project.world, assigned);
            }
        }
        return null;
    }

    private static boolean settled(String id, Set<String> done, Set<String> blocked) {
        return done.contains(id) || blocked.contains(id);
    }

    private Set<String> pendingBuildMaterials(String world, String worker) {
        var materials = new HashSet<String>();
        for (var project : projects.values()) {
            if (project.supplyChest == null || !project.world.equals(world)) continue;
            var pickups = new HashMap<String, String>();
            for (var job : project.jobs) {
                if (job.state == State.DONE && job.work.constructionMaterial != null
                        && worker.equalsIgnoreCase(job.worker)) {
                    pickups.put(job.work.id, job.work.constructionMaterial);
                }
            }
            if (pickups.isEmpty()) continue;
            var blocked = blockedJobs(project);
            for (var job : project.jobs) {
                if (job.work.kind != Kind.BUILD || job.work.sameWorkerAs == null
                        || job.state != State.WAITING || blocked.contains(job.work.id)) continue;
                var material = pickups.get(job.work.sameWorkerAs);
                if (material != null) materials.add(material);
            }
        }
        return materials;
    }

    synchronized Job renew(String projectId, String jobId, UUID token, long now) throws IOException {
        return renew(projectId, jobId, token, null, now);
    }

    synchronized Job renew(String projectId, String jobId, UUID token, ResourceSite site, long now) throws IOException {
        Project project = projects.get(projectId);
        Job job = find(project, jobId);
        if (!owns(job, token, now) || cancelled(project)) return null;
        Job renewed = new Job(job.work, State.LEASED, job.attempts, job.worker,
                job.lease, now + LEASE_MS, null, job.chest, gatheringSite(job, site));
        replace(project, renewed);
        markSeen(project.world, job.worker, now);
        return renewed;
    }

    synchronized boolean finish(String projectId, String jobId, UUID token, boolean success,
                                String evidence, long now) throws IOException {
        return finish(projectId, jobId, token, success, evidence, null, now);
    }

    synchronized boolean finish(String projectId, String jobId, UUID token, boolean success,
                                String evidence, ChestReport chest, long now) throws IOException {
        return finish(projectId, jobId, token, success, evidence, chest, null, now);
    }

    synchronized boolean finish(String projectId, String jobId, UUID token, boolean success,
                                String evidence, ChestReport chest, ResourceSite site, long now) throws IOException {
        return finish(projectId, jobId, token, success, evidence, chest, site, false, now);
    }

    synchronized boolean finish(String projectId, String jobId, UUID token, boolean success,
                                String evidence, ChestReport chest, ResourceSite site, boolean pickupShortfall,
                                long now) throws IOException {
        if (evidence == null || evidence.isBlank() || evidence.length() > 2048) {
            throw new IllegalArgumentException("Report an observed outcome or failure reason");
        }
        Project active = projects.get(projectId);
        Project project = active == null && validId(projectId) ? readArchived(projectId) : active;
        Job job = find(project, jobId);
        if (job != null && job.state == State.DONE) {
            return success && Objects.equals(job.lease, token) && job.evidence.equals(evidence)
                    && Objects.equals(job.chest == null ? null : job.chest.report, chest)
                    && (site == null || site.equals(job.resourceSite));
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
        var location = gatheringSite(job, site);
        if (pickupShortfall) {
            if (success || chest == null || job.work.kind != Kind.TRANSFER) {
                throw new IllegalArgumentException("Pickup shortfall requires a failed transfer and chest observation");
            }
            if (recoverShortPickup(project, job, evidence, observation)) {
                markSeen(project.world, job.worker, now);
                return true;
            }
        }
        Job result = success
                ? new Job(job.work, State.DONE, job.attempts, job.worker, job.lease, 0, evidence, observation, location)
                : retry(job, evidence, observation, location);
        if (success && project.repeat != null && !project.repeat.cancelled && job.work.id.equals(lane(job.work.id) + "inspect")) {
            prepareOutputDeposit(project, result);
        } else if (success && project.repeat != null && job.work.id.equals(lane(job.work.id) + "deposit")) {
            var repeat = project.repeat;
            var next = new Repeat(repeat.batch, repeat.item, repeat.chest, repeat.completed + 1, observation, repeat.cancelled, repeat.capacity);
            var reset = repeat.cancelled
                    ? project.jobs.stream().map(entry -> entry == job ? result : entry).toList()
                    : project.jobs.stream().map(entry -> lane(entry.work.id).equals(lane(job.work.id))
                            ? new Job(entry.work, State.WAITING, 0, null, null, 0, null, null, entry.resourceSite) : entry).toList();
            commit(new Project(project.id, project.world, reset, next, project.supplyChest, project.supplyRecoveries));
        } else replace(project, result);
        markSeen(project.world, job.worker, now);
        return true;
    }

    private static String lane(String id) {
        if (!id.startsWith("lane-")) return "";
        int end = id.indexOf('-', 5);
        return end < 0 ? "" : id.substring(0, end + 1);
    }

    private static String pendingDeposit(Project project, Set<String> done) {
        for (Job job : project.jobs) {
            String prefix = lane(job.work.id);
            if (job.work.id.equals(prefix + "deposit") && done.contains(prefix + "inspect")
                    && (job.state == State.WAITING || job.state == State.LEASED)) return job.work.id;
        }
        return null;
    }

    private void prepareOutputDeposit(Project project, Job inspected) throws IOException {
        var repeat = project.repeat;
        int current = inspected.chest.report.items.getOrDefault(repeat.item, 0);
        Integer free = inspected.chest.report.free;
        if (current + repeat.batch > repeat.limit() || free != null && free < repeat.batch) {
            replace(project, new Job(inspected.work, State.WAITING, 0, null, null,
                    inspected.chest.observedAt + 10_000, "Output chest has no room for this batch", inspected.chest));
            return;
        }
        String deposit = lane(inspected.work.id) + "deposit";
        var jobs = new ArrayList<Job>();
        for (Job entry : project.jobs) {
            if (entry.work.id.equals(inspected.work.id)) jobs.add(inspected);
            else if (entry.work.id.equals(deposit)) {
                Work old = entry.work;
                var payload = com.google.gson.JsonParser.parseString(old.payload).getAsJsonObject();
                payload.addProperty("stock", current + repeat.batch);
                var next = new Work(old.id, old.kind, JSON.toJson(payload), old.needs, old.locks, old.sameWorkerAs);
                jobs.add(new Job(next, State.WAITING, 0, null, null, 0, null, null));
            } else jobs.add(entry);
        }
        commit(project.withJobs(jobs));
    }

    synchronized Map<String, ChestObservation> chestStock(String world) throws IOException {
        Map<String, ChestObservation> latest = new TreeMap<>();
        for (String id : history()) {
            var project = readArchived(id);
            if (project != null) collectChestStock(world, project, latest);
        }
        for (Project project : projects.values()) collectChestStock(world, project, latest);
        return latest;
    }

    private static void collectChestStock(String world, Project project, Map<String, ChestObservation> latest) {
        if (!project.world.equals(world)) return;
        if (project.repeat != null && project.repeat.lastStock != null) mergeStock(latest, project.repeat.lastStock);
        for (Job job : project.jobs) {
            if (job.chest != null) mergeStock(latest, job.chest);
        }
    }

    private static void mergeStock(Map<String, ChestObservation> latest, ChestObservation observation) {
        latest.merge(observation.report.location, observation, (a, b) -> a.observedAt > b.observedAt ? a : b);
    }

    private static String workerKey(String world, String worker) {
        return world + "\n" + worker.toLowerCase(Locale.ROOT);
    }

    private void markSeen(String world, String worker, long now) {
        workerSeen.put(workerKey(world, worker), now);
    }

    private boolean recoverShortPickup(Project project, Job pickup, String evidence, ChestObservation observation)
            throws IOException {
        var chest = observation.report;
        if (!chest.location.equals(project.supplyChest) || pickup.work.needs.size() != 1) return false;
        var target = JSON.fromJson(pickup.work.payload, PickupTarget.class);
        if (target == null || !"TAKE".equals(target.direction) || target.item == null
                || chest.items.getOrDefault(target.item, 0) >= target.stock) return false;

        var jobs = new HashMap<String, Job>();
        project.jobs.forEach(job -> jobs.put(job.work.id, job));
        var deposit = jobs.get(pickup.work.needs.get(0));
        return rewindSupply(project, pickup, deposit, jobs, evidence, observation);
    }

    private void recoverMissingSuppliers(String world, long now) throws IOException {
        for (Project project : List.copyOf(projects.values())) {
            if (project.supplyChest == null || !world.equals(project.world)) continue;
            var jobs = new HashMap<String, Job>();
            project.jobs.forEach(job -> jobs.put(job.work.id, job));
            for (Job job : project.jobs) {
                if (job.state != State.WAITING || job.work.sameWorkerAs == null
                        || !job.work.needs.stream().allMatch(id -> jobs.get(id).state == State.DONE)) continue;
                var supplier = jobs.get(job.work.sameWorkerAs);
                var key = workerKey(world, supplier.worker);
                // After a coordinator restart, give workers the full window to reconnect.
                long seen = workerSeen.computeIfAbsent(key, ignored -> now);
                if (now - seen < SUPPLIER_OFFLINE_MS) continue;
                if (rewindSupply(project, job, supplier, jobs, "Supplier " + supplier.worker + " went offline",
                        job.chest)) break;
            }
        }
    }

    private boolean rewindSupply(Project project, Job waiting, Job supplier, Map<String, Job> jobs,
                                 String evidence, ChestObservation observation) throws IOException {
        Set<String> rewind = new HashSet<>();
        Job stage = supplier;
        // A supplied material follows a single dependency chain from gathering to chest collection.
        while (!stage.work.needs.isEmpty()) {
            if (stage.work.needs.size() != 1) return false;
            rewind.add(stage.work.id);
            stage = jobs.get(stage.work.needs.get(0));
        }
        if (stage.work.kind != Kind.GATHER) return false;
        String root = stage.work.id;
        rewind.add(root);
        // A second pickup may still be drawing from the same deposit.
        if (jobs.values().stream().anyMatch(job -> job != waiting && job.state == State.LEASED
                && (rewind.contains(job.work.id) || job.work.kind == Kind.TRANSFER
                && job.work.needs.stream().anyMatch(rewind::contains)))) return false;
        var recoveries = new HashMap<>(project.supplyRecoveries);
        int attempts = recoveries.getOrDefault(root, 0);
        if (attempts >= MAX_SUPPLY_RECOVERIES) {
            replace(project, new Job(waiting.work, State.FAILED, waiting.attempts, null, null, 0,
                    evidence + " after " + attempts + " supply recoveries", observation));
            return true;
        }
        recoveries.put(root, attempts + 1);
        rewind.add(waiting.work.id);
        var updated = project.jobs.stream().map(job -> rewind.contains(job.work.id)
                ? new Job(job.work, State.WAITING, 0, null, null, 0, evidence + "; reacquiring materials", job == waiting ? observation : job.chest, job.resourceSite)
                : job).toList();
        commit(new Project(project.id, project.world, updated, project.repeat, project.supplyChest, recoveries));
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
                    jobs.set(i, retry(job, "Worker lease expired", job.chest, job.resourceSite));
                }
            }
            if (jobs != null) {
                next.put(project.id, project.withJobs(jobs));
                changed = true;
            }
        }
        if (changed) persist(next);
    }

    private static ResourceSite gatheringSite(Job job, ResourceSite site) {
        if (site != null && job.work.kind != Kind.GATHER) throw new IllegalArgumentException("Only gathering reports a resource site");
        return site == null ? job.resourceSite : site;
    }

    private static Job retry(Job job, String reason, ChestObservation chest, ResourceSite site) {
        State state = job.attempts < MAX_ATTEMPTS ? State.WAITING : State.FAILED;
        return new Job(job.work, state, job.attempts, null, null, 0, reason, chest, site);
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
        commit(project.withJobs(jobs));
    }

    private void commit(Project project) throws IOException {
        var next = new LinkedHashMap<>(projects);
        next.put(project.id, settleBarriers(project));
        persist(next);
    }

    private static Project settleBarriers(Project project) {
        if (project.jobs.stream().noneMatch(job -> job.work.kind == Kind.BARRIER && job.state == State.WAITING)) {
            return project;
        }
        Set<String> done = new HashSet<>();
        List<Job> updated = new ArrayList<>(project.jobs.size());
        for (Job job : project.jobs) {
            if (job.work.kind == Kind.BARRIER && job.state == State.WAITING && done.containsAll(job.work.needs)) {
                job = new Job(job.work, State.DONE, 0, null, null, 0, "Prerequisites complete", null);
            }
            if (job.state == State.DONE) done.add(job.work.id);
            updated.add(job);
        }
        return project.withJobs(updated);
    }

    private void persist(Map<String, Project> next) throws IOException {
        writeAtomically(journal, new Board(1, next));
        projects = next;
    }

    private static void writeAtomically(Path destination, Object contents) throws IOException {
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "botfury-projects-", ".tmp");
        try {
            byte[] bytes = JSON.toJson(contents).getBytes(StandardCharsets.UTF_8);
            try (var output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }
}
