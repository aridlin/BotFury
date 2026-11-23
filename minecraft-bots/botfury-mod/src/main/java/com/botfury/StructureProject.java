package com.botfury;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/** Compiles a structure into leased excavation, placement and verification jobs. */
final class StructureProject {
    private static final Gson JSON = new Gson();
    private static final int CELL_LIMIT = 32_768;
    private static final int INDIVIDUAL_INSPECTION_LIMIT = 128;
    private static final Identifier AIR = Identifier.of("minecraft", "air");

    record Plan(List<ProjectQueue.Work> jobs, Map<Identifier, Integer> materials, ProjectGather.Area area, ProjectGather.Area footprint) {}
    private record Cell(BlockPos pos, BlockState desired, BlockState observed) {}
    private record BuildPose(BlockPos stand, Direction face, BlockPos support, boolean exposedBeforeBuild,
                             BuildAccess access) {
        BuildPose(BlockPos stand, Direction face, BlockPos support, boolean exposedBeforeBuild) {
            this(stand, face, support, exposedBeforeBuild, null);
        }
    }
    private record BuildAccess(Set<String> needs, Set<String> locks) {}
    private record AccessStep(BlockPos pos, AccessStep previous, int distance) {}
    private record TemporarySupport(BuildPose pillar, BlockPos stand) {}
    private record ScaffoldStep(BlockPos block, ProjectQueue.Work placement, BlockPos removalStand) {}
    private record Scaffold(List<ProjectQueue.Work> jobs, List<ScaffoldStep> blocks,
                            Map<BlockPos, BlockState> layout, Map<BlockPos, String> placed) {
        String lastPlacement() { return blocks.get(blocks.size() - 1).placement.id(); }
    }
    private static final BlockState SCAFFOLD = Blocks.COBBLESTONE.getDefaultState();

    private StructureProject() {}

    static int submit(MinecraftClient client, String file, BlockPos origin, boolean supplied) {
        var here = LocalFormation.here();
        int port = BotFuryMod.controlPort();
        if (here == null || client.world == null || port < 1024 || Boolean.getBoolean("botfury.managedHttp")) {
            ClientActions.message(client, "Join the build site on the controller first");
            return 0;
        }
        try {
            Plan plan = plan(client, file, origin);
            String id = file.substring(0, Math.min(file.lastIndexOf('.'), 48)) + "-" + UUID.randomUUID().toString().substring(0, 8);
            var supply = supplied && !plan.materials().isEmpty() ? StructureSupply.compile(client, plan) : null;
            ProjectBroker.submitStructure(id, here, plan, supply);
            int jobCount = supply == null ? plan.jobs().size() : supply.jobs().size();
            int workers = BotTracker.sendSelected("project:worker " + port);
            ClientActions.message(client, "Queued " + id + " (" + jobCount + " jobs, " + workers + " bots requested)");
            if (!plan.materials().isEmpty()) ClientActions.message(client, "Building materials: " + plan.materials());
            return 1;
        } catch (IOException | RuntimeException error) {
            ClientActions.message(client, "Could not queue structure: " + error.getMessage());
            return 0;
        }
    }

    static Plan plan(MinecraftClient client, String file, BlockPos origin) throws IOException {
        if (!file.matches("[A-Za-z0-9_-]{1,64}\\.(nbt|schem)")) {
            throw new IllegalArgumentException("Use a structure filename ending in .nbt or .schem");
        }
        var folder = FabricLoader.getInstance().getConfigDir().resolve("botfury-structures");
        Files.createDirectories(folder);
        var root = folder.toRealPath();
        var path = root.resolve(file).toRealPath();
        if (!root.equals(path.getParent()) || !Files.isRegularFile(path) || Files.size(path) > 256 * 1024) {
            throw new IllegalArgumentException("Use a structure in the BotFury structures folder under 256 KiB");
        }
        NbtCompound structure;
        try (var input = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
            structure = NbtIo.read(input, new NbtTagSizeTracker(2 * 1024 * 1024));
        }
        var desired = file.endsWith(".schem") ? schematicCells(structure, origin) : cells(structure, origin);
        var cells = new ArrayList<Cell>();
        for (var entry : desired.entrySet()) {
            var pos = entry.getKey();
            if (!loaded(client, pos)) throw new IllegalArgumentException("Load the entire build site first");
            var state = client.world.getBlockState(pos);
            if (!sameState(entry.getValue(), state) && BuildProtection.blocked(pos)) {
                throw new IllegalArgumentException("Schematic overlaps an exclusion zone at " + pos.toShortString());
            }
            var partner = ProjectPlacement.chestPartner(pos, entry.getValue());
            boolean reusable = partner != null && state.equals(ProjectPlacement.singleChest(entry.getValue()));
            boolean sameWater = ProjectWater.water(entry.getValue()) && ProjectWater.water(state)
                    && (!ProjectWater.source(state) || ProjectWater.source(entry.getValue()));
            if (!sameState(entry.getValue(), state) && !sameWater && !reusable && !state.isAir()
                    && (state.hasBlockEntity() || !state.getFluidState().isEmpty() || state.getHardness(client.world, pos) < 0)) {
                throw new IllegalArgumentException("Protected block or fluid at " + pos.toShortString());
            }
            cells.add(new Cell(pos, entry.getValue(), state));
        }
        cells.sort(Comparator.comparingInt((Cell cell) -> cell.pos.getY())
                .thenComparingInt(cell -> cell.pos.getX()).thenComparingInt(cell -> cell.pos.getZ()));
        return compile(client, cells, desired);
    }

