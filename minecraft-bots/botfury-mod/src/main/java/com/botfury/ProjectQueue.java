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
    enum Kind { EXCAVATE, GATHER, CRAFT, SMELT, TRANSFER, BUILD, VERIFY, INSPECT, BARRIER, SETTLEMENT_BARRIER, FILL_BUCKETS, RESUPPLY;

        boolean coordinatorOnly() { return this == BARRIER || this == SETTLEMENT_BARRIER; }
    }
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
                    || kind.coordinatorOnly() && (!payload.isEmpty() || !locks.isEmpty()
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
               String evidence, ChestObservation chest, ResourceSite resourceSite, Work gatheringAfterPickup) {
        Job(Work work, State state, int attempts, String worker, UUID lease, long expiresAt,
            String evidence, ChestObservation chest, ResourceSite resourceSite) {
            this(work, state, attempts, worker, lease, expiresAt, evidence, chest, resourceSite, null);
        }
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
    private record GatherSource(String resource, String variant, String item) {}
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
                   Map<String, Integer> supplyRecoveries, Map<String, ResourceSite> contributionOrigins) {
        Project(String id, String world, List<Job> jobs, Repeat repeat, String supplyChest,
                Map<String, Integer> supplyRecoveries) {
            this(id, world, jobs, repeat, supplyChest, supplyRecoveries, Map.of());
        }

        Project {
            jobs = List.copyOf(jobs);
            supplyRecoveries = supplyRecoveries == null ? Map.of() : Map.copyOf(supplyRecoveries);
            contributionOrigins = contributionOrigins == null ? Map.of() : Map.copyOf(contributionOrigins);
        }

        Project withJobs(List<Job> updated) {
            return withRecovery(updated, supplyRecoveries);
        }

        Project withRecovery(List<Job> updated, Map<String, Integer> recovery) {
            return new Project(id, world, updated, repeat, supplyChest, recovery, contributionOrigins);
        }

        Project withRepeat(List<Job> updated, Repeat value) {
            return new Project(id, world, updated, value, supplyChest, supplyRecoveries, contributionOrigins);
        }

        Project withContribution(List<Job> updated, String key, int count, ResourceSite origin, int stock, int returnRevision) {
            var amounts = new HashMap<>(supplyRecoveries);
            var origins = new HashMap<>(contributionOrigins);
            if (count > 0) {
                amounts.put(key, count);
                amounts.put(key + ":stock", stock);
                if (returnRevision > 0) amounts.merge(key + ":route", returnRevision, Math::max);
            } else {
                amounts.remove(key);
                amounts.remove(key + ":stock");
            }
            if (origin != null) origins.put(key, origin);
            else origins.remove(key);
            return new Project(id, world, updated, repeat, supplyChest, amounts, origins);
        }
    }
    record Assignment(String project, String world, Job job, FuelUse fuelFor, List<ResourceSite> accessRoute,
                      int cargoCheckpoint) {
        Assignment {
            accessRoute = accessRoute == null ? List.of() : List.copyOf(accessRoute);
        }
        Assignment(String project, String world, Job job) { this(project, world, job, null, List.of(), 0); }
    }
    record RouteBenchStock(String payload, String location, int reserve, Set<String> locks) {}
    record RouteToolDelivery(String recipient, ResourceSite position, String item, int previousCount, int craftTarget,
                             RouteBenchStock benchStock) {}
    private final Map<UUID, Boolean> routeToolRequests = new HashMap<>();

    record FuelUse(String job, String recipe, String input, String expectedFuel, int count) {}
    private record SmeltFuel(String recipe, String input, String fuel, String plannedFuel, int count) {}
    record OwnedStation(String project, String job, String payload) {}
    record OwnedPlacement(String project, String job, String payload) {}
    record CarriedSmelt(String project, Work work) {}
    record CarriedCraft(String project, Work work, String worker, UUID gatheringLease) {}
    record BlockedAccess(String project, Work job, String builder, boolean replacementScaffold) {
        String repairId() { return job.id + (replacementScaffold ? "-v2" : ""); }
    }
    record ScaffoldClearance(String project, Work job) {}
    record LocalInspection(String project, Work job) {}
    private static final java.util.regex.Pattern SCHEMATIC_PLACEMENT = java.util.regex.Pattern.compile("place-[0-9]+");
    private record StationTarget(ProjectBlock.Position target, ProjectBlock.Position stand, String block) {}
    private record Board(int schema, Map<String, Project> projects) {}
    private record PickupTarget(String direction, String item, int stock) {}
    private record ToolRecipe(String recipe) {}
    private enum CargoRecovery { ANY, CHECKPOINT, TOOL_FAILURE, CARRIED_GOAL }

    private static final Gson JSON = new Gson();
    private static final long LEASE_MS = 15_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int CONSTRUCTION_PICKUP_RETRY_RADIUS = 48;
    private static final int CONSTRUCTION_PICKUP_RETRY_CLIMB = 12;
    private static final int STOCK_RECOVERY_RADIUS = 64;
    private static final int PLACEMENT_HANDOFF_SEPARATION = 6;
    static final long PLACEMENT_HANDOFF_WINDOW_MS = 8_000;
    private static final long SUPPLIER_OFFLINE_MS = 60_000;
    private static final int MAX_SUPPLY_RECOVERIES = 2;
    private static final int MAX_PROJECTS = 32;
    private static final int MAX_DEPENDENCIES = 250_000;
    private static final String LEASE_EXPIRED = "Worker lease expired";
    private static final String COORDINATOR_RESTARTED = "Coordinator restarted during work";
    private static final String GATHER_STOCK_CONFIRMED = "Gathering target confirmed by chest stock";
    private static final String GATHER_HANDOFF = "Gathering handoff to ";
    private static final String GATHER_REASSIGNED = "Gathering reassigned to a worker with cargo";
    private static final String CONTRIBUTION_ACTIVE = "Delivering a partial stock contribution";
    private static final String CONTRIBUTION_RETRY = "Partial deposit ended before chest observation";
    private static final String CONTRIBUTION_SETTLED = "Partial stock contribution settled";
    static final String CARGO_CHECKPOINT = "Banking a mining checkpoint";
    private static final int MINING_CARGO_LIMIT = 4;
    private static final String CONSTRUCTION_PICKUP = "Collecting replacement materials for a nearby builder";
    static final String ALTERNATIVE_FUEL = "Using carried smelting fuel ";
    static final String TARGET_TIMEOUT = "Timed out approaching or changing the target";
    static final String PLACEMENT_REJECTED = "Placement was rejected at ";
    static final String GATHER_STALLED = "Gathering stalled without item or route progress";
    static final String EXCAVATION_TOOL_NEEDED = "No suitable tool in inventory for ";
    static final String GATHER_TOOLS_NEEDED = "Gathering needs renewed tools";
    private static final String EXHAUSTED_TOOLS = GATHER_TOOLS_NEEDED + " after " + MAX_SUPPLY_RECOVERIES
            + " supply recoveries";
    private static final String RECOVERING_TOOLS = GATHER_TOOLS_NEEDED + "; reacquiring materials";
    private static final List<String> PICKAXE_TIERS = List.of("minecraft:wooden_pickaxe", "minecraft:stone_pickaxe",
            "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe");
    private static final long STALLED_WORKER_COOLDOWN_MS = 30_000;
    private static final int GATHERING_RETRY_RADIUS = 64;
    private static final int GATHERING_RETRY_CLIMB = 12;
    private static final int FAILED_SITE_RADIUS = 12;
    private static final long GATHER_RECONNECT_GRACE_MS = 30_000;
    private final Path journal;
    private final Path archive;
    private final Map<String, Long> workerSeen = new HashMap<>();
    private final Set<String> unscopedWorkers = new HashSet<>();
    private final Set<UUID> restoredLeases = new HashSet<>();
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
            var upgraded = new LinkedHashMap<>(projects);
            boolean changed = false;
            for (var entry : projects.entrySet()) {
                var current = entry.getValue();
                var revised = parallelizeSavedManifest(current);
                if (revised != current) {
                    upgraded.put(entry.getKey(), revised);
                    changed = true;
                }
            }
            if (changed) persist(upgraded);
            for (var project : projects.values()) for (var job : project.jobs) {
                if (job.state == State.LEASED && job.lease != null) restoredLeases.add(job.lease);
            }
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

    private static Project parallelizeSavedManifest(Project project) {
        if (!project.id.startsWith("manifest-") || project.supplyChest == null || project.repeat != null) return project;
        var jobs = new LinkedHashMap<String, Job>();
        project.jobs.forEach(job -> jobs.put(job.work.id, job));
        boolean changed = false;
        for (var deposit : project.jobs) {
            if (deposit.state != State.WAITING || deposit.work.needs.size() != 1 || deposit.work.sameWorkerAs == null
                    || !deposit.work.locks.contains(project.supplyChest)) continue;
            var target = transferTarget(deposit);
            if (target == null || !"DEPOSIT".equals(target.direction) || target.item == null) continue;
            var root = cargoRoot(jobs, deposit);
            if (root == null || root.work.kind != Kind.GATHER || root.state != State.WAITING) continue;
            for (String need : root.work.needs) {
                var earlier = jobs.get(need);
                var previous = transferTarget(earlier);
                if (previous == null || !"DEPOSIT".equals(previous.direction) || !target.item.equals(previous.item)
                        || previous.stock >= target.stock || !earlier.work.locks.contains(project.supplyChest)) continue;
                jobs.put(root.work.id, withNeeds(root, root.work.needs.stream().filter(id -> !id.equals(need)).toList()));
                jobs.put(deposit.work.id, withNeeds(deposit, List.of(deposit.work.sameWorkerAs, need)));
                changed = true;
                break;
            }
        }
        return changed ? project.withJobs(List.copyOf(jobs.values())) : project;
    }

    private static PickupTarget transferTarget(Job job) {
        return job != null && job.work.kind == Kind.TRANSFER ? JSON.fromJson(job.work.payload, PickupTarget.class) : null;
    }

    private static Job cargoRoot(Map<String, Job> jobs, Job job) {
        var stage = jobs.get(job.work.sameWorkerAs);
        while (stage != null && stage.work.sameWorkerAs != null) stage = jobs.get(stage.work.sameWorkerAs);
        return stage;
    }

    private static Job withNeeds(Job job, List<String> needs) {
        var w = job.work;
        var work = new Work(w.id, w.kind, w.payload, needs, w.locks, w.sameWorkerAs, w.cleanup, w.constructionMaterial);
        return new Job(work, job.state, job.attempts, job.worker, job.lease, job.expiresAt, job.evidence, job.chest, job.resourceSite);
    }

    synchronized List<OwnedStation> ownedStations(String world) {
        var result = new ArrayList<OwnedStation>();
        for (var project : projects.values()) {
            if (!project.world.equals(world) || cancelled(project)
                    || project.jobs.stream().allMatch(job -> job.state == State.DONE)) continue;
            for (var job : project.jobs) {
                if (job.state == State.DONE && stationKey(job) != null) {
                    result.add(new OwnedStation(project.id, job.work.id, job.work.payload));
                }
            }
        }
        return result;
    }

    synchronized List<OwnedPlacement> ownedPlacements(String world) {
        var result = new ArrayList<OwnedPlacement>();
        for (var project : projects.values()) {
            if (!project.world.equals(world) || project.supplyChest == null || cancelled(project)
                    || project.jobs.stream().allMatch(job -> job.state == State.DONE)) continue;
            for (var job : project.jobs) {
                if (repairablePlacement(job)) result.add(new OwnedPlacement(project.id, job.work.id, job.work.payload));
            }
        }
        return result;
    }

    synchronized List<BlockedAccess> blockedAccess(String world) {
        return blockedAccess(world, false);
    }

    synchronized List<BlockedAccess> blockedPlacementPoses(String world) {
        return blockedAccess(world, true);
    }

    synchronized List<BlockedAccess> blockedLocalHandoffs(String world) {
        var result = new ArrayList<BlockedAccess>();
        for (var project : projects.values()) {
            if (!project.world.equals(world) || project.supplyChest == null || cancelled(project)) continue;
            for (var job : project.jobs) {
                String id = job.work.id;
                if (job.state != State.FAILED || !placementApproachFailure(job)
                        || !project.supplyRecoveries.containsKey("pose-handoff:" + id)
                        || project.supplyRecoveries.containsKey("local-handoff:" + id)
                        || !("recovery-" + id + "-cargo").equals(job.work.sameWorkerAs)
                        || job.work.needs.stream().anyMatch(need -> find(project, need).state != State.DONE)) continue;
                var pickup = find(project, job.work.sameWorkerAs);
                if (pickup.worker != null && pickup.work.constructionMaterial != null) {
                    result.add(new BlockedAccess(project.id, job.work, pickup.worker, false));
                }
            }
        }
        return result;
    }

    synchronized boolean installLocalHandoff(String world, BlockedAccess request, Work replacement,
                                               String worker, int held, long now) throws IOException {
        var project = projects.get(request.project);
        if (held < 0 || request.builder.equalsIgnoreCase(worker)
                || !blockedLocalHandoffs(world).contains(request) || !replacement.id.equals(request.job.id)
                || !replacement.needs.equals(request.job.needs)
                || !Objects.equals(replacement.sameWorkerAs, request.job.sameWorkerAs)) return false;
        if (!availableProjectWorker(project, worker, now)) return false;
        var pickup = find(project, replacement.sameWorkerAs);
        String material = pickup.work.constructionMaterial;
        if (hasPendingDepositOf(world, worker, material)
                || pendingBuildMaterials(world, worker).contains(material)
                    && !recoveryPickupPrecedesReservations(project, pickup, worker)) return false;
        var freshPickup = held >= 1
                ? new Job(pickup.work, State.DONE, pickup.attempts, worker, null, 0,
                        "Observed carried material for the local placement", pickup.chest)
                : replacementConstructionPickup(project, pickup, worker, 1, held, now);
        if (freshPickup == null) return false;
        var waiting = find(project, replacement.id);
        var jobs = new ArrayList<>(project.jobs);
        jobs.set(jobs.indexOf(pickup), freshPickup);
        jobs.set(jobs.indexOf(waiting), new Job(replacement, State.WAITING, 0, null, null, 0,
                "Assigned to a builder for a new local approach", waiting.chest, waiting.resourceSite));
        var counts = new HashMap<>(project.supplyRecoveries);
        counts.put("local-handoff:" + replacement.id, 1);
        commit(project.withRecovery(jobs, counts));
        return true;
    }

    private List<BlockedAccess> blockedAccess(String world, boolean alternatePose) {
        var result = new ArrayList<BlockedAccess>();
        for (var project : projects.values()) {
            if (!project.world.equals(world) || project.supplyChest == null || project.repeat != null) continue;
            boolean activeCleanup = project.jobs.stream().anyMatch(job -> (job.state == State.LEASED || job.state == State.WAITING)
                    && job.work.id.startsWith("remove-access-repair-"));
            for (var job : project.jobs) {
                if (job.state != State.FAILED || !placementApproachFailure(job) || job.work.kind != Kind.BUILD
                        || !SCHEMATIC_PLACEMENT.matcher(job.work.id).matches() || job.work.sameWorkerAs == null
                        || job.work.needs.stream().anyMatch(id -> find(project, id).state != State.DONE)) continue;
                boolean replanned = project.supplyRecoveries.containsKey("pose:" + job.work.id);
                boolean priorScaffold = project.supplyRecoveries.containsKey("access:" + job.work.id);
                boolean replacement = !alternatePose && priorScaffold;
                if (alternatePose ? replanned : activeCleanup || replacement && (!replanned
                        || project.supplyRecoveries.containsKey("access:" + job.work.id + "-v2"))) continue;
                var cargo = find(project, job.work.sameWorkerAs);
                if (cargo.state == State.DONE && cargo.worker != null && cargo.work.constructionMaterial != null) {
                    result.add(new BlockedAccess(project.id, job.work, cargo.worker, replacement));
                }
            }
        }
        return result;
    }

    synchronized Set<String> temporaryFooting(String projectId) {
        var project = projects.get(projectId);
        if (project == null) return Set.of();
        var cells = new HashSet<String>();
        for (var job : project.jobs) {
            if (job.work.cleanup != null && job.state != State.DONE) cells.add(job.work.cleanup.site);
        }
        return cells;
    }

    synchronized boolean installPlacementPose(String world, BlockedAccess request, Work replacement,
                                              int held, long now) throws IOException {
        var project = projects.get(request.project);
        if (!blockedPlacementPoses(world).contains(request) || !replacement.id.equals(request.job.id)
                || !replacement.needs.equals(request.job.needs)
                || !Objects.equals(replacement.sameWorkerAs, request.job.sameWorkerAs) || held < 0) return false;
        var waiting = find(project, request.job.id);
        var pickup = find(project, waiting.work.sameWorkerAs);
        var batch = project.jobs.stream().filter(job -> job.state != State.DONE
                && pickup.work.id.equals(job.work.sameWorkerAs)).toList();
        if (batch.stream().anyMatch(job -> job.state == State.LEASED)) return false;
        var freshPickup = held >= batch.size() ? pickup
                : replacementConstructionPickup(project, pickup, request.builder, batch.size(), held, now);
        if (freshPickup == null) return false;
        var jobs = new ArrayList<>(project.jobs);
        jobs.set(jobs.indexOf(pickup), freshPickup);
        jobs.set(jobs.indexOf(waiting), new Job(replacement, State.WAITING, 0, null, null, 0,
                "Retrying placement from a newly observed support face", waiting.chest, waiting.resourceSite));
        var recoveries = new HashMap<>(project.supplyRecoveries);
        recoveries.put("pose:" + waiting.work.id, 1);
        commit(project.withRecovery(jobs, recoveries));
        return true;
    }

    synchronized Set<String> accessReservations(String world) {
        var reserved = new HashSet<String>();
        for (var project : projects.values()) {
            if (project.world.equals(world)) {
                for (var job : project.jobs) {
                    // Finished jobs no longer need their former standing and interaction space.
                    if (job.state == State.WAITING || job.state == State.LEASED) {
                        reserved.addAll(job.work.locks);
                    } else if (job.work.kind == Kind.BUILD && SCHEMATIC_PLACEMENT.matcher(job.work.id).matches()) {
                        var placement = JSON.fromJson(job.work.payload, StationTarget.class);
                        if (placement != null && placement.target != null) {
                            reserved.add(ProjectBlock.key(placement.target.checked()));
                        }
                    }
                }
            }
        }
        return reserved;
    }

    synchronized List<LocalInspection> localInspections(String world) {
        var result = new ArrayList<LocalInspection>();
        var writes = activeInspectionWrites(world);
        for (var project : projects.values()) {
            if (!project.world.equals(world) || cancelled(project)) continue;
            for (var job : project.jobs) {
                if (localInspectionReady(project, job, writes)) result.add(new LocalInspection(project.id, job.work));
            }
        }
        return result;
    }

    synchronized boolean confirmLocalInspection(String world, LocalInspection check, String evidence) throws IOException {
        var project = projects.get(check.project);
        if (project == null || !world.equals(project.world) || cancelled(project)) return false;
        var job = find(project, check.job.id);
        if (job == null || !job.work.equals(check.job) || !localInspectionReady(project, job, activeInspectionWrites(world))) {
            return false;
        }
        replace(project, new Job(job.work, State.DONE, job.attempts, null, null, 0, evidence, null));
        restoredLeases.remove(job.lease);
        return true;
    }

    private Set<String> activeInspectionWrites(String world) {
        var writes = new HashSet<String>();
        for (var project : projects.values()) {
            if (!world.equals(project.world)) continue;
            for (var job : project.jobs) {
                if (job.state == State.LEASED && (job.work.kind == Kind.BUILD || job.work.kind == Kind.EXCAVATE
                        || job.work.kind == Kind.FILL_BUCKETS || job.work.kind == Kind.SMELT)) {
                    writes.addAll(job.work.locks);
                }
            }
        }
        return writes;
    }

    private static boolean localInspectionReady(Project project, Job job, Set<String> writes) {
        return job.work.kind == Kind.INSPECT && job.state != State.DONE && job.work.sameWorkerAs == null
                && Collections.disjoint(job.work.locks, writes)
                && job.work.needs.stream().allMatch(need -> find(project, need).state == State.DONE)
                && project.jobs.stream().noneMatch(other -> job.work.id.equals(other.work.sameWorkerAs));
    }

    synchronized List<ScaffoldClearance> scaffoldClearances(String world) {
        var ready = new ArrayList<ScaffoldClearance>();
        for (var project : projects.values()) {
            if (!project.world.equals(world) || cancelled(project)) continue;
            for (var job : project.jobs) {
                if (scaffoldClearanceReady(project, job)) ready.add(new ScaffoldClearance(project.id, job.work));
            }
        }
        return ready;
    }

    synchronized boolean confirmScaffoldClearance(String world, ScaffoldClearance check, String evidence) throws IOException {
        var project = projects.get(check.project);
        var current = find(project, check.job.id);
        if (current == null || !world.equals(project.world) || cancelled(project)
                || !current.work.equals(check.job) || !scaffoldClearanceReady(project, current)) return false;
        replace(project, new Job(current.work, State.DONE, current.attempts, null, null, 0, evidence, null));
        return true;
    }

    private static boolean scaffoldClearanceReady(Project project, Job job) {
        String prefix = "check-access-repair-";
        String id = job.work.id;
        if (job.work.kind != Kind.VERIFY || job.state == State.DONE || !id.startsWith(prefix)) return false;
        int step = id.lastIndexOf('-');
        return step > prefix.length() && project.supplyRecoveries.containsKey("access:" + id.substring(prefix.length(), step))
                && job.work.needs.stream().allMatch(need -> find(project, need).state == State.DONE);
    }

    synchronized boolean installAccess(String world, BlockedAccess request, StructureProject.AccessRepair repair,
                                       long now) throws IOException {
        var project = projects.get(request.project);
        var waiting = find(project, request.job.id);
        if (waiting == null || !waiting.work.equals(request.job) || !blockedAccess(world).contains(request)
                || project.jobs.size() + repair.before().size() + repair.cleanup().size() >= MAX_JOBS
                || projects.values().stream().anyMatch(other -> other != project && other.world.equals(world)
                    && project.supplyChest.equals(reservedStorage(other)))) return false;
        var cargo = find(project, waiting.work.sameWorkerAs);
        var stockByChest = new HashMap<String, ChestObservation>();
        collectChestStock(world, project, stockByChest);
        var stock = stockByChest.get(project.supplyChest);
        String item = "minecraft:cobblestone";
        if (stock == null || stockCount(stock.report, item) < repair.stone()
                || item.equals(cargo.work.constructionMaterial)
                || pendingBuildMaterials(world, request.builder).contains(item)
                || hasPendingDepositOf(world, request.builder, item)) return false;
        for (var job : project.jobs) {
            if (job.work.kind != Kind.TRANSFER || !job.work.locks.contains(project.supplyChest)) continue;
            var transfer = transferTarget(job);
            if (transfer != null && ("VERIFY_STOCK".equals(transfer.direction)
                    || job.state != State.DONE && ("TAKE".equals(transfer.direction) || "WITHDRAW".equals(transfer.direction)))
                    && (transfer.item == null || transfer.item.equals(item))) return false;
        }
        var payload = JSON.fromJson(cargo.work.payload, com.google.gson.JsonObject.class);
        payload.addProperty("item", item);
        payload.addProperty("direction", "TAKE");
        payload.addProperty("stock", repair.stone());
        var pickup = new Work("repair-" + request.repairId() + "-stock", Kind.TRANSFER, JSON.toJson(payload),
                cargo.work.needs, cargo.work.locks, null, null, item);
        var jobs = new ArrayList<>(project.jobs);
        int index = jobs.indexOf(waiting);
        jobs.set(index, new Job(repair.placement(), State.WAITING, 0, null, null, 0,
                "Waiting for temporary scaffold", waiting.chest, waiting.resourceSite));
        jobs.add(index++, new Job(pickup, State.WAITING, 0, request.builder, null,
                now + LEASE_MS, CONSTRUCTION_PICKUP, null));
        for (var work : repair.before()) jobs.add(index++, new Job(work, State.WAITING, 0, null, null, 0, null, null));
        for (var work : repair.cleanup()) jobs.add(new Job(work, State.WAITING, 0, null, null, 0, null, null));
        var counts = new HashMap<>(project.supplyRecoveries);
        counts.put("access:" + request.repairId(), 1);
        commit(project.withRecovery(jobs, counts));
        return true;
    }

    private static boolean repairablePlacement(Job job) {
        return job != null && job.state == State.DONE && job.work.kind == Kind.BUILD && job.work.sameWorkerAs != null
                && SCHEMATIC_PLACEMENT.matcher(job.work.id).matches();
    }

    synchronized boolean repairMissingPlacement(String world, String projectId, String jobId) throws IOException {
        var project = projects.get(projectId);
        var lost = find(project, jobId);
        if (project == null || !world.equals(project.world) || project.supplyChest == null || cancelled(project)
                || !repairablePlacement(lost)
                || project.jobs.stream().anyMatch(job -> job.state == State.LEASED && job.work.needs.contains(jobId))) return false;
        var repairCounts = new HashMap<>(project.supplyRecoveries);
        String key = "placement:" + jobId;
        int repairs = repairCounts.getOrDefault(key, 0);
        if (repairs >= MAX_SUPPLY_RECOVERIES) return false;
        var jobs = new ArrayList<Job>();
        for (var job : project.jobs) {
            if (job == lost || job.state == State.FAILED && job.work.kind == Kind.BUILD
                    && job.work.needs.contains(jobId) && placementApproachFailure(job)) {
                job = new Job(job.work, State.WAITING, 0, null, null, 0,
                        job == lost ? "Previously built block is missing" : "Waiting for missing support to be rebuilt",
                        job.chest, job.resourceSite);
            }
            jobs.add(job);
        }
        repairCounts.put(key, repairs + 1);
        commit(project.withRecovery(jobs, repairCounts));
        return true;
    }

    synchronized boolean restoreOwnedStation(String world, String projectId, String jobId) throws IOException {
        return restoreOwnedStation(world, projectId, jobId, List.of());
    }

    synchronized boolean restoreOwnedStation(String world, String projectId, String jobId, List<Work> footing) throws IOException {
        var project = projects.get(projectId);
        var station = find(project, jobId);
        String key = station == null ? null : stationKey(station);
        if (project == null || !project.world.equals(world) || cancelled(project)
                || key == null || station.state != State.DONE || project.jobs.size() + footing.size() > MAX_JOBS) return false;
        var jobs = new HashMap<String, Job>();
        project.jobs.forEach(job -> jobs.put(job.work.id, job));
        var recipe = jobs.get(station.work.sameWorkerAs);
        if (recipe == null || recipe.state != State.DONE) return false;
        var revised = new ArrayList<Job>();
        for (var job : project.jobs) {
            var work = job.work;
            if (job == station && !footing.isEmpty()) {
                for (var step : footing) {
                    if (jobs.containsKey(step.id) || !jobs.keySet().containsAll(step.needs)) return false;
                    var ready = new Job(step, State.WAITING, 0, null, null, 0, null, null);
                    jobs.put(step.id, ready);
                    revised.add(ready);
                }
                var needs = new ArrayList<>(work.needs);
                needs.add(footing.get(footing.size() - 1).id);
                var updated = new Work(work.id, work.kind, work.payload, needs, work.locks, work.sameWorkerAs,
                        work.cleanup, work.constructionMaterial);
                station = new Job(updated, job.state, job.attempts, job.worker, job.lease, job.expiresAt,
                        job.evidence, job.chest, job.resourceSite);
                job = station;
            } else if (job.state == State.FAILED && work.locks.contains(key) && stationFailure(job)) {
                job = new Job(work, State.WAITING, 0, null, null, 0, "Waiting for station repair", job.chest, job.resourceSite);
            }
            jobs.put(work.id, job);
            revised.add(job);
        }
        return rewindSupply(project.withJobs(revised), station, recipe, jobs, "Owned station is missing", null);
    }

    private static boolean stationFailure(Job job) {
        return switch (job.work.kind) {
            case CRAFT, SMELT -> "Expected workstation is missing".equals(job.evidence);
            case TRANSFER -> "Storage chest changed size or location".equals(job.evidence);
            default -> false;
        };
    }

    private static String stationKey(Job job) {
        if (job.work.kind != Kind.BUILD || job.work.sameWorkerAs == null) return null;
        String expected = switch (job.work.id) {
            case "setup-workbench" -> "minecraft:crafting_table";
            case "setup-furnace" -> "minecraft:furnace";
            case "setup-storage" -> "minecraft:chest";
            default -> null;
        };
        if (expected == null) return null;
        var location = JSON.fromJson(job.work.payload, StationTarget.class);
        if (location == null || location.target == null || !expected.equals(location.block)) return null;
        var target = location.target;
        String key = "block:" + target.x() + "," + target.y() + "," + target.z();
        return job.work.locks.contains(key) ? key : null;
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
            if (blockedByPrevious(job, blocked)) blocked.add(job.work.id);
        }
        return blocked;
    }

    private static boolean blockedByPrevious(Job job, Set<String> blocked) {
        return job.state == State.FAILED || job.state == State.WAITING
                && job.work.kind != Kind.SETTLEMENT_BARRIER && job.work.needs.stream().anyMatch(blocked::contains);
    }

    synchronized boolean cancelOutput(String world, String chest) throws IOException {
        return cancelOutput(world, chest, null);
    }

    synchronized boolean cancelOutput(String world, String chest, String projectId) throws IOException {
        for (Project project : projects.values()) {
            var repeat = project.repeat;
            if (!project.world.equals(world) || repeat == null || repeat.cancelled || !repeat.chest.equals(chest)
                    || projectId != null && !projectId.equals(project.id)) continue;
            var cancelled = new Repeat(repeat.batch, repeat.item, repeat.chest, repeat.completed, repeat.lastStock, true, repeat.capacity);
            commit(project.withRepeat(project.jobs, cancelled));
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
        return claim(world, worker, kinds, null, now);
    }

    synchronized Assignment claim(String world, String worker, Set<Kind> kinds, String projectId, long now) throws IOException {
        return claim(world, worker, kinds, projectId, now, null);
    }

    synchronized Assignment claim(String world, String worker, Set<Kind> kinds, String projectId,
                                  long now, ResourceSite position) throws IOException {
        return claim(world, worker, kinds, projectId, now, position, material -> false);
    }

    synchronized Assignment claim(String world, String worker, Set<Kind> kinds, String projectId, long now,
                                  ResourceSite position, java.util.function.Predicate<String> hasExcavationTool) throws IOException {
        return claim(world, worker, kinds, projectId, now, position, hasExcavationTool, null, null);
    }

    synchronized Assignment claim(String world, String worker, Set<Kind> kinds, String projectId, long now,
                                  ResourceSite position, java.util.function.Predicate<String> hasExcavationTool,
                                  java.util.function.ToIntBiFunction<String, String> heldCount,
                                  Map<String, Integer> toolUses) throws IOException {
        return claim(world, worker, kinds, projectId, now, position, hasExcavationTool, heldCount, toolUses, 0);
    }

    synchronized Assignment claim(String world, String worker, Set<Kind> kinds, String projectId, long now,
                                  ResourceSite position, java.util.function.Predicate<String> hasExcavationTool,
                                  java.util.function.ToIntBiFunction<String, String> heldCount,
                                  Map<String, Integer> toolUses, int returnRevision) throws IOException {
        if (world == null || !validId(worker) || kinds == null || kinds.isEmpty()
                || projectId != null && !validId(projectId)) {
            throw new IllegalArgumentException("Invalid worker or world");
        }
        markSeen(world, worker, projectId, now);
        var workerKey = workerKey(world, worker, null);
        if (projectId == null) unscopedWorkers.add(workerKey);
        else unscopedWorkers.remove(workerKey);
        expire(now);
        recoverMissingBuildCargo(world);
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
                            && (projectId == null || projectId.equals(project.id))
                            ? assignment(project, job) : null;
                }
                if (world.equals(project.world)) reserved.addAll(job.work.locks);
            }
        }
        // TAKE measures an inventory minimum. The same blocks cannot satisfy two unfinished batches.
        var carried = pendingBuildMaterials(world, worker);
        Project ownedOrder = null;
        String ownedLane = null;
        for (Project project : projects.values()) {
            if (!world.equals(project.world) || cancelled(project) || project.repeat == null
                    || projectId != null && !projectId.equals(project.id)) continue;
            var pinned = project.jobs.stream().filter(job -> job.state == State.WAITING
                    && job.work.sameWorkerAs != null && worker.equalsIgnoreCase(find(project, job.work.sameWorkerAs).worker)
                    && find(project, job.work.sameWorkerAs).state == State.DONE).findFirst();
            if (pinned.isPresent()) {
                ownedOrder = project;
                ownedLane = lane(pinned.get().work.id);
                break;
            }
        }
        Project fallbackProject = null;
        Job fallbackJob = null;
        boolean waitingWithCargo = false;
        for (Project project : projects.values()) {
            if (!world.equals(project.world) || cancelled(project) || (ownedOrder != null && ownedOrder != project)
                    || projectId != null && !projectId.equals(project.id)) continue;
            Set<String> done = new HashSet<>();
            Set<String> cargoAwaitingHandoff = new HashSet<>();
            var byId = new HashMap<String, Job>();
            for (Job job : project.jobs) {
                byId.put(job.work.id, job);
                if (job.state == State.DONE) done.add(job.work.id);
                if (placementHandoffOpen(job, now)) cargoAwaitingHandoff.add(job.work.sameWorkerAs);
            }
            var unavailableStations = new HashSet<String>();
            for (var job : project.jobs) {
                if (job.state == State.DONE) continue;
                var key = stationKey(job);
                if (key != null) unavailableStations.add(key);
            }
            var blocked = blockedJobs(project);
            String deposit = project.repeat == null ? null : pendingDeposit(project, done);
            for (Job job : project.jobs) {
                if (job.work.constructionMaterial != null && carried.contains(job.work.constructionMaterial)
                        && !recoveryPickupPrecedesReservations(project, job, worker)) continue;
                if (ownedLane != null && !ownedLane.equals(lane(job.work.id))) continue;
                if (deposit != null && job.work.kind == Kind.TRANSFER && !deposit.equals(job.work.id)) continue;
                if (job.work.kind.coordinatorOnly() || cargoAwaitingHandoff.contains(job.work.sameWorkerAs)
                        || CARGO_CHECKPOINT.equals(job.evidence) && checkpointDepositActive(project, job)
                        || retryDelayedFor(job, worker, now)
                        || !kinds.contains(job.work.kind)) continue;
                boolean nearbyPickup = canRetryConstructionPickup(job, position);
                boolean nearbyCleanup = canRetryScaffoldCleanup(job, position, hasExcavationTool);
                var excavationMaterial = excavationToolMaterial(job);
                boolean equippedRetry = excavationMaterial != null && hasExcavationTool.test(excavationMaterial);
                if (excavationMaterial != null && !equippedRetry) continue;
                if (job.state != State.WAITING && !nearbyPickup && !nearbyCleanup
                        && !(equippedRetry && job.state == State.FAILED && job.attempts == MAX_ATTEMPTS)) continue;
                if (!done.containsAll(job.work.needs)) {
                    if (job.work.kind == Kind.TRANSFER && job.work.needs.size() > 1 && !blocked.contains(job.work.id)
                            && job.work.sameWorkerAs != null && done.contains(job.work.sameWorkerAs)
                            && worker.equalsIgnoreCase(byId.get(job.work.sameWorkerAs).worker)) {
                        waitingWithCargo |= earlierDepositInProgress(job, byId, done);
                    }
                    continue;
                }
                if (job.work.cleanup != null && (!settled(job.work.cleanup.afterSettled, done, blocked)
                        || job.work.cleanup.alsoSettled.stream().anyMatch(id -> !settled(id, done, blocked)))) continue;
                boolean pinned = job.work.sameWorkerAs != null;
                if (pinned
                        && !worker.equalsIgnoreCase(byId.get(job.work.sameWorkerAs).worker)) continue;
                boolean usesStation = job.work.kind == Kind.TRANSFER || job.work.kind == Kind.CRAFT || job.work.kind == Kind.SMELT;
                if (usesStation && !Collections.disjoint(unavailableStations, job.work.locks)
                        || !Collections.disjoint(reserved, job.work.locks) || job.work.locks.stream().anyMatch(key ->
                        projectReservations.containsKey(key) && !project.id.equals(projectReservations.get(key)))) {
                    waitingWithCargo |= pinned;
                    continue;
                }
                // Finish using inventory already gathered for a chain before starting unrelated work.
                if (job.work.sameWorkerAs != null || ownedOrder != null || gatherReconnectPending(job, now)
                        || CONSTRUCTION_PICKUP.equals(job.evidence) && job.expiresAt > now) {
                    return assign(project, job, worker, now, position);
                }
                if (fallbackJob == null || (nearbyPickup || nearbyCleanup) && fallbackJob.state != State.FAILED) {
                    fallbackProject = project;
                    fallbackJob = job;
                }
            }
        }
        if (waitingWithCargo) return null;
        // Existing leases and ready handoffs were checked above. Prefer rescuing mining work over a new independent job.
        if (heldCount != null && kinds.contains(Kind.TRANSFER)) {
            var deposit = offerInterruptedDeposit(world, worker, projectId, now, position, heldCount, CargoRecovery.TOOL_FAILURE, returnRevision);
            if (deposit == null) {
                deposit = offerInterruptedDeposit(world, worker, projectId, now, position, heldCount, CargoRecovery.CARRIED_GOAL, returnRevision);
            }
            if (deposit != null) return deposit;
        }
        if (kinds.contains(Kind.GATHER)) {
            if (toolUses != null) {
                var mining = offerEquippedMiner(world, worker, projectId, toolUses, now, true);
                if (mining != null) return mining;
            }
            if (recoverStalledGathering(world, projectId, now) || heldCount != null && recoverToolsAfterBanking(world, projectId)) {
                return claim(world, worker, kinds, projectId, now, position, hasExcavationTool, heldCount, toolUses, returnRevision);
            }
        }
        return fallbackJob == null ? null : assign(fallbackProject, fallbackJob, worker, now, position);
    }

    private static String excavationToolMaterial(Job job) {
        if (job.work.kind != Kind.EXCAVATE || job.evidence == null || !job.evidence.startsWith(EXCAVATION_TOOL_NEEDED)) {
            return null;
        }
        String material = job.evidence.substring(EXCAVATION_TOOL_NEEDED.length());
        return material.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? material : null;
    }

    private static boolean canRetryConstructionPickup(Job job, ResourceSite position) {
        // Location can change after the normal attempts. Give one local worker a chance to finish the pickup.
        if (position == null || job.state != State.FAILED || job.attempts != MAX_ATTEMPTS
                || job.work.constructionMaterial == null || !TARGET_TIMEOUT.equals(job.evidence)) return false;
        var pickup = transferTarget(job);
        if (pickup == null || !"TAKE".equals(pickup.direction)) return false;
        var target = JSON.fromJson(job.work.payload, StationTarget.class).target;
        return target != null && target.y() - position.y <= CONSTRUCTION_PICKUP_RETRY_CLIMB
                && distance(position, target) <= CONSTRUCTION_PICKUP_RETRY_RADIUS;
    }

    private static boolean canRetryScaffoldCleanup(Job job, ResourceSite position,
                                                   java.util.function.Predicate<String> hasExcavationTool) {
        if (position == null || job.work.cleanup == null || job.state != State.FAILED
                || job.attempts >= MAX_ATTEMPTS + 2 || !TARGET_TIMEOUT.equals(job.evidence)) return false;
        var block = JSON.fromJson(job.work.payload, StationTarget.class);
        return block != null && block.target != null && block.block != null && distance(position, block.target) <= 8
                && Math.abs((long) position.y - block.target.y()) <= 3 && hasExcavationTool.test(block.block);
    }

    private static boolean earlierDepositInProgress(Job deposit, Map<String, Job> jobs, Set<String> done) {
        for (String id : deposit.work.needs) {
            if (done.contains(id)) continue;
            var earlier = jobs.get(id);
            if (earlier.work.kind != Kind.TRANSFER || earlier.work.sameWorkerAs == null) continue;
            var root = cargoRoot(jobs, earlier);
            if (root != null && (root.state == State.DONE || root.state == State.LEASED)) return true;
        }
        return false;
    }

    synchronized boolean offerConstructionHandoff(String world, String worker, String projectId,
                                                  Set<Kind> kinds, long now,
                                                  java.util.function.Function<String, ResourceSite> locate,
                                                  java.util.function.ToIntBiFunction<String, String> heldCount) throws IOException {
        var candidate = locate.apply(worker);
        if (candidate == null) return false;
        var activeProjects = new HashMap<String, Project>();
        for (var project : projects.values()) {
            for (var job : project.jobs) {
                if (job.state == State.LEASED && job.expiresAt > now) {
                    activeProjects.put(job.worker.toLowerCase(Locale.ROOT), project);
                }
            }
        }
        if (activeProjects.containsKey(worker.toLowerCase(Locale.ROOT))) return false;
        var reserved = pendingBuildMaterials(world, worker);
        for (var project : projects.values()) {
            if (project.supplyChest == null || !world.equals(project.world) || cancelled(project)
                    || projectId != null && !project.id.equals(projectId)) continue;
            for (var job : project.jobs) {
                boolean failedApproach = placementHandoffOpen(job, now)
                        || job.state == State.FAILED && placementApproachFailure(job);
                if (job.state != State.WAITING && !failedApproach || job.work.kind != Kind.BUILD
                        || job.work.sameWorkerAs == null || !SCHEMATIC_PLACEMENT.matcher(job.work.id).matches()) continue;
                var pickup = find(project, job.work.sameWorkerAs);
                String material = pickup.work.constructionMaterial;
                if (pickup.state != State.DONE || pickup.worker == null || material == null
                        || worker.equalsIgnoreCase(pickup.worker) || reserved.contains(material)
                        || hasPendingDepositOf(world, worker, material)
                        || job.work.needs.stream().anyMatch(id -> find(project, id).state != State.DONE)) continue;
                var activeProject = activeProjects.get(pickup.worker.toLowerCase(Locale.ROOT));
                if (!failedApproach && (activeProject == null || activeProject == project)) continue;
                var target = JSON.fromJson(job.work.payload, StationTarget.class).target;
                if (target == null) continue;
                double distance = distance(candidate, target);
                String handoff = "build-handoff:" + pickup.work.id;
                String poseHandoff = "pose-handoff:" + job.work.id;
                if (project.supplyRecoveries.containsKey(poseHandoff)) continue;
                boolean useAlternatePose = project.supplyRecoveries.getOrDefault(handoff, 0) >= MAX_SUPPLY_RECOVERIES;
                if (useAlternatePose) {
                    if (job.state != State.FAILED || !failedApproach
                            || !project.supplyRecoveries.containsKey("pose:" + job.work.id)) continue;
                    var storage = JSON.fromJson(pickup.work.payload, StationTarget.class).target;
                    if (distance > 40 || target.y() - candidate.y > CONSTRUCTION_PICKUP_RETRY_CLIMB
                            || storage == null || distance(candidate, storage) > 32) continue;
                } else if (distance > 24) continue;
                var original = locate.apply(pickup.worker);
                if (failedApproach) {
                    // A close worker can still be stranded on the wrong side of a wall or height change.
                    if (original != null && distance(candidate, new ProjectBlock.Position(original.x, original.y, original.z))
                            < PLACEMENT_HANDOFF_SEPARATION) continue;
                } else if (original == null || distance(original, target) < distance + 16) continue;
                int held = heldCount.applyAsInt(worker, material);
                boolean canCollect = kinds.contains(Kind.TRANSFER);
                boolean reassigned = useAlternatePose
                        ? handoffReplannedPlacement(project, job, pickup, worker, held, canCollect, poseHandoff, now)
                        : reassignConstructionCargo(project, pickup, worker, held, canCollect, handoff, MAX_SUPPLY_RECOVERIES, now);
                if (reassigned) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double distance(ResourceSite site, ProjectBlock.Position target) {
        return Math.hypot(Math.hypot((double) site.x - target.x(), (double) site.y - target.y()),
                (double) site.z - target.z());
    }

    private boolean handoffReplannedPlacement(Project project, Job placement, Job batchPickup, String worker,
                                              int held, boolean canCollect, String recovery, long now) throws IOException {
        String pickupId = "recovery-" + placement.work.id + "-cargo";
        if (held < 0 || project.jobs.size() >= MAX_JOBS || find(project, pickupId) != null) return false;
        var original = batchPickup.work;
        var payload = JSON.fromJson(original.payload, com.google.gson.JsonObject.class);
        payload.addProperty("stock", 1);
        var pickupWork = new Work(pickupId, Kind.TRANSFER, JSON.toJson(payload), original.needs,
                original.locks, null, null, original.constructionMaterial);
        var pickup = new Job(pickupWork, State.DONE, 0, worker, null, 0,
                "Observed carried material for the replanned placement", batchPickup.chest);
        if (held == 0) {
            if (!canCollect) return false;
            pickup = replacementConstructionPickup(project, pickup, worker, 1, held, now);
            if (pickup == null) return false;
        }
        var work = placement.work;
        var needs = work.needs.stream().map(id -> id.equals(original.id) ? pickupId : id).toList();
        var replanned = new Work(work.id, work.kind, work.payload, needs, work.locks, pickupId);
        var jobs = new ArrayList<>(project.jobs);
        int index = jobs.indexOf(placement);
        jobs.set(index, new Job(replanned, State.WAITING, 0, null, null, 0,
                "Reassigned replanned placement to a nearby builder", placement.chest, placement.resourceSite));
        jobs.add(index, pickup);
        var counts = new HashMap<>(project.supplyRecoveries);
        counts.put(recovery, 1);
        commit(project.withRecovery(jobs, counts));
        return true;
    }

    private boolean reassignConstructionCargo(Project project, Job pickup, String worker, int held,
                                              boolean canCollect, String handoff, int limit, long now) throws IOException {
        var batch = project.jobs.stream().filter(job -> job.state != State.DONE
                && pickup.work.id.equals(job.work.sameWorkerAs)).toList();
        if (pickup.state != State.DONE || batch.stream().anyMatch(job -> job.work.kind != Kind.BUILD
                || job.state == State.LEASED)) return false;
        int handoffs = project.supplyRecoveries.getOrDefault(handoff, 0);
        if (handoffs >= limit || held < 0) return false;
        Job replacement = held >= batch.size()
                ? new Job(pickup.work, State.DONE, pickup.attempts, worker, pickup.lease, 0, pickup.evidence, pickup.chest)
                : canCollect ? replacementConstructionPickup(project, pickup, worker, batch.size(), held, now) : null;
        if (replacement == null) return false;
        var recovery = new HashMap<>(project.supplyRecoveries);
        recovery.put(handoff, handoffs + 1);
        var jobs = project.jobs.stream().map(job -> {
            if (job == pickup) return replacement;
            if (batch.contains(job) && (placementHandoffOpen(job, now)
                    || job.state == State.FAILED && placementApproachFailure(job))) {
                return new Job(job.work, State.WAITING, 0, null, null, 0,
                        "Reassigned to a nearby builder", job.chest, job.resourceSite);
            }
            return job;
        }).toList();
        var reopened = project.withRecovery(jobs, recovery);
        // Reopening this placement can revive a separate batch already supplied by the replacement worker.
        if (pendingBuildMaterials(reopened, worker, pickup.work.id).contains(pickup.work.constructionMaterial)) return false;
        commit(reopened);
        return true;
    }

    private Job replacementConstructionPickup(Project project, Job pickup, String worker, int required, int held,
                                              long now) {
        var target = transferTarget(pickup);
        if (target == null || !"TAKE".equals(target.direction) || !pickup.work.constructionMaterial.equals(target.item)
                || !pickup.work.locks.contains(project.supplyChest)
                || projects.values().stream().anyMatch(other -> !other.id.equals(project.id)
                && project.world.equals(other.world) && project.supplyChest.equals(reservedStorage(other)))) return null;
        var observed = new HashMap<String, ChestObservation>();
        collectChestStock(project.world, project, observed);
        var stock = observed.get(project.supplyChest);
        if (stock == null || stockCount(stock.report, target.item) < required - held) return null;
        var payload = JSON.fromJson(pickup.work.payload, com.google.gson.JsonObject.class);
        payload.addProperty("stock", required);
        var work = new Work(pickup.work.id, pickup.work.kind, JSON.toJson(payload), pickup.work.needs,
                pickup.work.locks, pickup.work.sameWorkerAs, pickup.work.cleanup, pickup.work.constructionMaterial);
        return new Job(work, State.WAITING, 0, worker, null, now + LEASE_MS, CONSTRUCTION_PICKUP, pickup.chest);
    }

    static final String ROUTE_PREPARATION_FAILED = "Could not prepare route resupply: ";
    private static final String ROUTE_BENCH_RECEIVED = "Recipient picked up the emergency workbench";

    synchronized void recordRouteBenchReceipt(String projectId, String jobId, UUID token, long now) throws IOException {
        var project = projects.get(projectId);
        var receiver = find(project, jobId);
        if (!owns(receiver, token, now) || receiver.work.kind != Kind.TRANSFER || !depositTarget(receiver)) return;
        String id = "route-tool-" + token;
        var supply = find(project, id + "-retry");
        if (supply == null) supply = find(project, id);
        if (supply == null || supply.state != State.LEASED || supply.work.kind != Kind.RESUPPLY
                || ROUTE_BENCH_RECEIVED.equals(supply.evidence)) return;
        var delivery = JSON.fromJson(supply.work.payload, RouteToolDelivery.class);
        if (!"minecraft:crafting_table".equals(delivery.item) || !receiver.worker.equals(delivery.recipient)) return;
        replace(project, new Job(supply.work, supply.state, supply.attempts, supply.worker, supply.lease,
                supply.expiresAt, ROUTE_BENCH_RECEIVED, supply.chest));
    }

    synchronized void recordRouteStock(String projectId, String jobId, UUID token, long now, ChestReport stock) throws IOException {
        if (stock == null) return;
        var project = projects.get(projectId);
        var job = find(project, jobId);
        if (!owns(job, token, now) || job.work.kind != Kind.RESUPPLY
                || job.chest != null && job.chest.report.equals(stock)) return;
        var delivery = JSON.fromJson(job.work.payload, RouteToolDelivery.class);
        if (delivery.benchStock == null || !delivery.benchStock.location.equals(stock.location)
                || !job.work.locks.contains(stock.location)) return;
        replace(project, new Job(job.work, job.state, job.attempts, job.worker, job.lease, job.expiresAt,
                job.evidence, new ChestObservation(stock, now), job.resourceSite));
    }

    synchronized RouteToolDelivery routeToolDelivery(String projectId, String jobId, UUID token, long now) {
        var job = find(projects.get(projectId), jobId);
        return owns(job, token, now) && job.work.kind == Kind.RESUPPLY && !ROUTE_BENCH_RECEIVED.equals(job.evidence)
                ? JSON.fromJson(job.work.payload, RouteToolDelivery.class) : null;
    }

    synchronized Assignment offerRouteTool(String world, String worker, String projectId, long now,
                                           Map<String, Integer> tools, int routeCraftTarget, boolean canSupplyBench,
                                           java.util.function.Function<String, ResourceSite> position,
                                           java.util.function.ToIntBiFunction<String, String> held) throws IOException {
        return offerRouteTool(world, worker, projectId, now, tools, routeCraftTarget, canSupplyBench, position, held, false);
    }

    synchronized Assignment offerRouteTool(String world, String worker, String projectId, long now,
                                           Map<String, Integer> tools, int routeCraftTarget, boolean canSupplyBench,
                                           java.util.function.Function<String, ResourceSite> position,
                                           java.util.function.ToIntBiFunction<String, String> held, boolean fromStock) throws IOException {
        if (routeToolRequests.isEmpty()
                || projects.values().stream().flatMap(project -> project.jobs.stream())
                    .anyMatch(job -> job.state == State.LEASED && worker.equalsIgnoreCase(job.worker))) return null;
        var spare = tools == null ? null : PICKAXE_TIERS.stream().skip(1)
                .filter(tool -> tools.get(tool) != null && tools.get(tool) >= 64 && held.applyAsInt(worker, tool) >= 2)
                .findFirst().orElse(null);
        boolean canCraftPickaxe = routeCraftTarget >= 1 && routeCraftTarget <= 36;
        if (spare == null && !canCraftPickaxe && !canSupplyBench && !fromStock) return null;
        boolean reservedWood = pendingBuildMaterials(world, worker).stream().anyMatch(item ->
                item.endsWith("_planks") || ResourceCatalog.blocks("any_logs", "both").contains(item));
        RouteBenchStock benchStock = null;
        boolean checkedStock = false;
        for (var project : projects.values()) {
            if (!world.equals(project.world) || cancelled(project) || project.jobs.size() >= MAX_JOBS
                    || projectId != null && !project.id.equals(projectId)) continue;
            for (var deposit : project.jobs) {
                if (deposit.state != State.LEASED || deposit.expiresAt <= now || !routeToolRequests.containsKey(deposit.lease)
                        || worker.equalsIgnoreCase(deposit.worker)) continue;
                boolean requestBench = spare == null && !canCraftPickaxe && !reservedWood && routeToolRequests.get(deposit.lease);
                if (spare == null && !canCraftPickaxe && !requestBench) continue;
                String id = "route-tool-" + deposit.lease;
                var destination = position.apply(deposit.worker);
                if (destination == null) continue;
                var first = find(project, id);
                if (first != null) {
                    if (first.state != State.FAILED || first.evidence == null
                            || !first.evidence.startsWith(ROUTE_PREPARATION_FAILED)
                            || worker.equalsIgnoreCase(first.worker)) continue;
                    id += "-retry";
                    if (find(project, id) != null) continue;
                }
                if (requestBench && fromStock && !checkedStock) {
                    checkedStock = true;
                    benchStock = routeBenchStock(world, worker, position.apply(worker), held, now);
                }
                var pickup = requestBench ? benchStock : null;
                if (requestBench && pickup == null && !canSupplyBench) continue;
                String item = requestBench ? "minecraft:crafting_table" : spare == null ? "minecraft:stone_pickaxe" : spare;
                int craftTarget = requestBench || spare != null ? 0 : routeCraftTarget;
                int previousCount = held.applyAsInt(deposit.worker, item);
                if (previousCount < 0) continue;
                var delivery = new RouteToolDelivery(deposit.worker, destination, item, previousCount, craftTarget, pickup);
                var work = new Work(id, Kind.RESUPPLY, JSON.toJson(delivery), List.of(),
                        pickup == null ? Set.of() : pickup.locks);
                var loan = new Job(work, State.LEASED, 1, worker, UUID.randomUUID(),
                        now + LEASE_MS, null, null);
                var jobs = new ArrayList<>(project.jobs);
                jobs.add(loan);
                commit(project.withJobs(jobs));
                return assignment(project, loan);
            }
        }
        return null;
    }

    private RouteBenchStock routeBenchStock(String world, String worker, ResourceSite candidate,
                                           java.util.function.ToIntBiFunction<String, String> held, long now) throws IOException {
        if (candidate == null || held.applyAsInt(worker, "minecraft:crafting_table") != 0) return null;
        var observations = chestStock(world);
        var claimed = leasedKeys(world, now);
        RouteBenchStock best = null;
        double bestDistance = STOCK_RECOVERY_RADIUS;
        for (var source : projects.values()) {
            if (!world.equals(source.world) || cancelled(source) || source.repeat != null || source.supplyChest == null) continue;
            var observed = observations.get(source.supplyChest);
            if (observed == null) continue;
            var storage = source.jobs.stream().filter(job -> job.state == State.DONE && job.work.constructionMaterial != null
                    && job.work.kind == Kind.TRANSFER && job.work.locks.contains(source.supplyChest)).findFirst().orElse(null);
            if (storage == null || !Collections.disjoint(storage.work.locks, claimed)) continue;
            var location = JSON.fromJson(storage.work.payload, StationTarget.class).target;
            if (location == null || location.y() - candidate.y > CONSTRUCTION_PICKUP_RETRY_CLIMB) continue;
            double distance = distance(candidate, location);
            if (distance >= bestDistance) continue;
            for (var item : new TreeSet<>(observed.report.items.keySet())) {
                if (!item.startsWith("minecraft:") || !item.endsWith("_planks")) continue;
                int carried = held.applyAsInt(worker, item);
                if (carried < 0 || carried >= 4) continue;
                int reserve = constructionStockReserve(source, item);
                if (observed.report.items.get(item) - reserve < 4 - carried) continue;
                var payload = JSON.fromJson(storage.work.payload, com.google.gson.JsonObject.class);
                payload.addProperty("direction", "TAKE");
                payload.addProperty("item", item);
                payload.addProperty("stock", 4);
                best = new RouteBenchStock(payload.toString(), source.supplyChest, reserve, storage.work.locks);
                bestDistance = distance;
                break;
            }
        }
        return best;
    }

    private Set<String> leasedKeys(String world, long now) {
        var locks = new HashSet<String>();
        for (var project : projects.values()) {
            if (!world.equals(project.world)) continue;
            for (var job : project.jobs) if (job.state == State.LEASED && job.expiresAt > now) locks.addAll(job.work.locks);
        }
        return locks;
    }

    private static int constructionStockReserve(Project project, String item) {
        var pickups = new HashSet<String>();
        int otherStock = 0;
        int verifyStock = 0;
        for (var job : project.jobs) {
            if (job.work.kind != Kind.TRANSFER || !job.work.locks.contains(project.supplyChest)) continue;
            if (item.equals(job.work.constructionMaterial)) pickups.add(job.work.id);
            var transfer = transferTarget(job);
            if (transfer == null || !item.equals(transfer.item)) continue;
            if ("VERIFY_STOCK".equals(transfer.direction)) verifyStock = Math.max(verifyStock, transfer.stock);
            if (job.state != State.DONE && job.work.constructionMaterial == null) {
                if ("WITHDRAW".equals(transfer.direction)) return Integer.MAX_VALUE;
                if ("TAKE".equals(transfer.direction)) otherStock += transfer.stock;
            }
        }
        int remaining = (int)project.jobs.stream().filter(job -> job.state != State.DONE && job.work.kind == Kind.BUILD
                && job.work.sameWorkerAs != null && pickups.contains(job.work.sameWorkerAs)).count();
        return verifyStock + otherStock + remaining;
    }

    synchronized Assignment offerEquippedMiner(String world, String worker, String projectId,
                                                Map<String, Integer> toolUses, long now) throws IOException {
        return offerEquippedMiner(world, worker, projectId, toolUses, now, false);
    }

    private Assignment offerEquippedMiner(String world, String worker, String projectId,
                                          Map<String, Integer> toolUses, long now, boolean exhaustedOnly) throws IOException {
        if (toolUses == null || toolUses.isEmpty() || projects.values().stream().flatMap(project -> project.jobs.stream())
                .anyMatch(job -> job.state == State.LEASED && worker.equalsIgnoreCase(job.worker))) return null;
        for (var project : projects.values()) {
            if (project.supplyChest == null || !world.equals(project.world) || cancelled(project)
                    || projectId != null && !project.id.equals(projectId)) continue;
            for (var gather : project.jobs) {
                boolean exhausted = gather.state == State.FAILED && EXHAUSTED_TOOLS.equals(gather.evidence);
                boolean stalled = stalledPinnedGathering(gather);
                boolean reopening = exhausted || stalled;
                if ((!reopening && (exhaustedOnly || gather.state != State.WAITING)) || gather.work.kind != Kind.GATHER
                        || gather.work.sameWorkerAs == null || gather.work.needs.size() != 1
                        || gatherSource(gather.work.payload) == null || checkpointDepositActive(project, gather)) continue;
                var craft = find(project, gather.work.sameWorkerAs);
                if (craft.work.kind != Kind.CRAFT) continue;
                var recipe = JSON.fromJson(craft.work.payload, ToolRecipe.class);
                int tier = recipe == null || recipe.recipe == null ? -1 : PICKAXE_TIERS.indexOf(recipe.recipe);
                if (tier < 0) continue;
                String carriedTool = PICKAXE_TIERS.subList(tier, PICKAXE_TIERS.size()).stream()
                        .filter(tool -> toolUses.get(tool) != null && toolUses.get(tool) >= 32)
                        .findFirst().orElse(null);
                if (carriedTool == null) continue;
                var key = stalled ? "stalled-equipped-miner:" + gather.work.id : equippedRescueKey(gather);
                if (reopening && (craft.state != State.DONE || project.supplyRecoveries.containsKey(key))) continue;
                if (!reopening && craft.state == State.DONE) continue;
                if (stalled && (worker.equalsIgnoreCase(craft.worker) || worker.equalsIgnoreCase(gather.worker))) continue;
                if (reopening && gather.work.needs.stream().anyMatch(need -> find(project, need).state != State.DONE)) continue;
                var prep = reopening ? Set.<String>of() : stalledPrivatePreparation(project, gather, craft);
                if (!reopening && prep.isEmpty()) continue;
                var lease = new Job(gather.work, State.LEASED, gather.attempts + 1, worker, UUID.randomUUID(),
                        now + LEASE_MS, null, gather.chest, gatheringSite(project, gather));
                var jobs = project.jobs.stream().map(job -> {
                    if (job == gather) return lease;
                    if (!prep.contains(job.work.id)) return job;
                    return new Job(job.work, State.DONE, job.attempts, worker, null, 0,
                            "Tool preparation satisfied by carried " + carriedTool, job.chest, job.resourceSite);
                }).toList();
                if (reopening) {
                    var recoveries = new HashMap<>(project.supplyRecoveries);
                    recoveries.put(key, 1);
                    commit(project.withRecovery(jobs, recoveries));
                } else commit(project.withJobs(jobs));
                return assignment(project, lease);
            }
        }
        return null;
    }

    private static String equippedRescueKey(Job gather) {
        return "equipped-miner:" + gather.work.id;
    }

    private static Set<String> stalledPrivatePreparation(Project project, Job consumer, Job preparation) {
        var stages = new HashSet<String>();
        boolean failed = false;
        for (var job = preparation; job != null && job.state != State.DONE;
             job = job.work.sameWorkerAs == null ? null : find(project, job.work.sameWorkerAs)) {
            if (job.state == State.LEASED || (job.work.kind != Kind.GATHER && job.work.kind != Kind.CRAFT
                    && job.work.kind != Kind.SMELT)) return Set.of();
            stages.add(job.work.id);
            failed |= job.state == State.FAILED;
        }
        return failed && privatePreparationReady(project, consumer, stages) ? stages : Set.of();
    }

    private static boolean privatePreparationReady(Project project, Job consumer, Set<String> stages) {
        for (var job : project.jobs) {
            if (stages.contains(job.work.id)) {
                if (job.work.needs.stream().anyMatch(id -> !stages.contains(id) && find(project, id).state != State.DONE)) {
                    return false;
                }
            } else if (job != consumer && job.work.needs.stream().anyMatch(stages::contains)) return false;
        }
        return true;
    }

    synchronized List<CarriedCraft> craftableToolPreparation(String world, long now) {
        var result = new ArrayList<CarriedCraft>();
        for (var project : projects.values()) {
            if (!world.equals(project.world) || project.supplyChest == null || cancelled(project)) continue;
            var toolConsumers = new HashSet<String>();
            for (var job : project.jobs) {
                if (job.work.kind == Kind.GATHER && job.state == State.WAITING && job.work.sameWorkerAs != null) {
                    toolConsumers.add(job.work.sameWorkerAs);
                }
            }
            for (var craft : project.jobs) {
                if (craft.work.kind != Kind.CRAFT || craft.state != State.WAITING || craft.work.sameWorkerAs == null
                        || craft.work.needs.size() != 1 || !toolConsumers.contains(craft.work.id)
                        || project.supplyRecoveries.containsKey("carried-craft:" + craft.work.id)) continue;
                var recipe = JSON.fromJson(craft.work.payload, ToolRecipe.class);
                if (recipe == null || recipe.recipe == null || !PICKAXE_TIERS.contains(recipe.recipe)) continue;
                var path = privateActiveCraftPreparation(project, craft, now);
                if (path.isEmpty()) continue;
                var gathering = path.get(path.size() - 1);
                result.add(new CarriedCraft(project.id, craft.work, gathering.worker, gathering.lease));
            }
        }
        return result;
    }

    private List<Job> privateActiveCraftPreparation(Project project, Job craft, long now) {
        var path = new ArrayList<Job>();
        for (var stage = find(project, craft.work.sameWorkerAs); stage != null;
             stage = stage.work.sameWorkerAs == null ? null : find(project, stage.work.sameWorkerAs)) {
            if (stage.work.kind != Kind.GATHER && stage.work.kind != Kind.CRAFT) return List.of();
            path.add(stage);
            if (stage.state == State.WAITING) continue;
            if (stage.state != State.LEASED || stage.expiresAt <= now || stage.work.kind != Kind.GATHER
                    || handoffWorker(stage) != null || routeToolRequests.containsKey(stage.lease)) return List.of();
            var ids = path.stream().map(job -> job.work.id).collect(java.util.stream.Collectors.toSet());
            return privatePreparationReady(project, craft, ids) ? path : List.of();
        }
        return List.of();
    }

    synchronized boolean useCarriedCraftingIngredients(String world, CarriedCraft request, Set<String> inputs,
                                                         long now) throws IOException {
        if (inputs.isEmpty() || !craftableToolPreparation(world, now).contains(request)) return false;
        var project = projects.get(request.project);
        var craft = find(project, request.work.id);
        if (!Collections.disjoint(inputs, pendingBuildMaterials(world, request.worker))
                || inputs.stream().anyMatch(item -> hasPendingDepositOf(world, request.worker, item))) return false;
        var path = privateActiveCraftPreparation(project, craft, now);
        var jobs = project.jobs.stream().map(job -> {
            if (!path.contains(job)) return job;
            return new Job(job.work, State.DONE, job.attempts, request.worker, null, 0,
                    "Preparation satisfied by observed crafting ingredients", job.chest, job.resourceSite);
        }).toList();
        var counts = new HashMap<>(project.supplyRecoveries);
        counts.put("carried-craft:" + craft.work.id, 1);
        commit(project.withRecovery(jobs, counts));
        return true;
    }

    synchronized List<CarriedSmelt> resumableSmelting(String world) {
        var result = new ArrayList<CarriedSmelt>();
        for (var project : projects.values()) {
            if (!world.equals(project.world) || project.supplyChest == null || cancelled(project)) continue;
            for (var job : project.jobs) {
                if (job.work.kind != Kind.SMELT || job.state != State.WAITING || job.work.sameWorkerAs == null
                        || job.work.needs.size() != 1 || job.evidence == null || !job.evidence.startsWith("Missing furnace fuel ")
                        || !job.evidence.endsWith("; reacquiring materials")
                        || project.supplyRecoveries.containsKey("carried-smelt:" + job.work.id)) continue;
                if (!stalledPrivatePreparation(project, job, find(project, job.work.sameWorkerAs)).isEmpty()) {
                    result.add(new CarriedSmelt(project.id, job.work));
                }
            }
        }
        return result;
    }

    synchronized boolean resumeCarriedSmelting(String world, CarriedSmelt request, String worker, String fuel,
                                                long now) throws IOException {
        if (!resumableSmelting(world).contains(request) || fuel == null || !fuel.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return false;
        var project = projects.get(request.project);
        if (!availableProjectWorker(project, worker, now)) return false;
        var smelt = find(project, request.work.id);
        var spec = JSON.fromJson(smelt.work.payload, SmeltFuel.class);
        var reserved = pendingBuildMaterials(world, worker);
        if (reserved.contains(spec.input) || reserved.contains(fuel) || hasPendingDepositOf(world, worker, spec.input)
                || hasPendingDepositOf(world, worker, fuel)) return false;
        var stages = stalledPrivatePreparation(project, smelt, find(project, smelt.work.sameWorkerAs));
        var payload = JSON.fromJson(smelt.work.payload, com.google.gson.JsonObject.class);
        if (!payload.has("plannedFuel")) payload.addProperty("plannedFuel", spec.fuel);
        payload.addProperty("fuel", fuel);
        var work = new Work(smelt.work.id, smelt.work.kind, JSON.toJson(payload), smelt.work.needs,
                smelt.work.locks, smelt.work.sameWorkerAs);
        var jobs = project.jobs.stream().map(job -> {
            if (job == smelt) return new Job(work, State.WAITING, job.attempts, null, null, 0,
                    "Resuming smelting with observed carried supplies", job.chest, job.resourceSite);
            if (!stages.contains(job.work.id)) return job;
            return new Job(job.work, State.DONE, job.attempts, worker, null, 0,
                    "Preparation satisfied by observed carried smelting supplies", job.chest, job.resourceSite);
        }).toList();
        var counts = new HashMap<>(project.supplyRecoveries);
        counts.put("carried-smelt:" + smelt.work.id, 1);
        commit(project.withRecovery(jobs, counts));
        return true;
    }

    private boolean availableProjectWorker(Project project, String worker, long now) {
        long lastSeen = Math.max(workerSeen.getOrDefault(workerKey(project.world, worker, project.id), 0L),
                workerSeen.getOrDefault(workerKey(project.world, worker, null), 0L));
        return lastSeen > 0 && now - lastSeen <= LEASE_MS && projects.values().stream().flatMap(p -> p.jobs.stream())
                .noneMatch(job -> job.state == State.LEASED && worker.equalsIgnoreCase(job.worker));
    }

    synchronized Assignment offerGatheringHandoff(String world, String worker, String projectId, long now,
                                                  java.util.function.ToIntBiFunction<String, String> heldCount) throws IOException {
        if (projects.values().stream().flatMap(project -> project.jobs.stream()).anyMatch(job ->
                job.state == State.LEASED && (worker.equalsIgnoreCase(job.worker)
                        || worker.equalsIgnoreCase(handoffWorker(job))))) return null;
        for (Project project : projects.values()) {
            if (!world.equals(project.world) || cancelled(project)
                    || projectId != null && !projectId.equals(project.id)) continue;
            for (Job job : project.jobs) {
                if (job.state != State.LEASED || job.expiresAt <= now || job.work.kind != Kind.GATHER
                        || job.work.sameWorkerAs != null || handoffWorker(job) != null) continue;
                var source = gatherSource(job.work.payload);
                if (source == null || pendingBuildMaterials(world, worker).contains(source.item)
                        || hasPendingDepositOf(world, worker, source.item)) continue;
                int candidate = heldCount.applyAsInt(worker, source.item);
                if (candidate < 16) continue;
                int current = heldCount.applyAsInt(job.worker, source.item);
                if (current < 0) continue;
                var deposit = contributionDeposit(project, job);
                if (deposit == null) continue;
                if (candidate < current + 16 || candidate <= 2 * current) {
                    var contribution = offerContribution(project, job, deposit, source.item, worker, current + candidate,
                            false, now, null, false, 0);
                    if (contribution != null) return contribution;
                    continue;
                }
                // Keep the current lease valid through its signed deadline. The new worker must wait.
                replace(project, new Job(job.work, State.LEASED, job.attempts, job.worker, job.lease, job.expiresAt,
                        GATHER_HANDOFF + worker, job.chest, job.resourceSite));
                return null;
            }
        }
        return null;
    }

    synchronized Assignment offerInterruptedDeposit(String world, String worker, String projectId, long now,
                                                     java.util.function.ToIntBiFunction<String, String> heldCount) throws IOException {
        return offerInterruptedDeposit(world, worker, projectId, now, null, heldCount);
    }

    synchronized Assignment offerInterruptedDeposit(String world, String worker, String projectId, long now,
                                                     ResourceSite position,
                                                     java.util.function.ToIntBiFunction<String, String> heldCount) throws IOException {
        return offerInterruptedDeposit(world, worker, projectId, now, position, heldCount, 0);
    }

    synchronized Assignment offerInterruptedDeposit(String world, String worker, String projectId, long now,
                                                     ResourceSite position,
                                                     java.util.function.ToIntBiFunction<String, String> heldCount,
                                                     int returnRevision) throws IOException {
        return offerInterruptedDeposit(world, worker, projectId, now, position, heldCount, CargoRecovery.ANY, returnRevision);
    }

    synchronized Assignment offerCheckpointDeposit(String world, String worker, String projectId, long now,
                                                   ResourceSite position,
                                                   java.util.function.ToIntBiFunction<String, String> heldCount) throws IOException {
        return offerCheckpointDeposit(world, worker, projectId, now, position, heldCount, 0);
    }

    synchronized Assignment offerCheckpointDeposit(String world, String worker, String projectId, long now,
                                                     ResourceSite position,
                                                     java.util.function.ToIntBiFunction<String, String> heldCount,
                                                     int returnRevision) throws IOException {
        return offerInterruptedDeposit(world, worker, projectId, now, position, heldCount, CargoRecovery.CHECKPOINT, returnRevision);
    }

    private Assignment offerInterruptedDeposit(String world, String worker, String projectId, long now,
                                               ResourceSite position,
                                               java.util.function.ToIntBiFunction<String, String> heldCount,
                                               CargoRecovery mode, int returnRevision) throws IOException {
        if (projects.values().stream().flatMap(project -> project.jobs.stream())
                .anyMatch(job -> job.state == State.LEASED && worker.equalsIgnoreCase(job.worker))) return null;
        var reserved = pendingBuildMaterials(world, worker);
        for (var project : projects.values()) {
            if (!world.equals(project.world) || project.supplyChest == null || project.repeat != null
                    || projectId != null && !projectId.equals(project.id)) continue;
            for (var gather : project.jobs) {
                if (gather.work.kind != Kind.GATHER || gather.state == State.DONE) continue;
                var deposit = contributionDeposit(project, gather);
                if (deposit == null) continue;
                boolean checkpoint = CARGO_CHECKPOINT.equals(gather.evidence) && cargoCheckpoint(project, gather) > 0;
                boolean missingTools = RECOVERING_TOOLS.equals(gather.evidence) || EXHAUSTED_TOOLS.equals(gather.evidence);
                if (mode == CargoRecovery.CHECKPOINT && !checkpoint || mode == CargoRecovery.TOOL_FAILURE && !missingTools) continue;
                boolean interrupted = gather.state != State.LEASED && (missingTools
                        || GATHER_STALLED.equals(gather.evidence) || LEASE_EXPIRED.equals(gather.evidence) || checkpoint);
                boolean returningCargo = deposit.chest != null && CONTRIBUTION_SETTLED.equals(deposit.evidence);
                boolean coveringActiveGather = mode == CargoRecovery.CARRIED_GOAL;
                if (coveringActiveGather) {
                    if (gather.state != State.LEASED || now >= gather.expiresAt
                            || GATHER_STOCK_CONFIRMED.equals(gather.evidence) || handoffWorker(gather) != null) continue;
                } else if (!interrupted && !returningCargo) continue;
                var source = gatherSource(gather.work.payload);
                if (source == null || reserved.contains(source.item) || hasPendingDepositOf(world, worker, source.item)) continue;
                int held = heldCount.applyAsInt(worker, source.item);
                var cargo = contributionCargo(deposit, worker);
                if (held == 0 && CONTRIBUTION_SETTLED.equals(deposit.evidence) && project.supplyRecoveries.containsKey(cargo)) {
                    commit(project.withContribution(project.jobs, cargo, 0, null, 0, 0));
                    return null;
                }
                if (held <= 0) continue;
                if (coveringActiveGather) {
                    var target = transferTarget(deposit);
                    if (target == null) continue;
                    int missing = target.stock - (deposit.chest == null ? 0 : stockCount(deposit.chest.report, source.item));
                    int originalHeld = heldCount.applyAsInt(gather.worker, source.item);
                    if (missing < 1 || held < missing || originalHeld < 0 || originalHeld >= missing) continue;
                }
                var assignment = offerContribution(project, gather, deposit, source.item, worker,
                        held, true, now, position, coveringActiveGather, returnRevision);
                if (assignment != null) return assignment;
            }
        }
        return null;
    }

    private static Job contributionDeposit(Project project, Job gather) {
        return project.jobs.stream().filter(job -> job.state == State.WAITING && job.work.kind == Kind.TRANSFER
                && gather.work.id.equals(job.work.sameWorkerAs) && depositTarget(job)).findFirst().orElse(null);
    }

    private static boolean checkpointDepositActive(Project project, Job gather) {
        return project.jobs.stream().anyMatch(job -> job.state == State.LEASED && CONTRIBUTION_ACTIVE.equals(job.evidence)
                && gather.work.id.equals(job.work.sameWorkerAs));
    }

    private static int cargoCheckpoint(Project project, Job gather) {
        if (gather.work.kind != Kind.GATHER || project.repeat != null || project.supplyChest == null) return 0;
        var source = gatherSource(gather.work.payload);
        if (source == null) return 0;
        Integer depth = SubsurfaceAccess.prospectingAltitude(source.resource);
        if (depth == null || depth > 0) return 0;
        var deposit = contributionDeposit(project, gather);
        if (deposit == null || !deposit.work.locks.contains(project.supplyChest)
                || project.jobs.stream().anyMatch(job -> job != deposit && job.work.needs.contains(gather.work.id))) return 0;
        var count = JSON.fromJson(gather.work.payload, com.google.gson.JsonObject.class).get("count");
        return count != null && count.getAsInt() > MINING_CARGO_LIMIT ? MINING_CARGO_LIMIT : 0;
    }

    synchronized Assignment offerGatheringStock(String world, String worker, String projectId, long now,
                                                ResourceSite position,
                                                java.util.function.ToIntBiFunction<String, String> heldCount) throws IOException {
        if (position == null || projects.values().stream().flatMap(project -> project.jobs.stream())
                .anyMatch(job -> job.state == State.LEASED && worker.equalsIgnoreCase(job.worker))) return null;
        var carried = pendingBuildMaterials(world, worker);
        for (var project : projects.values()) {
            if (!world.equals(project.world) || project.supplyChest == null || project.repeat != null
                    || projectId != null && !projectId.equals(project.id)) continue;
            for (var gather : project.jobs) {
                if (gather.state != State.FAILED || gather.work.kind != Kind.GATHER || gather.work.sameWorkerAs != null
                        || !GATHER_STALLED.equals(gather.evidence)) continue;
                String key = "gather-stock:" + gather.work.id;
                if (project.supplyRecoveries.containsKey(key)) continue;
                var source = gatherSource(gather.work.payload);
                if (source == null || !source.item.contains(":") || carried.contains(source.item)
                        || hasPendingDepositOf(world, worker, source.item)) continue;
                var count = JSON.fromJson(gather.work.payload, com.google.gson.JsonObject.class).get("count");
                if (count == null) continue;
                int required = count.getAsInt();
                int held = heldCount.applyAsInt(worker, source.item);
                if (held <= 0 || held < required - held || held >= required) continue;
                var pickup = gatheringStockPickup(project, gather, source.item, required, required - held, position);
                if (pickup == null) continue;
                var lease = new Job(pickup, State.LEASED, gather.attempts, worker, UUID.randomUUID(), now + LEASE_MS,
                        "Completing gathering from carried cargo and project stock", null, null, gather.work);
                var recoveries = new HashMap<>(project.supplyRecoveries);
                recoveries.put(key, 1);
                var jobs = project.jobs.stream().map(job -> job == gather ? lease : job).toList();
                commit(project.withRecovery(jobs, recoveries));
                return assignment(project, lease);
            }
        }
        return null;
    }

    private Work gatheringStockPickup(Project project, Job gather, String item, int required, int missing,
                                      ResourceSite position) {
        if (gather.work.needs.stream().anyMatch(id -> find(project, id).state != State.DONE)) return null;
        var observed = new HashMap<String, ChestObservation>();
        collectChestStock(project.world, project, observed);
        var stock = observed.get(project.supplyChest);
        if (stock == null || stockCount(stock.report, item) < missing) return null;
        Job station = null;
        for (var job : project.jobs) {
            if (job.work.kind != Kind.TRANSFER || !job.work.locks.contains(project.supplyChest)) continue;
            var transfer = transferTarget(job);
            if (transfer == null) continue;
            if ("VERIFY_STOCK".equals(transfer.direction)
                    && (item.equals(transfer.item) || "any_logs".equals(transfer.item)
                    && ResourceCatalog.blocks("any_logs", "both").contains(item))
                    && stockCount(stock.report, transfer.item) - missing < transfer.stock) return null;
            if (job.state != State.DONE && ("TAKE".equals(transfer.direction) || "WITHDRAW".equals(transfer.direction))
                    && (item.equals(transfer.item) || transfer.item == null || !transfer.item.contains(":"))) return null;
            if (job.state == State.DONE && job.chest != null) station = job;
        }
        if (station == null) return null;
        var payload = JSON.fromJson(station.work.payload, com.google.gson.JsonObject.class);
        var target = JSON.fromJson(payload, StationTarget.class).target;
        if (target == null || distance(position, target) > STOCK_RECOVERY_RADIUS
                || target.y() - position.y > CONSTRUCTION_PICKUP_RETRY_CLIMB) return null;
        var locks = new HashSet<>(gather.work.locks);
        locks.addAll(station.work.locks);
        for (var other : projects.values()) {
            if (!project.world.equals(other.world)) continue;
            if (other != project && project.supplyChest.equals(reservedStorage(other))
                    || other.jobs.stream().anyMatch(job -> job.state == State.LEASED
                    && !Collections.disjoint(locks, job.work.locks))) return null;
        }
        payload.addProperty("direction", "TAKE");
        payload.addProperty("item", item);
        payload.addProperty("stock", required);
        return new Work(gather.work.id, Kind.TRANSFER, JSON.toJson(payload), gather.work.needs, locks);
    }

    private Assignment offerContribution(Project project, Job gather, Job deposit, String item, String worker,
                                         int carriedTotal, boolean interrupted, long now, ResourceSite position,
                                         boolean coveringActiveGather, int returnRevision) throws IOException {
        // Older journals used the settled marker even when the worker never reached the chest.
        boolean legacyRetry = deposit.attempts == 0 && CONTRIBUTION_SETTLED.equals(deposit.evidence);
        boolean retry = legacyRetry || (deposit.attempts == 1 && CONTRIBUTION_RETRY.equals(deposit.evidence));
        boolean additionalCargo = interrupted && deposit.chest != null && CONTRIBUTION_SETTLED.equals(deposit.evidence);
        var target = transferTarget(deposit);
        var payload = JSON.fromJson(gather.work.payload, com.google.gson.JsonObject.class);
        if (target == null || !item.equals(target.item) || payload == null || !payload.has("count")) return null;
        String cargo = contributionCargo(deposit, worker);
        int earlierCount = project.supplyRecoveries.getOrDefault(cargo, 0);
        var origin = project.contributionOrigins.get(cargo);
        var station = position == null ? null : JSON.fromJson(deposit.work.payload, StationTarget.class).target;
        // A newer uphill return method gets one chance with retained cargo, even at the same origin.
        boolean upgradedReturn = interrupted && CONTRIBUTION_SETTLED.equals(deposit.evidence)
                && earlierCount > 0 && origin != null && station != null && station.y() - position.y >= 5
                && returnRevision > project.supplyRecoveries.getOrDefault(cargo + ":route", 0);
        if (project.repeat != null || project.supplyChest == null
                || !additionalCargo && !upgradedReturn && (deposit.chest != null
                    || !retry && (deposit.attempts != 0 || CONTRIBUTION_SETTLED.equals(deposit.evidence)))
                || now < deposit.expiresAt || !deposit.work.locks.contains(project.supplyChest)
                || project.jobs.stream().anyMatch(job -> job != deposit && job.work.needs.contains(gather.work.id))) return null;
        int stock = deposit.chest == null ? 0 : stockCount(deposit.chest.report, item);
        if (additionalCargo) {
            if (stock >= target.stock) return null;
            // Older journals recorded the count but lacked a location. Let a live position anchor one new attempt.
            boolean missingOldOrigin = origin == null && position != null;
            // A smaller stack can cover the remainder after the chest advanced since this carrier's attempt.
            Integer earlierStock = project.supplyRecoveries.get(cargo + ":stock");
            boolean newCargoAfterDeposit = coveringActiveGather && carriedTotal < earlierCount
                    && earlierStock != null && stock > earlierStock;
            if (carriedTotal <= earlierCount && !missingOldOrigin && !newCargoAfterDeposit && !upgradedReturn
                    && !differentContributionApproach(deposit, origin, position)) return null;
        }
        int goal = payload.get("count").getAsInt();
        if (!interrupted && carriedTotal < goal || ownChestContributionBlocked(project, gather, deposit)) return null;
        var assigned = new Job(deposit.work, State.LEASED, retry || additionalCargo || upgradedReturn ? 1 : 0, worker,
                UUID.randomUUID(), now + LEASE_MS, CONTRIBUTION_ACTIVE, deposit.chest);
        if (interrupted) {
            var jobs = project.jobs.stream().map(job -> job == deposit ? assigned : job).toList();
            commit(project.withContribution(jobs, cargo, carriedTotal, position, stock, returnRevision));
        } else replace(project, assigned);
        return new Assignment(project.id, project.world, assigned);
    }

    private static boolean differentContributionApproach(Job deposit, ResourceSite origin, ResourceSite position) {
        if (origin == null || position == null || gatheringDistance(origin, position) < 12) return false;
        var target = JSON.fromJson(deposit.work.payload, StationTarget.class).target;
        return target != null && (position.y - origin.y >= 8 && position.y <= target.y()
                || distance(origin, target) - distance(position, target) >= 16);
    }

    private static String contributionCargo(Job deposit, String worker) {
        return "contribution:" + deposit.work.id + ":" + worker.toLowerCase(Locale.ROOT);
    }

    private boolean ownChestContributionBlocked(Project project, Job gather, Job deposit) {
        if (deposit.work.needs.stream().anyMatch(dep -> !dep.equals(gather.work.id)
                && find(project, dep).state != State.DONE)) return true;
        if (projects.values().stream().anyMatch(other -> other != project && other.world.equals(project.world)
                && project.supplyChest.equals(reservedStorage(other)))) return true;
        return projects.values().stream().filter(other -> other.world.equals(project.world))
                .flatMap(other -> other.jobs.stream()).anyMatch(job -> job.state == State.LEASED
                        && !Collections.disjoint(deposit.work.locks, job.work.locks));
    }

    private static boolean depositTarget(Job job) {
        var transfer = JSON.fromJson(job.work.payload, PickupTarget.class);
        return transfer != null && "DEPOSIT".equals(transfer.direction);
    }

    private boolean hasPendingDepositOf(String world, String worker, String item) {
        for (Project project : projects.values()) {
            if (!world.equals(project.world) || cancelled(project)) continue;
            for (Job job : project.jobs) {
                if (job.state != State.WAITING || job.work.kind != Kind.TRANSFER || job.work.sameWorkerAs == null) continue;
                var cargo = find(project, job.work.sameWorkerAs);
                if (cargo == null || cargo.state != State.DONE || !worker.equalsIgnoreCase(cargo.worker)) continue;
                var transfer = JSON.fromJson(job.work.payload, PickupTarget.class);
                if (transfer != null && "DEPOSIT".equals(transfer.direction) && item.equals(transfer.item)) return true;
            }
        }
        return false;
    }

    private static String handoffWorker(Job job) {
        return job.evidence != null && job.evidence.startsWith(GATHER_HANDOFF)
                ? job.evidence.substring(GATHER_HANDOFF.length()) : null;
    }

    private Assignment assign(Project project, Job job, String worker, long now, ResourceSite position) throws IOException {
        var site = job.work.kind == Kind.GATHER && GATHER_STALLED.equals(job.evidence)
                ? nearestRetrySite(project, job, position) : gatheringSite(project, job);
        var assigned = new Job(job.work, State.LEASED, job.attempts + 1, worker,
                UUID.randomUUID(), now + LEASE_MS, null, job.chest, site);
        replace(project, assigned);
        return assignment(project, assigned);
    }

    private static Assignment assignment(Project project, Job job) {
        return new Assignment(project.id, project.world, job, fuelUse(project, job), accessRoute(project, job),
                cargoCheckpoint(project, job));
    }

    private static List<ResourceSite> accessRoute(Project project, Job job) {
        if (job.work.kind != Kind.BUILD) return List.of();
        String repair = job.work.id;
        boolean replacement = project.supplyRecoveries.containsKey("access:" + repair + "-v2");
        if (replacement) repair += "-v2";
        else if (!project.supplyRecoveries.containsKey("access:" + repair)
                || project.supplyRecoveries.containsKey("pose:" + repair)) return List.of();
        String stage = "access-repair-" + repair + "-";
        var walk = new LinkedHashMap<ResourceSite, ResourceSite>();
        for (var step : project.jobs) {
            if (step.work.id.startsWith("remove-" + stage) && step.state != State.WAITING) return List.of();
            if (!step.work.id.startsWith("place-" + stage)) continue;
            if (step.state != State.DONE) return List.of();
            var location = JSON.fromJson(step.work.payload, StationTarget.class).target;
            if (location == null) return List.of();
            var column = new ResourceSite(location.x(), 0, location.z());
            var feet = new ResourceSite(location.x(), location.y() + 1, location.z());
            walk.merge(column, feet, (lower, upper) -> upper.y > lower.y ? upper : lower);
        }
        if (walk.isEmpty() || walk.size() > 4) return List.of();
        var route = List.copyOf(walk.values());
        var stand = JSON.fromJson(job.work.payload, StationTarget.class).stand;
        var last = route.get(route.size() - 1);
        if (stand == null || stand.x() != last.x || stand.y() != last.y || stand.z() != last.z) return List.of();
        for (int i = 1; i < route.size(); i++) {
            var a = route.get(i - 1);
            var b = route.get(i);
            if (b.y - a.y != 1 || Math.abs(a.x - b.x) + Math.abs(a.z - b.z) != 1) return List.of();
        }
        return route;
    }

    private static FuelUse fuelUse(Project project, Job gather) {
        if (gather.work.kind != Kind.GATHER || project.repeat != null || project.supplyChest == null) return null;
        var source = gatherSource(gather.work.payload);
        if (source == null) return null;
        var consumers = project.jobs.stream().filter(job -> job.work.needs.contains(gather.work.id)).toList();
        if (consumers.size() != 1) return null;
        var smelt = consumers.get(0);
        if (smelt.state != State.WAITING || smelt.work.kind != Kind.SMELT || !gather.work.id.equals(smelt.work.sameWorkerAs)) return null;
        var spec = JSON.fromJson(smelt.work.payload, SmeltFuel.class);
        if (spec == null || spec.recipe == null || spec.input == null || spec.count <= 0) return null;
        String planned = spec.plannedFuel == null ? spec.fuel : spec.plannedFuel;
        return source.item.equals(planned) ? new FuelUse(smelt.work.id, spec.recipe, spec.input, planned, spec.count) : null;
    }

    private static ResourceSite gatheringSite(Project project, Job job) {
        // Recovery callers without a current worker position must start without a site hint after a stall.
        if (job.work.kind == Kind.GATHER && GATHER_STALLED.equals(job.evidence)) return null;
        if (job.resourceSite != null || job.work.kind != Kind.GATHER) return job.resourceSite;
        var source = gatherSource(job.work.payload);
        if (source == null) return null;
        for (int index = project.jobs.size() - 1; index >= 0; index--) {
            var previous = project.jobs.get(index);
            if (previous.state == State.DONE && previous.work.kind == Kind.GATHER && previous.resourceSite != null
                    && source.equals(gatherSource(previous.work.payload))) return previous.resourceSite;
        }
        return null;
    }

    private static ResourceSite nearestRetrySite(Project project, Job job, ResourceSite position) {
        if (position == null) return null;
        var source = gatherSource(job.work.payload);
        if (source == null) return null;
        ResourceSite best = null;
        double nearest = GATHERING_RETRY_RADIUS;
        for (var previous : project.jobs) {
            var site = previous.resourceSite;
            if (previous.state != State.DONE || previous.work.kind != Kind.GATHER || site == null
                    || site.y - position.y > GATHERING_RETRY_CLIMB
                    || job.resourceSite != null && gatheringDistance(site, job.resourceSite) <= FAILED_SITE_RADIUS) continue;
            double distance = gatheringDistance(site, position);
            if (distance < nearest && source.equals(gatherSource(previous.work.payload))) {
                best = site;
                nearest = distance;
            }
        }
        return best;
    }

    private static double gatheringDistance(ResourceSite a, ResourceSite b) {
        return Math.hypot(Math.hypot((double) a.x - b.x, (double) a.y - b.y), (double) a.z - b.z);
    }

    private static GatherSource gatherSource(String payload) {
        try {
            var source = JSON.fromJson(payload, GatherSource.class);
            return source != null && source.resource != null && !source.resource.equals("dropped")
                    && source.variant != null && source.item != null ? source : null;
        } catch (JsonParseException invalid) {
            return null;
        }
    }

    private static boolean settled(String id, Set<String> done, Set<String> blocked) {
        return done.contains(id) || blocked.contains(id);
    }

    private Set<String> pendingBuildMaterials(String world, String worker) {
        var materials = new HashSet<String>();
        for (var project : projects.values()) {
            if (project.supplyChest == null || !project.world.equals(world)) continue;
            materials.addAll(pendingBuildMaterials(project, worker, null));
        }
        return materials;
    }

    private boolean recoveryPickupPrecedesReservations(Project recoveryProject, Job pickup, String worker) {
        String id = pickup.work.id;
        if (!id.startsWith("recovery-") || !id.endsWith("-cargo")) return false;
        String placement = id.substring("recovery-".length(), id.length() - "-cargo".length());
        if (!recoveryProject.supplyRecoveries.containsKey("pose-handoff:" + placement)) return false;
        String material = pickup.work.constructionMaterial;
        for (var project : projects.values()) {
            if (project.supplyChest == null || !project.world.equals(recoveryProject.world)) continue;
            String predecessor = project == recoveryProject ? id : null;
            if (pendingBuildMaterials(project, worker, null, predecessor).contains(material)) return false;
        }
        return true;
    }

    private static Set<String> pendingBuildMaterials(Project project, String worker, String incomingPickup) {
        return pendingBuildMaterials(project, worker, incomingPickup, null);
    }

    private static Set<String> pendingBuildMaterials(Project project, String worker, String incomingPickup, String predecessor) {
        var pickups = new HashMap<String, String>();
        for (var job : project.jobs) {
            if (job.state == State.DONE && job.work.constructionMaterial != null
                    && worker.equalsIgnoreCase(job.worker) && !job.work.id.equals(incomingPickup)) {
                pickups.put(job.work.id, job.work.constructionMaterial);
            }
        }
        if (pickups.isEmpty()) return Set.of();
        var blocked = blockedJobs(project);
        var downstream = new HashSet<String>();
        if (predecessor != null) downstream.add(predecessor);
        var materials = new HashSet<String>();
        for (var job : project.jobs) {
            // Cargo for later placements cannot be used before this recovery pickup and its placement.
            if (predecessor != null && job.work.needs.stream().anyMatch(downstream::contains)) {
                downstream.add(job.work.id);
                continue;
            }
            if (job.work.kind != Kind.BUILD || job.state != State.WAITING || blocked.contains(job.work.id)) continue;
            var material = pickups.get(job.work.sameWorkerAs);
            if (material != null) materials.add(material);
        }
        return materials;
    }

    synchronized Job renew(String projectId, String jobId, UUID token, long now) throws IOException {
        return renew(projectId, jobId, token, null, now);
    }

    synchronized Job renew(String projectId, String jobId, UUID token, ResourceSite site, long now) throws IOException {
        return renew(projectId, jobId, token, site, now, false);
    }

    synchronized Job renew(String projectId, String jobId, UUID token, ResourceSite site, long now,
                           boolean needsRouteTool) throws IOException {
        return renew(projectId, jobId, token, site, now, needsRouteTool, false);
    }

    synchronized Job renew(String projectId, String jobId, UUID token, ResourceSite site, long now,
                           boolean needsRouteTool, boolean needsRouteBench) throws IOException {
        Project project = projects.get(projectId);
        Job job = find(project, jobId);
        if (!owns(job, token, now) || cancelled(project) || handoffWorker(job) != null
                || GATHER_STOCK_CONFIRMED.equals(job.evidence)) return null;
        if (needsRouteTool && job.work.kind == Kind.TRANSFER && depositTarget(job)) routeToolRequests.put(token, needsRouteBench);
        else routeToolRequests.remove(token);
        Job renewed = new Job(job.work, State.LEASED, job.attempts, job.worker,
                job.lease, now + LEASE_MS, job.evidence, job.chest, gatheringSite(job, site), job.gatheringAfterPickup);
        replace(project, renewed);
        restoredLeases.remove(token);
        markAssignmentSeen(project, job.worker, now);
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
                    && (job.work.kind == Kind.RESUPPLY && chest == null
                        || Objects.equals(job.chest == null ? null : job.chest.report, chest))
                    && (site == null || site.equals(job.resourceSite));
        }
        if (!owns(job, token, now) || !success && handoffWorker(job) != null
                || GATHER_STOCK_CONFIRMED.equals(job.evidence)) return false;
        routeToolRequests.remove(token);
        var fuelPlan = success ? fuelUse(project, job) : null;
        boolean substituted = success && evidence.startsWith(ALTERNATIVE_FUEL);
        if (substituted || fuelPlan != null && !fuelPlan.expectedFuel.equals(currentFuel(project, fuelPlan))) {
            String fuel = substituted ? evidence.substring(ALTERNATIVE_FUEL.length()) : fuelPlan.expectedFuel;
            if (fuelPlan == null || !fuel.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Alternative fuel needs a matching smelting job");
            }
            var smelt = find(project, fuelPlan.job);
            var work = smelt.work;
            var payload = JSON.fromJson(work.payload, com.google.gson.JsonObject.class);
            payload.addProperty("plannedFuel", fuelPlan.expectedFuel);
            payload.addProperty("fuel", fuel);
            var modified = new Work(work.id, work.kind, JSON.toJson(payload), work.needs, work.locks, work.sameWorkerAs);
            var jobs = new ArrayList<>(project.jobs);
            jobs.set(jobs.indexOf(smelt), new Job(modified, smelt.state, smelt.attempts, smelt.worker, smelt.lease,
                    smelt.expiresAt, smelt.evidence, smelt.chest, smelt.resourceSite));
            jobs.set(jobs.indexOf(job), new Job(job.work, State.DONE, job.attempts, job.worker, job.lease, 0,
                    evidence, null, gatheringSite(job, site)));
            commit(project.withJobs(jobs));
            markAssignmentSeen(project, job.worker, now);
            restoredLeases.remove(token);
            return true;
        }
        if (chest != null && (job.work.kind != Kind.TRANSFER || !job.work.locks.contains(chest.location)
                || project.repeat != null && !project.repeat.chest.equals(chest.location))) {
            throw new IllegalArgumentException("Chest report does not match the leased transfer");
        }
        if (success && job.work.kind == Kind.TRANSFER && chest == null) {
            throw new IllegalArgumentException("Successful transfer requires a chest observation");
        }
        var observation = chest == null ? job.chest : new ChestObservation(chest, now);
        if (job.gatheringAfterPickup != null) {
            replace(project, new Job(job.gatheringAfterPickup, success ? State.DONE : State.FAILED, job.attempts,
                    success ? job.worker : null, success ? job.lease : null, 0,
                    success ? evidence : GATHER_STALLED, observation));
            markAssignmentSeen(project, job.worker, now);
            restoredLeases.remove(token);
            return true;
        }
        var location = gatheringSite(job, site);
        if (CONTRIBUTION_ACTIVE.equals(job.evidence)) {
            settleContribution(project, job, evidence, observation, now);
            markAssignmentSeen(project, job.worker, now);
            restoredLeases.remove(token);
            return true;
        }
        if (!success && GATHER_TOOLS_NEEDED.equals(evidence) && recoverGatheringTools(project, job, location, now)) {
            markAssignmentSeen(project, job.worker, now);
            restoredLeases.remove(token);
            return true;
        }
        if (!success && recoverMissingProduction(project, job, evidence)) {
            markAssignmentSeen(project, job.worker, now);
            restoredLeases.remove(token);
            return true;
        }
        if (pickupShortfall) {
            if (success || chest == null || job.work.kind != Kind.TRANSFER) {
                throw new IllegalArgumentException("A stock shortfall requires a failed transfer and chest observation");
            }
            if (recoverShortTransfer(project, job, evidence, observation)) {
                markAssignmentSeen(project, job.worker, now);
                restoredLeases.remove(token);
                return true;
            }
        }
        Job result;
        if (success) {
            result = new Job(job.work, State.DONE, job.attempts, job.worker, job.lease, 0, evidence, observation, location);
        } else if (job.work.kind == Kind.RESUPPLY && evidence.startsWith(ROUTE_PREPARATION_FAILED)
                && !ROUTE_BENCH_RECEIVED.equals(job.evidence)) {
            result = new Job(job.work, State.FAILED, job.attempts, job.worker, null, 0, evidence, observation);
        } else if (CARGO_CHECKPOINT.equals(evidence) && cargoCheckpoint(project, job) > 0) {
            result = new Job(job.work, State.WAITING, Math.max(0, job.attempts - 1), job.worker, null,
                    now + STALLED_WORKER_COOLDOWN_MS, evidence, observation, location);
        } else if (job.attempts < MAX_ATTEMPTS && job.work.kind == Kind.GATHER && job.work.sameWorkerAs == null
                && GATHER_STALLED.equals(evidence)) {
            result = new Job(job.work, State.WAITING, job.attempts, job.worker, null,
                    now + STALLED_WORKER_COOLDOWN_MS, evidence, observation, location);
        } else if (job.attempts >= MAX_ATTEMPTS && job.work.kind == Kind.GATHER && job.work.sameWorkerAs != null
                && GATHER_STALLED.equals(evidence)) {
            result = new Job(job.work, State.FAILED, job.attempts, job.worker, null,
                    now + STALLED_WORKER_COOLDOWN_MS, evidence, observation, location);
        } else if (job.attempts < MAX_ATTEMPTS && project.supplyChest != null && job.work.kind == Kind.BUILD
                && job.work.sameWorkerAs != null && SCHEMATIC_PLACEMENT.matcher(job.work.id).matches()
                && find(project, job.work.sameWorkerAs).work.constructionMaterial != null
                && placementApproachFailure(evidence)) {
            result = new Job(job.work, State.WAITING, job.attempts, job.worker, null,
                    now + PLACEMENT_HANDOFF_WINDOW_MS, evidence, observation, location);
        } else result = retry(job, evidence, observation, location);
        if (success && project.repeat != null && !project.repeat.cancelled && job.work.id.equals(lane(job.work.id) + "inspect")) {
            prepareOutputDeposit(project, result);
        } else if (success && project.repeat != null && job.work.id.equals(lane(job.work.id) + "deposit")) {
            var repeat = project.repeat;
            var next = new Repeat(repeat.batch, repeat.item, repeat.chest, repeat.completed + 1, observation, repeat.cancelled, repeat.capacity);
            var reset = repeat.cancelled
                    ? project.jobs.stream().map(entry -> entry == job ? result : entry).toList()
                    : project.jobs.stream().map(entry -> lane(entry.work.id).equals(lane(job.work.id))
                            ? new Job(entry.work, State.WAITING, 0, null, null, 0, null, null, entry.resourceSite) : entry).toList();
            commit(project.withRepeat(reset, next));
        } else replace(project, result);
        markAssignmentSeen(project, job.worker, now);
        restoredLeases.remove(token);
        return true;
    }

    private static String currentFuel(Project project, FuelUse plan) {
        return JSON.fromJson(find(project, plan.job).work.payload, SmeltFuel.class).fuel;
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

    private static String workerKey(String world, String worker, String project) {
        return world + "\n" + worker.toLowerCase(Locale.ROOT) + "\n" + (project == null ? "" : project);
    }

    private void markSeen(String world, String worker, String project, long now) {
        workerSeen.put(workerKey(world, worker, project), now);
    }

    private void markAssignmentSeen(Project project, String worker, long now) {
        markSeen(project.world, worker, project.id, now);
        if (unscopedWorkers.contains(workerKey(project.world, worker, null))) {
            markSeen(project.world, worker, null, now);
        }
    }

    private boolean recoverShortTransfer(Project project, Job transfer, String evidence, ChestObservation observation)
            throws IOException {
        var chest = observation.report;
        if (!chest.location.equals(project.supplyChest)) return false;
        var target = transferTarget(transfer);
        if (target == null || target.item == null) return false;
        boolean deposit = "DEPOSIT".equals(target.direction);
        String production = deposit ? transfer.work.sameWorkerAs
                : "TAKE".equals(target.direction) && transfer.work.needs.size() == 1 ? transfer.work.needs.get(0) : null;
        if (production == null) return false;
        int stock = stockCount(chest, target.item);
        if (stock >= target.stock) return false;

        var jobs = new HashMap<String, Job>();
        project.jobs.forEach(job -> jobs.put(job.work.id, job));
        var prerequisite = jobs.get(production);
        return rewindSupply(project, transfer, prerequisite, jobs, evidence, observation);
    }

    private static int stockCount(ChestReport chest, String item) {
        return "any_logs".equals(item)
                ? ResourceCatalog.blocks(item, "both").stream().mapToInt(id -> chest.items.getOrDefault(id, 0)).sum()
                : chest.items.getOrDefault(item, 0);
    }

    private void settleContribution(Project project, Job deposit, String evidence, ChestObservation observation,
                                    long now) throws IOException {
        if (observation == null) {
            replace(project, interruptedContribution(deposit, now));
            return;
        }
        var target = transferTarget(deposit);
        var gather = find(project, deposit.work.sameWorkerAs);
        int remaining = target.stock - stockCount(observation.report, target.item);
        var result = new Job(deposit.work, remaining <= 0 ? State.DONE : State.WAITING, deposit.attempts,
                remaining <= 0 ? deposit.worker : null, remaining <= 0 ? deposit.lease : null, 0,
                remaining <= 0 ? evidence : CONTRIBUTION_SETTLED, observation);
        var jobs = new ArrayList<>(project.jobs);
        jobs.set(jobs.indexOf(deposit), result);
        var recoveries = new HashMap<>(project.supplyRecoveries);
        if (remaining <= 0 && gather.state != State.DONE) {
            // Keep the signed lease until its expiry so the miner has time to stop acting.
            var confirmed = gather.state == State.LEASED && gather.expiresAt > now
                    ? new Job(gather.work, State.LEASED, gather.attempts, gather.worker, gather.lease,
                            gather.expiresAt, GATHER_STOCK_CONFIRMED, gather.chest, gather.resourceSite)
                    : completedGatherFromChest(gather, deposit.worker);
            jobs.set(jobs.indexOf(gather), confirmed);
        } else if (gather.state == State.WAITING || gather.state == State.LEASED || gather.state == State.FAILED) {
            var work = gather.work;
            var payload = JSON.fromJson(work.payload, com.google.gson.JsonObject.class);
            int outstanding = Math.max(1, remaining);
            int previousCount = payload.get("count").getAsInt();
            if (outstanding < previousCount) {
                payload.addProperty("count", outstanding);
                var adjusted = new Work(work.id, work.kind, JSON.toJson(payload), work.needs, work.locks, work.sameWorkerAs);
                String retry = gather.state == State.LEASED && gather.expiresAt > now
                        ? GATHER_HANDOFF + gather.worker : gather.evidence;
                boolean toolFailure = gather.state == State.FAILED && EXHAUSTED_TOOLS.equals(gather.evidence);
                boolean afterRescue = recoveries.remove(equippedRescueKey(gather)) != null;
                if (toolFailure || afterRescue) recoveries.putIfAbsent("tool-stock:" + work.id, previousCount);
                var reduced = gather.state == State.FAILED && !toolFailure
                        ? new Job(adjusted, State.WAITING, 0, null, null, 0, null, gather.chest, gather.resourceSite)
                        : new Job(adjusted, gather.state, gather.attempts, gather.worker,
                                gather.lease, CARGO_CHECKPOINT.equals(gather.evidence) ? 0 : gather.expiresAt,
                                CARGO_CHECKPOINT.equals(gather.evidence) ? null : retry, gather.chest, gather.resourceSite);
                jobs.set(jobs.indexOf(gather), reduced);
            }
        }
        commit(project.withRecovery(jobs, recoveries));
    }

    private static Job completedGatherFromChest(Job gather, String worker) {
        return new Job(gather.work, State.DONE, gather.attempts, worker, null, 0,
                GATHER_STOCK_CONFIRMED, null, gather.resourceSite);
    }

    private static Job interruptedContribution(Job deposit, long now) {
        if (deposit.attempts == 0 && deposit.chest == null) {
            return new Job(deposit.work, State.WAITING, 1, null, null, now + STALLED_WORKER_COOLDOWN_MS,
                    CONTRIBUTION_RETRY, null);
        }
        return new Job(deposit.work, State.WAITING, deposit.attempts, null, null, 0, CONTRIBUTION_SETTLED, deposit.chest);
    }

    private void recoverMissingBuildCargo(String world) throws IOException {
        for (var project : List.copyOf(projects.values())) {
            if (!world.equals(project.world) || project.supplyChest == null || cancelled(project)
                    || project.jobs.stream().noneMatch(job -> job.work.kind == Kind.BUILD && job.work.sameWorkerAs != null
                    && job.evidence != null && job.evidence.startsWith("Missing ")
                    && (job.state == State.WAITING || job.state == State.FAILED))) continue;
            var consumers = new HashMap<String, List<Job>>();
            for (var job : project.jobs) {
                if (job.work.kind == Kind.BUILD && job.work.sameWorkerAs != null && job.state != State.DONE) {
                    consumers.computeIfAbsent(job.work.sameWorkerAs, ignored -> new ArrayList<>()).add(job);
                }
            }
            var recovery = new HashMap<>(project.supplyRecoveries);
            var changes = new HashMap<String, Job>();
            for (var pickup : project.jobs) {
                var builds = consumers.get(pickup.work.id);
                String material = pickup.work.constructionMaterial;
                if (builds == null || pickup.state != State.DONE || material == null
                        || !pickup.work.locks.contains(project.supplyChest)
                        || builds.stream().anyMatch(job -> job.state == State.LEASED)) continue;
                var missing = builds.stream().filter(job -> missingBuildMaterial(job, material)).toList();
                if (missing.isEmpty()) continue;
                var target = JSON.fromJson(pickup.work.payload, PickupTarget.class);
                if (target == null || !"TAKE".equals(target.direction) || !material.equals(target.item)) continue;
                String key = "pickup:" + pickup.work.id;
                String pendingKey = "pickup-pending:" + pickup.work.id;
                int previouslyPending = recovery.getOrDefault(pendingKey, target.stock);
                int attempts = previouslyPending == builds.size() ? recovery.getOrDefault(key, 0) : 0;
                if (attempts >= MAX_SUPPLY_RECOVERIES) continue;
                if (reservedStorage(project) == null && projects.values().stream().anyMatch(other ->
                        !other.id.equals(project.id) && other.world.equals(world)
                                && project.supplyChest.equals(reservedStorage(other)))) break;
                long outstanding = builds.stream().filter(job -> job.state == State.WAITING
                        || missingBuildMaterial(job, material)).count();
                var payload = JSON.fromJson(pickup.work.payload, com.google.gson.JsonObject.class);
                // A repaired placement can increase demand after a previous retry shrank the pickup.
                payload.addProperty("stock", Math.min(outstanding, payload.has("partner") ? 3456 : 1728));
                var work = new Work(pickup.work.id, pickup.work.kind, JSON.toJson(payload), pickup.work.needs,
                        pickup.work.locks, pickup.work.sameWorkerAs, pickup.work.cleanup, material);
                changes.put(work.id, new Job(work, State.WAITING, 0, null, null, 0,
                        "Build inventory is short; requesting remaining materials", pickup.chest, pickup.resourceSite));
                for (var job : missing) {
                    changes.put(job.work.id, new Job(job.work, State.WAITING, 0, null, null, 0,
                            "Waiting for replacement build materials", job.chest, job.resourceSite));
                }
                recovery.put(key, attempts + 1);
                recovery.put(pendingKey, builds.size());
            }
            if (!changes.isEmpty()) {
                var jobs = project.jobs.stream().map(job -> changes.getOrDefault(job.work.id, job)).toList();
                commit(project.withRecovery(jobs, recovery));
            }
        }
    }

    private static boolean missingBuildMaterial(Job job, String material) {
        return (job.state == State.WAITING || job.state == State.FAILED)
                && (("Missing material " + material).equals(job.evidence)
                || ("Missing unmodified material " + material).equals(job.evidence));
    }

    private static boolean stalledPinnedGathering(Job job) {
        return job.state == State.FAILED && job.work.kind == Kind.GATHER && job.work.sameWorkerAs != null
                && GATHER_STALLED.equals(job.evidence);
    }

    private boolean recoverStalledGathering(String world, String projectId, long now) throws IOException {
        for (var project : projects.values()) {
            if (!world.equals(project.world) || project.supplyChest == null && project.repeat == null
                    || cancelled(project) || projectId != null && !projectId.equals(project.id)) continue;
            for (var gather : project.jobs) {
                if (!stalledPinnedGathering(gather)
                        || project.supplyRecoveries.getOrDefault(stalledSupplyKey(gather), 0) >= MAX_SUPPLY_RECOVERIES) continue;
                if (gather.expiresAt == 0) {
                    // Old journals did not store the reassignment window. Give their fleet time to respond too.
                    replace(project, new Job(gather.work, gather.state, gather.attempts, gather.worker, null,
                            now + STALLED_WORKER_COOLDOWN_MS, gather.evidence, gather.chest, gather.resourceSite));
                    break;
                }
                if (now < gather.expiresAt) continue;
                var jobs = new HashMap<String, Job>();
                project.jobs.forEach(job -> jobs.put(job.work.id, job));
                var supplier = jobs.get(gather.work.sameWorkerAs);
                if (rewindSupply(project, gather, supplier, jobs, GATHER_STALLED, gather.chest)) return true;
            }
        }
        return false;
    }

    private static String stalledSupplyKey(Job job) {
        return "stalled-supply:" + job.work.id;
    }

    private boolean recoverToolsAfterBanking(String world, String projectId) throws IOException {
        for (var project : projects.values()) {
            if (!world.equals(project.world) || project.supplyChest == null || cancelled(project)
                    || projectId != null && !project.id.equals(projectId)
                    || projects.values().stream().anyMatch(other -> other != project && world.equals(other.world)
                        && project.supplyChest.equals(reservedStorage(other)))) continue;
            for (var gathering : project.jobs) {
                if (gathering.work.kind != Kind.GATHER || gathering.state != State.FAILED
                        || !EXHAUSTED_TOOLS.equals(gathering.evidence)) continue;
                Integer previous = project.supplyRecoveries.get("tool-stock:" + gathering.work.id);
                if (previous == null) continue;
                var payload = JSON.fromJson(gathering.work.payload, com.google.gson.JsonObject.class);
                var count = payload == null ? null : payload.get("count");
                if (count != null && count.getAsInt() < previous
                        && recoverGatheringTools(project, gathering, gathering.resourceSite, 0)) return true;
            }
        }
        return false;
    }

    private boolean recoverGatheringTools(Project project, Job gathering, ResourceSite site, long now) throws IOException {
        if (project.supplyChest == null && project.repeat == null
                || gathering.work.kind != Kind.GATHER || gathering.work.sameWorkerAs == null) return false;
        var jobs = new HashMap<String, Job>();
        project.jobs.forEach(job -> jobs.put(job.work.id, job));
        var supplier = jobs.get(gathering.work.sameWorkerAs);
        for (var stage = supplier; stage != null; stage = jobs.get(stage.work.sameWorkerAs)) {
            if (stage.state != State.DONE) return false;
            if (stage.work.kind != Kind.CRAFT) continue;
            var recipe = JSON.fromJson(stage.work.payload, ToolRecipe.class);
            if (recipe != null && recipe.recipe != null && PICKAXE_TIERS.contains(recipe.recipe)) {
                return rewindSupply(project, gathering, supplier, jobs, GATHER_TOOLS_NEEDED, gathering.chest, site, now);
            }
        }
        return false;
    }

    private static boolean missingProductionInput(Job job, String evidence) {
        if (job.work.kind == Kind.CRAFT) {
            var recipe = JSON.fromJson(job.work.payload, ToolRecipe.class);
            return recipe != null && recipe.recipe != null && ("Missing ingredients for " + recipe.recipe).equals(evidence);
        }
        if (job.work.kind != Kind.SMELT) return false;
        var spec = JSON.fromJson(job.work.payload, SmeltFuel.class);
        return spec != null && ((spec.input != null && ("Missing furnace input " + spec.input).equals(evidence))
                || (spec.fuel != null && ("Missing furnace fuel " + spec.fuel).equals(evidence)));
    }

    private boolean recoverMissingProduction(Project project, Job job, String evidence) throws IOException {
        if (project.supplyChest == null || job.work.sameWorkerAs == null || !missingProductionInput(job, evidence)) return false;
        var jobs = new HashMap<String, Job>();
        project.jobs.forEach(stage -> jobs.put(stage.work.id, stage));
        var supplier = jobs.get(job.work.sameWorkerAs);
        for (var stage = supplier; stage != null; stage = jobs.get(stage.work.sameWorkerAs)) {
            if (stage.state != State.DONE) return false;
            if (stage.work.kind == Kind.GATHER && stage.work.sameWorkerAs == null) {
                return rewindSupply(project, job, supplier, jobs, evidence, job.chest);
            }
            if (stage.work.kind != Kind.CRAFT && stage.work.kind != Kind.SMELT && stage.work.kind != Kind.GATHER) return false;
        }
        return false;
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
                var key = workerKey(world, supplier.worker, project.id);
                // After a coordinator restart, give workers the full window to reconnect.
                long seen = Math.max(workerSeen.computeIfAbsent(key, ignored -> now),
                        workerSeen.getOrDefault(workerKey(world, supplier.worker, null), 0L));
                if (now - seen < SUPPLIER_OFFLINE_MS) continue;
                if (rewindSupply(project, job, supplier, jobs, "Supplier " + supplier.worker + " went offline",
                        job.chest)) break;
            }
        }
    }

    private boolean rewindSupply(Project project, Job waiting, Job supplier, Map<String, Job> jobs,
                                 String evidence, ChestObservation observation) throws IOException {
        return rewindSupply(project, waiting, supplier, jobs, evidence, observation, waiting.resourceSite, 0);
    }

    private boolean rewindSupply(Project project, Job waiting, Job supplier, Map<String, Job> jobs,
                                 String evidence, ChestObservation observation, ResourceSite site,
                                 long preferOriginalAt) throws IOException {
        Set<String> rewind = new HashSet<>();
        Job stage = supplier;
        // Earlier batches can gate gathering, but their completed work must survive recovery.
        while (stage.work.kind != Kind.GATHER || stage.work.sameWorkerAs != null) {
            String previous = stage.work.sameWorkerAs;
            if (previous == null) {
                if (stage.work.needs.size() != 1) return false;
                previous = stage.work.needs.get(0);
            }
            rewind.add(stage.work.id);
            stage = jobs.get(previous);
        }
        String root = stage.work.id;
        rewind.add(root);
        // A second pickup may still be drawing from the same deposit.
        if (jobs.values().stream().anyMatch(job -> job != waiting && job.state == State.LEASED
                && (rewind.contains(job.work.id) || job.work.kind == Kind.TRANSFER
                && job.work.needs.stream().anyMatch(rewind::contains)))) return false;
        var recoveries = new HashMap<>(project.supplyRecoveries);
        boolean stalled = GATHER_STALLED.equals(evidence);
        if (stalled && !privatePreparationReady(project, waiting, rewind)) return false;
        String recoveryKey = stalled ? stalledSupplyKey(waiting)
                : missingProductionInput(waiting, evidence) ? "production-inputs:" + waiting.work.id : root;
        int attempts = recoveries.getOrDefault(recoveryKey, 0);
        if (GATHER_TOOLS_NEEDED.equals(evidence)) {
            var source = gatherSource(waiting.work.payload);
            String checkpoint = "tool-depth:" + waiting.work.id;
            if (site != null && source != null && SubsurfaceAccess.prospectingAltitude(source.resource) != null
                    && recoveries.getOrDefault(checkpoint, Integer.MAX_VALUE) - (long) site.y >= 8) {
                // Renew while a miner opens deeper ground, with a fixed limit at each checkpoint.
                recoveries.put(checkpoint, site.y);
                attempts = 0;
                recoveries.remove(equippedRescueKey(waiting));
            }
            var payload = JSON.fromJson(waiting.work.payload, com.google.gson.JsonObject.class);
            var count = payload == null ? null : payload.get("count");
            if (count != null) {
                int outstanding = count.getAsInt();
                String stockCheckpoint = "tool-stock:" + waiting.work.id;
                Integer previous = recoveries.put(stockCheckpoint, outstanding);
                if (previous != null && outstanding < previous) {
                    attempts = 0;
                    recoveries.remove(equippedRescueKey(waiting));
                }
            }
            if (waiting.state == State.FAILED) attempts = 0;
        }
        if (attempts >= MAX_SUPPLY_RECOVERIES) {
            replace(project, new Job(waiting.work, State.FAILED, waiting.attempts, null, null, 0,
                    evidence + " after " + attempts + " supply recoveries", observation, site));
            return true;
        }
        recoveries.put(recoveryKey, attempts + 1);
        rewind.add(waiting.work.id);
        var updated = project.jobs.stream().map(job -> {
            if (!rewind.contains(job.work.id)) return job;
            boolean preferred = preferOriginalAt > 0 && job.work.id.equals(root);
            return new Job(job.work, State.WAITING, 0, preferred ? waiting.worker : null, null,
                    preferred ? preferOriginalAt + STALLED_WORKER_COOLDOWN_MS : 0,
                    stalled && job == waiting ? evidence : evidence + "; reacquiring materials",
                    job == waiting ? observation : job.chest,
                    job == waiting ? site : job.resourceSite);
        }).toList();
        commit(project.withRecovery(updated, recoveries));
        return true;
    }

    private void expire(long now) throws IOException {
        var next = new LinkedHashMap<>(projects);
        var interrupted = new HashSet<UUID>();
        boolean changed = false;
        for (Project project : projects.values()) {
            List<Job> jobs = null;
            for (int i = 0; i < project.jobs.size(); i++) {
                Job job = project.jobs.get(i);
                if (job.state == State.LEASED && job.expiresAt <= now) {
                    if (jobs == null) jobs = new ArrayList<>(project.jobs);
                    String handoff = handoffWorker(job);
                    Job waiting;
                    boolean restored = restoredLeases.contains(job.lease);
                    if (restored) interrupted.add(job.lease);
                    routeToolRequests.remove(job.lease);
                    if (job.work.kind == Kind.RESUPPLY) {
                        jobs.set(i, retry(job, LEASE_EXPIRED, job.chest, null));
                        continue;
                    }
                    if (CONTRIBUTION_ACTIVE.equals(job.evidence)) {
                        jobs.set(i, interruptedContribution(job, now));
                        continue;
                    }
                    if (job.work.kind == Kind.GATHER && GATHER_STOCK_CONFIRMED.equals(job.evidence)) {
                        var depositor = project.jobs.stream().filter(candidate -> candidate.state == State.DONE
                                && job.work.id.equals(candidate.work.sameWorkerAs) && depositTarget(candidate))
                                .findFirst().map(Job::worker).orElse(job.worker);
                        jobs.set(i, completedGatherFromChest(job, depositor));
                        continue;
                    }
                    if (handoff != null) {
                        jobs.set(i, new Job(job.work, State.WAITING, Math.max(0, job.attempts - 1), handoff, null,
                                job.expiresAt + GATHER_RECONNECT_GRACE_MS, GATHER_REASSIGNED, job.chest, job.resourceSite));
                        continue;
                    }
                    if (job.gatheringAfterPickup != null) {
                        waiting = new Job(job.gatheringAfterPickup, State.FAILED, job.attempts, null, null, 0,
                                GATHER_STALLED, job.chest);
                    } else if (restored && job.attempts == MAX_ATTEMPTS + 1 && job.work.constructionMaterial != null) {
                        waiting = new Job(job.work, State.FAILED, MAX_ATTEMPTS, null, null, 0,
                                TARGET_TIMEOUT, job.chest, job.resourceSite);
                    } else if (restored) {
                        waiting = new Job(job.work, State.WAITING, Math.max(0, job.attempts - 1), null, null, 0,
                                COORDINATOR_RESTARTED, job.chest, job.resourceSite);
                    } else {
                        waiting = retry(job, LEASE_EXPIRED, job.chest, job.resourceSite);
                    }
                    if (waiting.state == State.WAITING && job.work.kind == Kind.GATHER && job.work.sameWorkerAs == null
                            && job.resourceSite != null && gatherSource(job.work.payload) != null) {
                        // The original gatherer may reconnect with partial cargo still in inventory.
                        waiting = new Job(job.work, State.WAITING, waiting.attempts, job.worker, null,
                                job.expiresAt + GATHER_RECONNECT_GRACE_MS, waiting.evidence, job.chest, job.resourceSite);
                    }
                    jobs.set(i, waiting);
                }
            }
            if (jobs != null) {
                next.put(project.id, settleBarriers(project.withJobs(jobs)));
                changed = true;
            }
        }
        if (changed) {
            persist(next);
            restoredLeases.removeAll(interrupted);
        }
    }

    private static ResourceSite gatheringSite(Job job, ResourceSite site) {
        if (site != null && job.work.kind != Kind.GATHER) throw new IllegalArgumentException("Only gathering reports a resource site");
        return site == null ? job.resourceSite : site;
    }

    synchronized int retryExcavation(String world, String id) throws IOException {
        return retryFailures(world, id, job -> job.work.kind == Kind.EXCAVATE && job.work.cleanup == null);
    }

    synchronized int retryDeliveries(String world, String id) throws IOException {
        return retryFailures(world, id, job -> job.work.kind == Kind.TRANSFER && depositTarget(job));
    }

    synchronized int retryInterruptedGathering(String world, String id) throws IOException {
        int reopened = retryFailures(world, id, job -> job.work.kind == Kind.GATHER
                && (LEASE_EXPIRED.equals(job.evidence) || GATHER_STALLED.equals(job.evidence)));
        var project = projects.get(id);
        for (var job : project.jobs) {
            if (job.state != State.FAILED || !EXHAUSTED_TOOLS.equals(job.evidence)) continue;
            project = projects.get(id);
            var current = find(project, job.work.id);
            if (current.state != State.FAILED) continue;
            if (project.supplyChest != null && reservedStorage(project) == null) checkStorage(world, project.supplyChest);
            if (recoverGatheringTools(project, current, current.resourceSite, 0)) reopened++;
        }
        return reopened;
    }

    synchronized int retryPlacementApproaches(String world, String id) throws IOException {
        return retryFailures(world, id, job -> job.work.kind == Kind.BUILD
                && (LEASE_EXPIRED.equals(job.evidence) || placementApproachFailure(job)));
    }

    synchronized int retryInterruptedProduction(String world, String id) throws IOException {
        int reopened = retryFailures(world, id, job -> (job.work.kind == Kind.CRAFT || job.work.kind == Kind.SMELT)
                && (LEASE_EXPIRED.equals(job.evidence) || TARGET_TIMEOUT.equals(job.evidence)));
        var project = projects.get(id);
        for (var job : project.jobs) {
            if (job.state != State.FAILED || !missingProductionInput(job, job.evidence)) continue;
            var current = projects.get(id);
            var failed = find(current, job.work.id);
            if (failed.state != State.FAILED) continue;
            if (current.supplyChest != null && reservedStorage(current) == null) checkStorage(world, current.supplyChest);
            if (recoverMissingProduction(current, failed, failed.evidence)) reopened++;
        }
        return reopened;
    }

    private static boolean placementApproachFailure(Job job) {
        return placementApproachFailure(job.evidence);
    }

    private static boolean placementApproachFailure(String evidence) {
        return evidence != null && (TARGET_TIMEOUT.equals(evidence)
                || evidence.startsWith(PLACEMENT_REJECTED)
                || evidence.startsWith("No reachable solid support face for "));
    }

    private static boolean placementHandoffOpen(Job job, long now) {
        return job.state == State.WAITING && job.work.kind == Kind.BUILD && job.work.sameWorkerAs != null
                && job.worker != null && job.expiresAt > now && placementApproachFailure(job);
    }

    private int retryFailures(String world, String id, java.util.function.Predicate<Job> applicable) throws IOException {
        var project = projects.get(id);
        if (project == null || !project.world.equals(world) || cancelled(project)) {
            throw new IllegalArgumentException("Unknown active project in this world");
        }
        var jobs = new ArrayList<>(project.jobs);
        int reopened = 0;
        for (int index = 0; index < jobs.size(); index++) {
            var job = jobs.get(index);
            if (job.state == State.FAILED && applicable.test(job)) {
                jobs.set(index, new Job(job.work, State.WAITING, 0, null, null, 0, job.evidence, job.chest, job.resourceSite));
                reopened++;
            }
        }
        if (reopened != 0) {
            if (project.supplyChest != null && reservedStorage(project) == null) {
                checkStorage(world, project.supplyChest);
            }
            commit(project.withJobs(jobs));
        }
        return reopened;
    }

    private static Job retry(Job job, String reason, ChestObservation chest, ResourceSite site) {
        State state = job.work.kind != Kind.RESUPPLY && job.attempts < MAX_ATTEMPTS ? State.WAITING : State.FAILED;
        return new Job(job.work, state, job.attempts, null, null, 0, reason, chest, site);
    }

    private static boolean retryDelayedFor(Job job, String worker, long now) {
        if (job.expiresAt <= now) return false;
        if (RECOVERING_TOOLS.equals(job.evidence) || CONSTRUCTION_PICKUP.equals(job.evidence)
                || gatherReconnectPending(job, now)) {
            return !worker.equalsIgnoreCase(job.worker);
        }
        return !GATHER_STALLED.equals(job.evidence) || worker.equalsIgnoreCase(job.worker);
    }

    private static boolean gatherReconnectPending(Job job, long now) {
        return job.work.kind == Kind.GATHER && job.worker != null && job.expiresAt > now
                && (LEASE_EXPIRED.equals(job.evidence) || COORDINATOR_RESTARTED.equals(job.evidence)
                        || GATHER_REASSIGNED.equals(job.evidence));
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
        if (project.jobs.stream().noneMatch(job -> job.work.kind.coordinatorOnly() && job.state == State.WAITING)) {
            return project;
        }
        Set<String> done = new HashSet<>();
        Set<String> blocked = new HashSet<>();
        List<Job> updated = new ArrayList<>(project.jobs.size());
        for (Job job : project.jobs) {
            var kind = job.work.kind;
            if (job.state == State.WAITING && kind.coordinatorOnly()) {
                boolean ready = kind == Kind.SETTLEMENT_BARRIER
                        ? job.work.needs.stream().allMatch(id -> settled(id, done, blocked))
                        : done.containsAll(job.work.needs);
                if (ready) {
                    String evidence = kind == Kind.SETTLEMENT_BARRIER ? "Prerequisites settled" : "Prerequisites complete";
                    job = new Job(job.work, State.DONE, 0, null, null, 0, evidence, null);
                }
            }
            if (job.state == State.DONE) done.add(job.work.id);
            if (blockedByPrevious(job, blocked)) blocked.add(job.work.id);
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

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }
}