    private static Map<BlockPos, BlockState> cells(NbtCompound structure, BlockPos origin) {
        NbtList size = structure.getList("size", NbtElement.INT_TYPE);
        NbtList palette = structure.getList("palette", NbtElement.COMPOUND_TYPE);
        NbtList blocks = structure.getList("blocks", NbtElement.COMPOUND_TYPE);
        if (size.size() != 3 || palette.isEmpty() || blocks.isEmpty() || blocks.size() > CELL_LIMIT
                || !structure.getList("entities", NbtElement.COMPOUND_TYPE).isEmpty()) {
            throw new IllegalArgumentException("Expected a single-palette structure with at most " + CELL_LIMIT + " blocks and no entities");
        }
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.getCompound(i);
            var position = block.getList("pos", NbtElement.INT_TYPE);
            int index = block.getInt("state");
            if (!block.contains("state", NbtElement.INT_TYPE)
                    || index < 0 || index >= palette.size() || position.size() != 3) {
                throw new IllegalArgumentException("Invalid structure block or block entity");
            }
            for (int axis = 0; axis < 3; axis++) {
                if (position.getInt(axis) < 0 || position.getInt(axis) >= size.getInt(axis)) {
                    throw new IllegalArgumentException("Structure block lies outside its size");
                }
            }
            var state = palette.getCompound(index);
            var properties = new HashMap<String, String>();
            var tag = state.getCompound("Properties");
            for (String key : tag.getKeys()) {
                if (!tag.contains(key, NbtElement.STRING_TYPE)) throw new IllegalArgumentException("Invalid block property " + key);
                properties.put(key, tag.getString(key));
            }
            String name = state.getString("Name");
            if (block.contains("nbt") && (!SpongeSchematic.survivalContainer(name)
                    || !name.equals(block.getCompound("nbt").getString("id")))) {
                throw new IllegalArgumentException("Unsupported structure block entity " + name);
            }
            var blockState = supportedBlock(name, properties);
            var pos = absolute(origin, position.getInt(0), position.getInt(1), position.getInt(2));
            if (result.put(pos, blockState) != null) throw new IllegalArgumentException("Duplicate structure position");
        }
        return result;
    }

    private static Map<BlockPos, BlockState> schematicCells(NbtCompound schematic, BlockPos origin) {
        var result = new HashMap<BlockPos, BlockState>();
        SpongeSchematic.blocks(schematic, CELL_LIMIT).forEach((pos, text) -> {
            var properties = new HashMap<String, String>();
            int open = text.indexOf('[');
            String name = open < 0 ? text : text.substring(0, open);
            if (open >= 0) {
                if (!text.endsWith("]")) throw new IllegalArgumentException("Invalid schematic block state " + text);
                for (String pair : text.substring(open + 1, text.length() - 1).split(",", -1)) {
                    var parts = pair.split("=", -1);
                    if (parts.length != 2 || properties.put(parts[0], parts[1]) != null) {
                        throw new IllegalArgumentException("Invalid schematic block state " + text);
                    }
                }
            }
            result.put(absolute(origin, pos.getX(), pos.getY(), pos.getZ()), supportedBlock(name, properties));
        });
        return result;
    }

    private static BlockState supportedBlock(String name, Map<String, String> properties) {
        var state = ProjectBlock.state(Identifier.tryParse(name), properties);
        if (state.isAir()) return Registries.BLOCK.get(AIR).getDefaultState();
        if (!ProjectWater.water(state) && !ProjectPlacement.supported(state)) {
            throw new IllegalArgumentException("Unsupported structure block " + state);
        }
        return state;
    }

    private static boolean sameState(BlockState wanted, BlockState actual) {
        return wanted.isAir() ? actual.isAir() : wanted.equals(actual);
    }

    private static BlockPos absolute(BlockPos origin, int x, int y, int z) {
        return new ProjectBlock.Position(Math.addExact(origin.getX(), x), Math.addExact(origin.getY(), y),
                Math.addExact(origin.getZ(), z)).checked();
    }

    private static Plan compile(MinecraftClient client, List<Cell> cells, Map<BlockPos, BlockState> desired) {
        var jobs = new ArrayList<ProjectQueue.Work>();
        var changes = new ArrayList<String>();
        var placed = new HashMap<BlockPos, String>();
        var ready = new HashMap<BlockPos, BlockState>();
        var obstructing = new LinkedHashMap<Integer, Cell>();
        var removed = new HashSet<BlockPos>();
        var materials = new TreeMap<Identifier, Integer>();
        var pending = new LinkedHashMap<Integer, Cell>();
        var waterSources = new LinkedHashMap<Integer, Cell>();
        boolean hasWater = cells.stream().anyMatch(cell -> ProjectWater.water(cell.desired));
        if (hasWater && cells.stream().noneMatch(cell -> ProjectWater.source(cell.desired))) {
            throw new IllegalArgumentException("Flowing water requires a source in the structure");
        }
        var cleanup = new ArrayList<ProjectQueue.Work>();
        var layout = new HashMap<>(desired);
        var origin = cells.get(0).pos;
        var area = new ProjectGather.Area(origin, origin);
        var footprint = area;
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            area = StructureSupply.include(StructureSupply.include(area, cell.pos), cell.pos.down());
            footprint = StructureSupply.include(footprint, cell.pos);
            var partner = ProjectPlacement.chestPartner(cell.pos, cell.desired);
            if (partner != null) {
                var other = desired.get(partner);
                if (other == null || !cell.pos.equals(ProjectPlacement.chestPartner(partner, other))
                        || other.get(ChestBlock.FACING) != cell.desired.get(ChestBlock.FACING)) {
                    throw new IllegalArgumentException("Missing matching double chest half at " + partner.toShortString());
                }
                var single = ProjectPlacement.singleChest(cell.desired);
                if (cell.observed.equals(single) && client.world.getBlockState(partner).equals(single)) {
                    throw new IllegalArgumentException("Two existing single chests cannot be joined in place at " + cell.pos.toShortString());
                }
            }
            if (sameState(cell.desired, cell.observed)
                    || partner != null && cell.observed.equals(ProjectPlacement.singleChest(cell.desired))) {
                ready.put(cell.pos, cell.observed);
                continue;
            }
            if (!cell.observed.isAir() && !ProjectWater.water(cell.observed)) {
                obstructing.put(i, cell);
                removed.add(cell.pos);
            }
            if (cell.desired.isAir()) continue;
            if (ProjectWater.water(cell.desired)) {
                if (ProjectWater.source(cell.desired)) waterSources.put(i, cell);
                continue;
            }
            if (!ProjectPlacement.placeable(cell.desired, client.world, cell.pos)) {
                throw new IllegalArgumentException("Unsupported placement geometry: " + cell.desired);
            }
            pending.put(i, cell);
        }
        var inspectionChunks = new LinkedHashMap<Long, List<Cell>>();
        if (hasWater || cells.size() > INDIVIDUAL_INSPECTION_LIMIT) {
            for (Cell cell : cells) inspectionChunks.computeIfAbsent(ChunkPos.toLong(cell.pos),
                    ignored -> new ArrayList<>()).add(cell);
        }
        int verificationJobs = inspectionChunks.isEmpty() ? cells.size() : inspectionChunks.values().stream()
                .mapToInt(chunk -> (chunk.size() + ProjectInspection.MAX_CELLS - 1) / ProjectInspection.MAX_CELLS).sum();
        boolean groupedMutations = !inspectionChunks.isEmpty() && (!obstructing.isEmpty() || !pending.isEmpty() || !waterSources.isEmpty());
        if (groupedMutations) verificationJobs++;
        int wetJobs = waterSources.size() + (waterSources.isEmpty() ? 0 : 1);
        requireJobBudget(obstructing.size() + pending.size() + verificationJobs + wetJobs);
        var cleared = new HashMap<BlockPos, BlockState>();
        List<String> excavationFrontier = List.of();
        while (!obstructing.isEmpty()) {
            var wave = new ArrayList<String>();
            var clearedThisWave = new ArrayList<BlockPos>();
            for (var iterator = obstructing.entrySet().iterator(); iterator.hasNext(); ) {
                var entry = iterator.next();
                var cell = entry.getValue();
                var stand = findPlacementStand(client, cell.pos, null, pos ->
                        !removed.contains(pos.down()) && safeStand(client, pos, cleared)
                                && canRemoveBlock(client, pos, cell.pos, cleared));
                if (stand == null) continue;
                String id = "clear-" + entry.getKey();
                jobs.add(work(id, ProjectQueue.Kind.EXCAVATE, cell.pos, cell.observed, stand, Direction.UP, excavationFrontier));
                wave.add(id);
                clearedThisWave.add(cell.pos);
                area = StructureSupply.union(area, new ProjectBlock(cell.pos, stand, AIR));
                iterator.remove();
            }
            if (wave.isEmpty()) {
                var blocked = obstructing.values().iterator().next();
                throw new IllegalArgumentException("No reachable excavation face at " + blocked.pos.toShortString());
            }
            for (var pos : clearedThisWave) cleared.put(pos, Blocks.AIR.getDefaultState());
            changes.addAll(wave);
            excavationFrontier = List.copyOf(wave);
        }

        var exposedPlacements = new ArrayList<String>();
        var scaffolds = new ArrayList<Scaffold>();
        boolean allowTemporarySupport = false;
        while (!pending.isEmpty()) {
            int before = pending.size();
            for (var iterator = pending.entrySet().iterator(); iterator.hasNext(); ) {
                var entry = iterator.next();
                var cell = entry.getValue();
                var placementState = placementState(cell, ready);
                if (placementState == null) continue;
                var pose = buildPose(client, cell, layout, ready, cleared, placed, !scaffolds.isEmpty());
                var temporary = pose == null && allowTemporarySupport
                        ? temporarySupport(client, new Cell(cell.pos, placementState, cell.observed), layout, cleared) : null;
                if (temporary != null) pose = temporary.pillar;
                if (pose == null) continue;
                var approachNeeds = new ArrayList<>(excavationFrontier);
                approachNeeds.addAll(exposedPlacements);
                if (!scaffolds.isEmpty()) approachNeeds.add(scaffolds.get(scaffolds.size() - 1).lastPlacement());
                var needs = new LinkedHashSet<>(approachNeeds);
                if (placed.containsKey(pose.support)) needs.add(placed.get(pose.support));
                String id = "place-" + entry.getKey();
                if (temporary != null) {
                    var support = pose.support;
                    var state = Blocks.COBBLESTONE.getDefaultState();
                    layout.put(support, state);
                    String check = "check-support-" + entry.getKey();
                    String place = "place-support-" + entry.getKey();
                    String remove = "remove-support-" + entry.getKey();
                    jobs.add(work(check, ProjectQueue.Kind.VERIFY, support, Blocks.AIR.getDefaultState(),
                            temporary.stand, Direction.UP, approachNeeds));
                    jobs.add(work(place, ProjectQueue.Kind.BUILD, support, state, temporary.stand, Direction.UP, List.of(check)));
                    var removal = work(remove, ProjectQueue.Kind.EXCAVATE, support, state, temporary.stand,
                            Direction.UP, List.of(place));
                    cleanup.add(new ProjectQueue.Work(removal.id(), removal.kind(), removal.payload(), removal.needs(),
                            removal.locks(), null, new ProjectQueue.Cleanup(ProjectBlock.key(support), id)));
                    needs.add(place);
                    materials.merge(Registries.ITEM.getId(state.getBlock().asItem()), 1, Integer::sum);
                    area = StructureSupply.union(area, new ProjectBlock(support.down(), temporary.stand, AIR));
                    allowTemporarySupport = false;
                }
                placed.put(cell.pos, id);
                ready.put(cell.pos, placementState);
                var partner = ProjectPlacement.chestPartner(cell.pos, cell.desired);
                if (partner != null && placementState.equals(cell.desired)) {
                    ready.put(partner, desired.get(partner));
                    placed.put(partner, id);
                }
                changes.add(id);
                if (pose.access != null) needs.addAll(pose.access.needs);
                var placement = work(id, ProjectQueue.Kind.BUILD, cell.pos, placementState, pose.stand, pose.face, List.copyOf(needs));
                if (pose.access != null || partner != null) {
                    var locks = new LinkedHashSet<>(placement.locks());
                    if (pose.access != null) locks.addAll(pose.access.locks);
                    if (partner != null) locks.add(ProjectBlock.key(partner));
                    placement = new ProjectQueue.Work(id, placement.kind(), placement.payload(), placement.needs(), locks);
                }
                jobs.add(placement);
                if (pose.exposedBeforeBuild) exposedPlacements.add(id);
                materials.merge(Registries.ITEM.getId(cell.desired.getBlock().asItem()), 1, Integer::sum);
                area = StructureSupply.union(area, new ProjectBlock(pose.support, pose.stand, AIR));
                iterator.remove();
            }
            if (pending.size() == before) {
                if (!allowTemporarySupport) {
                    allowTemporarySupport = true;
                    continue;
                }
                var cell = pending.values().iterator().next();
                int cleanupJobs = scaffolds.stream().mapToInt(entry -> entry.blocks.size()).sum() + cleanup.size();
                int room = ProjectQueue.MAX_JOBS - jobs.size() - cleanupJobs - pending.size() - verificationJobs - wetJobs;
                var scaffold = scaffold(client, cell, pending.values(), layout, cleared, ready, placed,
                        List.copyOf(changes), scaffolds.size(), room);
                if (scaffold != null) {
                    scaffolds.add(scaffold);
                    jobs.addAll(scaffold.jobs);
                    layout.putAll(scaffold.layout);
                    ready.putAll(scaffold.layout);
                    placed.putAll(scaffold.placed);
                    changes.add(scaffold.lastPlacement());
                    materials.merge(Registries.ITEM.getId(Blocks.COBBLESTONE.asItem()), scaffold.blocks.size(), Integer::sum);
                    for (var step : scaffold.blocks) {
                        var stand = ProjectBlock.parse(step.placement.payload()).stand();
                        area = StructureSupply.union(area, new ProjectBlock(step.block, stand, AIR),
                                new ProjectBlock(step.block, step.removalStand, AIR));
                    }
                    allowTemporarySupport = false;
                    continue;
                }
                throw new IllegalArgumentException("No reachable support face for " + cell.desired + " at " + cell.pos.toShortString());
            }
        }
        var dryChanges = List.copyOf(changes);
        for (var removal : cleanup) {
            var support = removal.cleanup();
            jobs.add(new ProjectQueue.Work(removal.id(), removal.kind(), removal.payload(), removal.needs(),
                    removal.locks(), null, new ProjectQueue.Cleanup(support.site(), support.afterSettled(), dryChanges)));
            changes.add(removal.id());
        }
        if (!waterSources.isEmpty()) {
            String dryBarrier = "before-water";
            jobs.add(new ProjectQueue.Work(dryBarrier, ProjectQueue.Kind.BARRIER, "", List.copyOf(changes), Set.of()));
            for (var entry : waterSources.entrySet()) {
                var cell = entry.getValue();
                var pose = buildPose(client, cell, layout, ready, cleared, placed, !scaffolds.isEmpty());
                if (pose == null) throw new IllegalArgumentException("No reachable water placement face at " + cell.pos.toShortString());
                String id = "fill-source-" + entry.getKey();
                var job = work(id, ProjectQueue.Kind.BUILD, cell.pos, cell.desired, pose.stand, pose.face, List.of(dryBarrier));
                if (pose.access != null) {
                    var locks = new LinkedHashSet<>(job.locks());
                    locks.addAll(pose.access.locks);
                    job = new ProjectQueue.Work(id, job.kind(), job.payload(), job.needs(), locks);
                }
                jobs.add(job);
                changes.add(id);
                materials.merge(Registries.ITEM.getId(ProjectPlacement.material(cell.desired)), 1, Integer::sum);
                area = StructureSupply.union(area, new ProjectBlock(pose.support, pose.stand, AIR));
            }
        }
        List<String> prerequisites = List.copyOf(changes);
        if (groupedMutations) {
            jobs.add(new ProjectQueue.Work("before-inspection", ProjectQueue.Kind.BARRIER, "", prerequisites, Set.of()));
            prerequisites = List.of("before-inspection");
        }
        var verificationLayout = new HashMap<>(desired);
        var temporaryAccessCells = new HashSet<BlockPos>();
        for (var scaffold : scaffolds) {
            verificationLayout.putAll(scaffold.layout);
            temporaryAccessCells.addAll(scaffold.layout.keySet());
        }
        var inspections = new ArrayList<String>();
        if (!inspectionChunks.isEmpty()) {
            for (var group : inspectionChunks.values()) {
                // Stair removal observes air in any schematic cells temporarily used for access.
                var chunk = group.stream().filter(cell -> !temporaryAccessCells.contains(cell.pos)).toList();
                if (chunk.isEmpty()) continue;
                var stand = inspectionStand(client, chunk, verificationLayout);
                if (stand == null) throw new IllegalArgumentException("No safe inspection position in or near the chunk at " + chunk.get(0).pos.toShortString());
                area = StructureSupply.include(StructureSupply.include(area, stand), stand.down());
                for (int i = 0; i < chunk.size(); i += ProjectInspection.MAX_CELLS) {
                    var checks = chunk.subList(i, Math.min(i + ProjectInspection.MAX_CELLS, chunk.size())).stream()
                            .map(cell -> ProjectInspection.Check.of(cell.pos, cell.desired)).toList();
                    String id = "inspect-" + inspections.size();
                    String payload = ProjectInspection.payload(stand, checks, hasWater);
                    var work = new ProjectQueue.Work(id, ProjectQueue.Kind.INSPECT, payload, prerequisites, Set.of());
                    jobs.add(ProjectInspection.parse(payload).reserve(work));
                    inspections.add(id);
                }
            }
        } else {
            for (int i = 0; i < cells.size(); i++) {
                var cell = cells.get(i);
                if (temporaryAccessCells.contains(cell.pos)) continue;
                var stand = verificationStand(client, cell.pos, verificationLayout);
                if (stand == null) throw new IllegalArgumentException("No safe verification position near " + cell.pos.toShortString());
                String id = "verify-" + i;
                jobs.add(work(id, ProjectQueue.Kind.VERIFY, cell.pos, cell.desired, stand, Direction.UP, prerequisites));
                inspections.add(id);
                area = StructureSupply.union(area, new ProjectBlock(cell.pos, stand, AIR));
            }
        }
        var settledWork = new ArrayList<>(changes);
        settledWork.addAll(inspections);
        String previousCleanup = null;
        for (int i = scaffolds.size() - 1; i >= 0; i--) {
            previousCleanup = appendScaffoldCleanup(jobs, scaffolds.get(i), settledWork, previousCleanup);
        }
        requireJobBudget(jobs.size());
        return new Plan(List.copyOf(jobs), Map.copyOf(materials), area, footprint);
    }

    private static void requireJobBudget(int jobs) {
        if (jobs > ProjectQueue.MAX_JOBS) {
            throw new IllegalArgumentException("Structure needs more than " + ProjectQueue.MAX_JOBS + " jobs");
        }
    }

    private static Scaffold scaffold(MinecraftClient client, Cell first, Collection<Cell> pending,
                                     Map<BlockPos, BlockState> layout, Map<BlockPos, BlockState> cleared,
                                     Map<BlockPos, BlockState> ready, Map<BlockPos, String> placed,
                                     List<String> prerequisites, int index, int room) {
        if (room < 3) return null;
        // A staircase is useful only around the work that can be reached from its upper steps.
        var nearby = pending.stream().filter(cell -> horizontalDistance(first.pos, cell.pos) <= 36
                && cell.pos.getY() <= first.pos.getY() + 6).toList();
        BlockPos highTarget = nearby.stream().filter(cell -> horizontalDistance(first.pos, cell.pos) <= 4
                        && cell.pos.getY() <= first.pos.getY() + 4)
                .max(Comparator.comparingInt(cell -> cell.pos.getY())).orElseThrow().pos;
        String suffix = index == 0 ? "" : index + "-";
        var anchor = scaffoldAnchor(client, first, nearby, layout, cleared, ready, placed,
                prerequisites, suffix, room);
        if (anchor != null) return anchor;
        var current = new HashMap<>(cleared);
        current.putAll(ready);
        var stairs = new HashSet<List<BlockPos>>();
        Scaffold best = null;
        int fewestRemaining = nearby.size();
        for (var target : new LinkedHashSet<>(List.of(first.pos, highTarget))) {
            for (int rise = 1; rise <= 5 && 3 * rise * (rise + 1) / 2 <= room; rise++) {
                for (int yOffset : new int[]{-2, -1, 0, 1, 2}) {
                    for (var side : Direction.Type.HORIZONTAL) {
                        for (var ascent : List.of(side.getOpposite(), side.rotateYClockwise(), side.rotateYCounterclockwise())) {
                            for (int gap : new int[]{1, 2, 3}) for (int lateral : new int[]{0, 1, -1}) {
                                var top = target.up(yOffset - 1).offset(side, gap)
                                        .offset(side.rotateYClockwise(), lateral);
                                var bottom = top.down(rise - 1).offset(ascent.getOpposite(), rise - 1);
                                if (!stairs.add(List.of(bottom, top))) continue;
                                var trial = planScaffold(client, top, ascent, rise, layout, cleared, current,
                                        ready, placed, prerequisites, suffix);
                                if (trial == null) continue;
                                var expanded = new HashMap<>(layout);
                                expanded.putAll(trial.layout);
                                var expandedReady = new HashMap<>(ready);
                                expandedReady.putAll(trial.layout);
                                var expandedPlaced = new HashMap<>(placed);
                                expandedPlaced.putAll(trial.placed);
                                int remaining = unsupportedWithScaffold(client, nearby, expanded, cleared,
                                        expandedReady, expandedPlaced);
                                if (remaining == 0) return trial;
                                if (remaining < fewestRemaining) {
                                    best = trial;
                                    fewestRemaining = remaining;
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Scaffold scaffoldAnchor(MinecraftClient client, Cell first, Collection<Cell> nearby,
                                           Map<BlockPos, BlockState> layout, Map<BlockPos, BlockState> cleared,
                                           Map<BlockPos, BlockState> ready, Map<BlockPos, String> placed,
                                           List<String> prerequisites, String suffix, int room) {
        var current = new HashMap<>(cleared);
        current.putAll(ready);
        var tried = new HashSet<BlockPos>();
        for (var cell : nearby) {
            if (horizontalDistance(first.pos, cell.pos) > 9 || Math.abs(cell.pos.getY() - first.pos.getY()) > 4
                    || cell.desired.getBlock() instanceof FallingBlock
                    || !cell.desired.isFullCube(client.world, cell.pos)) continue;
            for (var face : ProjectPlacement.faces(cell.desired)) {
                var top = cell.pos.offset(face.getOpposite());
                if (!tried.add(top)) continue;
                var base = top;
                int maxHeight = Math.min(4, room / 3);
                while (top.getY() - base.getY() < maxHeight && loaded(client, base)
                        && airAt(client, base, layout) && airAt(client, base, current)
                        && !BuildProtection.blocked(base)) {
                    base = base.down();
                }
                if (!loaded(client, base) || !current.getOrDefault(base, client.world.getBlockState(base))
                        .isFullCube(client.world, base)) continue;
                var blocks = new LinkedHashMap<BlockPos, BlockState>();
                for (var pos = base.up(); pos.getY() <= top.getY(); pos = pos.up()) blocks.put(pos, SCAFFOLD);
                if (blocks.isEmpty()) continue;
                var trial = planScaffoldBlocks(client, blocks, layout, cleared, ready, placed, prerequisites, suffix);
                if (trial == null) continue;
                var expanded = new HashMap<>(layout);
                expanded.putAll(trial.layout);
                var expandedReady = new HashMap<>(ready);
                expandedReady.putAll(trial.layout);
                var expandedPlaced = new HashMap<>(placed);
                expandedPlaced.putAll(trial.placed);
                if (buildPose(client, cell, expanded, expandedReady, cleared, expandedPlaced, true) != null) return trial;
            }
        }
        return null;
    }

    private static long horizontalDistance(BlockPos first, BlockPos other) {
        int dx = first.getX() - other.getX();
        int dz = first.getZ() - other.getZ();
        return (long) dx * dx + (long) dz * dz;
    }

    private static int unsupportedWithScaffold(MinecraftClient client, Collection<Cell> pending,
                                                Map<BlockPos, BlockState> layout, Map<BlockPos, BlockState> cleared,
                                                Map<BlockPos, BlockState> ready, Map<BlockPos, String> placed) {
        var remaining = new ArrayList<>(pending);
        while (!remaining.isEmpty()) {
            int before = remaining.size();
            for (var it = remaining.iterator(); it.hasNext(); ) {
                var cell = it.next();
                if (buildPose(client, cell, layout, ready, cleared, placed, true) == null) continue;
                ready.put(cell.pos, cell.desired);
                placed.put(cell.pos, "simulated-placement");
                it.remove();
            }
            if (remaining.size() == before) break;
        }
        return remaining.size();
    }

    private static Scaffold planScaffold(MinecraftClient client, BlockPos top, Direction ascent, int rise,
                                         Map<BlockPos, BlockState> layout, Map<BlockPos, BlockState> cleared,
                                         Map<BlockPos, BlockState> current, Map<BlockPos, BlockState> ready,
                                         Map<BlockPos, String> placed, List<String> prerequisites, String suffix) {
        var scaffold = new LinkedHashMap<BlockPos, BlockState>();
        int baseY = top.getY() - rise + 1;
        for (int height = 1; height <= rise; height++) {
            var column = new BlockPos(top.getX(), baseY, top.getZ()).offset(ascent.getOpposite(), rise - height);
            if (!safeStand(client, column, current) || !safeStand(client, column, layout)) return null;
            for (int layer = 0; layer < height; layer++) {
                var pos = column.up(layer);
                if (!loaded(client, pos) || BuildProtection.blocked(pos)
                        || !airAt(client, pos, layout) || !airAt(client, pos, current)) return null;
                scaffold.put(pos, SCAFFOLD);
            }
        }
        return planScaffoldBlocks(client, scaffold, layout, cleared, ready, placed, prerequisites, suffix);
    }

    private static Scaffold planScaffoldBlocks(MinecraftClient client, Map<BlockPos, BlockState> scaffold,
                                               Map<BlockPos, BlockState> layout, Map<BlockPos, BlockState> cleared,
                                               Map<BlockPos, BlockState> ready, Map<BlockPos, String> placedBefore,
                                               List<String> prerequisites, String suffix) {
        var finalLayout = new HashMap<>(layout);
        finalLayout.putAll(scaffold);
        var built = new HashMap<>(ready);
        var placed = new LinkedHashMap<>(placedBefore);
        var anchors = new LinkedHashMap<BlockPos, String>();
        var jobs = new ArrayList<ProjectQueue.Work>();
        var placements = new ArrayList<ProjectQueue.Work>();
        List<String> next = prerequisites;
        for (var pos : scaffold.keySet()) {
            var pose = buildPose(client, new Cell(pos, SCAFFOLD, Blocks.AIR.getDefaultState()), finalLayout, built, cleared, placed, false);
            if (pose == null) return null;
            int index = placements.size();
            String check = "check-access-" + suffix + index;
            jobs.add(work(check, ProjectQueue.Kind.VERIFY, pos, Blocks.AIR.getDefaultState(), pose.stand, Direction.UP, next));
            String id = "place-access-" + suffix + index;
            var placement = work(id, ProjectQueue.Kind.BUILD, pos, SCAFFOLD, pose.stand, pose.face, List.of(check));
            placements.add(placement);
            jobs.add(placement);
            built.put(pos, SCAFFOLD);
            placed.put(pos, id);
            anchors.put(pos, id);
            next = List.of(id);
        }

        var reverse = new ArrayList<>(scaffold.keySet());
        Collections.reverse(reverse);
        var steps = new ArrayList<ScaffoldStep>();
        for (var pos : reverse) {
            var existing = new HashMap<>(cleared);
            existing.putAll(built);
            var simulated = new HashMap<>(layout);
            simulated.putAll(built);
            var stand = findPlacementStand(client, pos, null, feet ->
                    safeStand(client, feet, existing) && safeStand(client, feet, simulated)
                            && canRemoveBlock(client, feet, pos, existing)
                            && canRemoveBlock(client, feet, pos, simulated)
                            && (safeStand(client, feet, cleared)
                            || builtAccess(client, feet, pos, cleared, existing, simulated, placed) != null));
            if (stand == null) return null;
            built.remove(pos);
            steps.add(new ScaffoldStep(pos, placements.get(placements.size() - 1 - steps.size()), stand));
        }
        Collections.reverse(steps);
        return new Scaffold(jobs, steps, scaffold, anchors);
    }

    private static String appendScaffoldCleanup(List<ProjectQueue.Work> jobs, Scaffold scaffold,
                                                List<String> settledWork, String previousCleanup) {
        var other = List.copyOf(settledWork);
        String settled = previousCleanup == null ? scaffold.lastPlacement() : previousCleanup;
        for (int i = scaffold.blocks.size() - 1; i >= 0; i--) {
            var step = scaffold.blocks.get(i);
            String id = "remove-" + step.placement.id().substring("place-".length());
            var removal = work(id, ProjectQueue.Kind.EXCAVATE, step.block, SCAFFOLD,
                    step.removalStand, Direction.UP, List.of(step.placement.id()));
            jobs.add(new ProjectQueue.Work(id, removal.kind(), removal.payload(), removal.needs(), removal.locks(),
                    null, new ProjectQueue.Cleanup(ProjectBlock.key(step.block), settled, other)));
            settled = id;
        }
        return settled;
    }

    private static BlockState placementState(Cell cell, Map<BlockPos, BlockState> ready) {
        var partner = ProjectPlacement.chestPartner(cell.pos, cell.desired);
        if (partner == null) return cell.desired;
        var single = ProjectPlacement.singleChest(cell.desired);
        var other = ready.get(partner);
        if (single.equals(other)) return cell.desired;
        return other == null && cell.desired.get(ChestBlock.CHEST_TYPE) == ChestType.LEFT ? single : null;
    }

    private static BuildPose buildPose(MinecraftClient client, Cell cell, Map<BlockPos, BlockState> desired,
                                       Map<BlockPos, BlockState> ready, Map<BlockPos, BlockState> cleared,
                                       Map<BlockPos, String> placed, boolean avoidCorners) {
        var placement = placementState(cell, ready);
        if (placement == null) return null;
        for (Direction face : ProjectPlacement.faces(placement)) {
            var support = cell.pos.offset(face.getOpposite());
            if (!loaded(client, support)) continue;
            var state = desired.containsKey(support) ? ready.get(support) : client.world.getBlockState(support);
            if (state == null || !ProjectPlacement.support(state, placement, face, client.world, support)) continue;
            Predicate<BlockPos> visible = pos -> clearBuildFace(client, pos, cell.pos, support, face, desired, placement, avoidCorners);
            var stand = findPlacementStand(client, cell.pos, face, pos ->
                    !desired.containsKey(pos) && !desired.containsKey(pos.up()) && !desired.containsKey(pos.down())
                            && safeStand(client, pos, Map.of()) && visible.test(pos));
            if (stand == null) {
                stand = findPlacementStand(client, cell.pos, face, pos ->
                        safeStand(client, pos, cleared) && safeStand(client, pos, desired) && visible.test(pos));
            }
            if (stand != null) return new BuildPose(stand, face, support, false);
            var current = new HashMap<>(cleared);
            current.putAll(ready);
            stand = findPlacementStand(client, cell.pos, face, pos ->
                    safeStand(client, pos, cleared) && safeStand(client, pos, desired)
                            && clearBuildFace(client, pos, cell.pos, support, face, current, placement, avoidCorners));
            // Later placements wait until a face that will be covered has been used.
            if (stand != null) return new BuildPose(stand, face, support, true);
            for (boolean exposed : new boolean[]{false, true}) {
                var visibleLayout = exposed ? current : desired;
                var accessRoutes = new HashMap<BlockPos, BuildAccess>();
                stand = findPlacementStand(client, cell.pos, face, pos -> {
                    if (!placed.containsKey(pos.down()) || !safeStand(client, pos, current)
                            || !safeStand(client, pos, visibleLayout)
                            || !clearBuildFace(client, pos, cell.pos, support, face, visibleLayout, placement, avoidCorners)) return false;
                    var access = builtAccess(client, pos, cell.pos, cleared, current, visibleLayout, placed);
                    if (access == null) return false;
                    accessRoutes.put(pos, access);
                    return true;
                });
                if (stand != null) return new BuildPose(stand, face, support, exposed, accessRoutes.get(stand));
            }
        }
        return null;
    }

    private static BlockPos findPlacementStand(MinecraftClient client, BlockPos target, Direction face,
                                                Predicate<BlockPos> suitable) {
        // Minecraft measures placement reach from the eyes, which can reach upward from lower floors.
        return findStand(client, target, face, true, new int[]{0, 1, -1, -2, -3}, suitable);
    }

    private static BuildAccess builtAccess(MinecraftClient client, BlockPos stand, BlockPos target,
                                           Map<BlockPos, BlockState> cleared, Map<BlockPos, BlockState> current,
                                           Map<BlockPos, BlockState> desired, Map<BlockPos, String> placed) {
        var visited = new HashSet<BlockPos>();
        var candidates = new ArrayDeque<AccessStep>();
        candidates.add(new AccessStep(stand, null, 0));
        visited.add(stand);
        while (!candidates.isEmpty()) {
            var candidate = candidates.removeFirst();
            var step = candidate.pos;
            if (safeStand(client, step, cleared)) {
                var needs = new LinkedHashSet<String>();
                var locks = new LinkedHashSet<String>();
                for (var node = candidate; node != null; node = node.previous) {
                    var route = node.pos;
                    var dependency = placed.get(route.down());
                    if (dependency != null) needs.add(dependency);
                    locks.add(ProjectBlock.key(route));
                    locks.add(ProjectBlock.key(route.up()));
                    locks.add(ProjectBlock.key(route.down()));
                }
                return new BuildAccess(needs, locks);
            }
            if (candidate.distance >= 16) continue;
            for (var direction : Direction.Type.HORIZONTAL) for (int dy : new int[]{0, -1, 1}) {
                var next = step.offset(direction).up(dy);
                if (visited.contains(next) || next.equals(target) || next.up().equals(target)
                        || !loaded(client, next) || !loaded(client, next.up()) || !loaded(client, next.down())
                        || !safeStand(client, next, current) || !safeStand(client, next, desired)) continue;
                if (dy != 0) {
                    var clearance = (dy < 0 ? next : step).up(2);
                    if (!airAt(client, clearance, current) || !airAt(client, clearance, desired)) continue;
                }
                visited.add(next);
                candidates.addLast(new AccessStep(next, candidate, candidate.distance + 1));
            }
        }
        return null;
    }

    private static boolean airAt(MinecraftClient client, BlockPos pos, Map<BlockPos, BlockState> layout) {
        return layout.getOrDefault(pos, client.world.getBlockState(pos)).isAir();
    }

    private static TemporarySupport temporarySupport(MinecraftClient client, Cell cell, Map<BlockPos, BlockState> layout,
                                                       Map<BlockPos, BlockState> cleared) {
        if (cell.desired.getBlock() instanceof FallingBlock
                || !cell.desired.isFullCube(client.world, cell.pos)) return null;
        for (var face : ProjectPlacement.faces(cell.desired)) {
            var support = cell.pos.offset(face.getOpposite());
            var floorPos = support.down();
            if (!loaded(client, support) || !loaded(client, floorPos) || BuildProtection.blocked(support)) continue;
            var planned = layout.get(support);
            if ((planned != null && !planned.isAir()) || !cleared.getOrDefault(support, client.world.getBlockState(support)).isAir()) continue;
            var floor = client.world.getBlockState(floorPos);
            if (floor.hasBlockEntity() || !floor.isFullCube(client.world, floorPos)
                    || !cleared.getOrDefault(floorPos, floor).equals(floor)
                    || !layout.getOrDefault(floorPos, floor).equals(floor)) continue;
            var simulated = new HashMap<>(layout);
            simulated.put(support, Blocks.COBBLESTONE.getDefaultState());
            var supportStand = findStand(client, support, Direction.UP, true,
                    pos -> safeStand(client, pos, cleared) && safeStand(client, pos, simulated)
                            && clearBuildFace(client, pos, support, floorPos, Direction.UP, simulated)
                            && canRemoveBlock(client, pos, support, simulated));
            if (supportStand == null) continue;
            var pillarStand = findStand(client, cell.pos, face, true,
                    pos -> safeStand(client, pos, cleared) && safeStand(client, pos, simulated)
                            && clearBuildFace(client, pos, cell.pos, support, face, simulated));
            if (pillarStand != null) return new TemporarySupport(new BuildPose(pillarStand, face, support, false), supportStand);
        }
        return null;
    }

    private static boolean clearBuildFace(MinecraftClient client, BlockPos stand, BlockPos target,
                                          BlockPos support, Direction face, Map<BlockPos, BlockState> desired) {
        return clearBuildFace(client, stand, target, support, face, desired,
                desired.getOrDefault(target, Blocks.AIR.getDefaultState()), false);
    }

    private static boolean clearBuildFace(MinecraftClient client, BlockPos stand, BlockPos target,
                                          BlockPos support, Direction face, Map<BlockPos, BlockState> desired,
                                          BlockState placement, boolean avoidCorners) {
        var eye = Vec3d.ofBottomCenter(stand).add(0, 1.62, 0);
        var state = desired.getOrDefault(support, client.world.getBlockState(support));
        var point = ProjectPlacement.facePoint(support, face, state, placement);
        if (!hitsFace(client, eye, point, target, support, face, desired)) return false;
        if (!avoidCorners) return true;
        // A ray that only slips past the exact corner of a block is too fragile in game.
        for (double dx : new double[]{-.08, .08}) for (double dz : new double[]{-.08, .08}) {
            if (!hitsFace(client, eye.add(dx, 0, dz), point, target, support, face, desired)) return false;
        }
        return true;
    }

    private static boolean hitsFace(MinecraftClient client, Vec3d eye, Vec3d point, BlockPos target,
                                    BlockPos support, Direction face, Map<BlockPos, BlockState> layout) {
        var hit = plannedRay(client, eye, point, layout, target);
        return hit != null && hit.getBlockPos().equals(support) && hit.getSide() == face;
    }

    private static boolean canRemoveBlock(MinecraftClient client, BlockPos stand, BlockPos support,
                                            Map<BlockPos, BlockState> layout) {
        var eye = Vec3d.ofBottomCenter(stand).add(0, 1.62, 0);
        var hit = plannedRay(client, eye, Vec3d.ofCenter(support), layout, null);
        return hit != null && hit.getBlockPos().equals(support);
    }

    private static BlockHitResult plannedRay(MinecraftClient client, Vec3d eye, Vec3d point,
                                            Map<BlockPos, BlockState> layout, BlockPos ignoredBlock) {
        if (eye.squaredDistanceTo(point) > 4.4 * 4.4) return null;
        return BlockView.raycast(eye, point, client.world, (world, pos) -> {
            if (pos.equals(ignoredBlock)) return null;
            var state = layout.getOrDefault(pos, world.getBlockState(pos));
            return state.getOutlineShape(world, pos).raycast(eye, point, pos);
        }, ignored -> null);
    }

    private static ProjectQueue.Work work(String id, ProjectQueue.Kind kind, BlockPos target, BlockState state,
                                          BlockPos stand, Direction face, List<String> needs) {
        var blockId = Registries.BLOCK.getId(state.getBlock());
        var properties = ProjectBlock.properties(state);
        var location = new ProjectBlock(target, stand, blockId, properties, face);
        var payload = new ProjectBlock.Payload(position(target), position(stand), blockId.toString(),
                properties.isEmpty() ? null : properties, face == Direction.UP ? null : face.getName());
        return location.reserve(new ProjectQueue.Work(id, kind, JSON.toJson(payload), needs, Set.of()));
    }

    private static ProjectBlock.Position position(BlockPos pos) {
        return new ProjectBlock.Position(pos.getX(), pos.getY(), pos.getZ());
    }

    static BlockPos chooseStand(MinecraftClient client, BlockPos target, Map<BlockPos, ?> cells) {
        return chooseStand(client, target, cells, null, ignored -> true);
    }

    static BlockPos chooseStand(MinecraftClient client, BlockPos target, Map<BlockPos, ?> cells, Direction face, Predicate<BlockPos> suitable) {
        return findStand(client, target, face, face != null, pos ->
                !cells.containsKey(pos) && !cells.containsKey(pos.up()) && !cells.containsKey(pos.down())
                        && safeStand(client, pos, Map.of()) && suitable.test(pos));
    }

    private static BlockPos inspectionStand(MinecraftClient client, List<Cell> chunk, Map<BlockPos, BlockState> desired) {
        for (Cell cell : chunk) {
            var stand = verificationStand(client, cell.pos, desired);
            if (stand != null) return stand;
        }
        // A chunk of empty space high above the ground can be inspected from its surface.
        var anchor = chunk.get(0).pos;
        for (int y = anchor.getY() - 4; y > client.world.getBottomY(); y--) {
            var stand = verificationStand(client, anchor.withY(y), desired);
            if (stand != null) return stand;
        }
        return null;
    }

    private static BlockPos verificationStand(MinecraftClient client, BlockPos target, Map<BlockPos, BlockState> desired) {
        int[] heights = {-1, -2, -3, 0, 1, 2, 3};
        var exterior = findStand(client, target, null, true, heights, pos ->
                !desired.containsKey(pos) && !desired.containsKey(pos.up()) && !desired.containsKey(pos.down())
                        && safeStand(client, pos, Map.of()));
        return exterior != null ? exterior
                : findStand(client, target, null, true, heights, pos -> safeStand(client, pos, desired));
    }

    private static BlockPos findStand(MinecraftClient client, BlockPos target, Direction face,
                                      boolean allowDiagonal, Predicate<BlockPos> suitable) {
        return findStand(client, target, face, allowDiagonal, new int[]{0, 1, -1}, suitable);
    }

    private static BlockPos findStand(MinecraftClient client, BlockPos target, Direction face,
                                      boolean allowDiagonal, int[] heights, Predicate<BlockPos> suitable) {
        for (int dy : heights) for (int distance : new int[]{2, 1, 3}) {
            for (var direction : Direction.Type.HORIZONTAL) {
                if (face != null && face.getAxis().isHorizontal() && direction != face) continue;
                for (int lateral : allowDiagonal ? new int[]{0, 1, -1, 2, -2} : new int[]{0}) {
                    var stand = target.offset(direction, distance).offset(direction.rotateYClockwise(), lateral).up(dy);
                    if (stand.getSquaredDistance(target) <= 16 && !stand.up().equals(target)
                            && loaded(client, stand) && loaded(client, stand.up()) && loaded(client, stand.down())
                            && suitable.test(stand)) return stand;
                }
            }
        }
        return null;
    }

    private static boolean safeStand(MinecraftClient client, BlockPos stand, Map<BlockPos, BlockState> layout) {
        var world = client.world;
        var below = stand.down();
        var above = stand.up();
        var floor = layout.getOrDefault(below, world.getBlockState(below));
        return floor.isFullCube(world, below) && floor.getFluidState().isEmpty()
                && airAt(client, stand, layout) && airAt(client, above, layout);
    }

    private static boolean loaded(MinecraftClient client, BlockPos pos) {
        return client.world.isInBuildLimit(pos) && client.world.getWorldBorder().contains(pos)
                && client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }
}
