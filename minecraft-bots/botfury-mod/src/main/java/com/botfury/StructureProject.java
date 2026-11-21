package com.botfury;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/** Compiles a small structure into leased excavation, placement and verification jobs. */
final class StructureProject {
    private static final Gson JSON = new Gson();
    private static final int LIMIT = 256;
    private static final Identifier AIR = Identifier.of("minecraft", "air");

    record Plan(List<ProjectQueue.Work> jobs, Map<Identifier, Integer> materials, ProjectGather.Area area) {}
    private record Cell(BlockPos pos, BlockState desired, BlockState observed) {}
    private record BuildPose(BlockPos stand, Direction face, BlockPos support, boolean exposedBeforeBuild) {}
    private record TemporarySupport(BuildPose pillar, BlockPos stand) {}

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
            if (!sameState(entry.getValue(), state) && !state.isAir()
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
        if (size.size() != 3 || palette.isEmpty() || blocks.isEmpty() || blocks.size() > LIMIT
                || !structure.getList("entities", NbtElement.COMPOUND_TYPE).isEmpty()) {
            throw new IllegalArgumentException("Expected a single-palette structure with at most 256 blocks and no entities");
        }
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.getCompound(i);
            var position = block.getList("pos", NbtElement.INT_TYPE);
            int index = block.getInt("state");
            if (block.contains("nbt") || !block.contains("state", NbtElement.INT_TYPE)
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
            var blockState = supportedBlock(state.getString("Name"), properties);
            var pos = absolute(origin, position.getInt(0), position.getInt(1), position.getInt(2));
            if (result.put(pos, blockState) != null) throw new IllegalArgumentException("Duplicate structure position");
        }
        return result;
    }

    private static Map<BlockPos, BlockState> schematicCells(NbtCompound schematic, BlockPos origin) {
        var result = new HashMap<BlockPos, BlockState>();
        SpongeSchematic.blocks(schematic, LIMIT).forEach((pos, text) -> {
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
        if (!ProjectPlacement.supported(state)) throw new IllegalArgumentException("Unsupported structure block " + state);
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
        var cleanup = new ArrayList<ProjectQueue.Work>();
        var layout = new HashMap<>(desired);
        var origin = cells.get(0).pos;
        var area = new ProjectGather.Area(origin, origin);
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            area = StructureSupply.include(StructureSupply.include(area, cell.pos), cell.pos.down());
            if (sameState(cell.desired, cell.observed)) {
                ready.put(cell.pos, cell.desired);
                continue;
            }
            if (!cell.observed.isAir()) {
                obstructing.put(i, cell);
                removed.add(cell.pos);
            }
            if (cell.desired.isAir()) continue;
            if (!cell.desired.isFullCube(client.world, cell.pos)) {
                throw new IllegalArgumentException("Not a full cube: " + cell.desired);
            }
            pending.put(i, cell);
        }
        var cleared = new HashMap<BlockPos, BlockState>();
        List<String> excavationFrontier = List.of();
        while (!obstructing.isEmpty()) {
            var wave = new ArrayList<String>();
            var clearedThisWave = new ArrayList<BlockPos>();
            for (var iterator = obstructing.entrySet().iterator(); iterator.hasNext(); ) {
                var entry = iterator.next();
                var cell = entry.getValue();
                var stand = findStand(client, cell.pos, null, true, pos ->
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
        boolean allowTemporarySupport = false;
        while (!pending.isEmpty()) {
            int before = pending.size();
            for (var iterator = pending.entrySet().iterator(); iterator.hasNext(); ) {
                var entry = iterator.next();
                var cell = entry.getValue();
                var pose = buildPose(client, cell, layout, ready, cleared);
                var temporary = pose == null && allowTemporarySupport ? temporarySupport(client, cell, layout, cleared) : null;
                if (temporary != null) pose = temporary.pillar;
                if (pose == null) continue;
                var approachNeeds = new ArrayList<>(excavationFrontier);
                approachNeeds.addAll(exposedPlacements);
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
                ready.put(cell.pos, cell.desired);
                changes.add(id);
                jobs.add(work(id, ProjectQueue.Kind.BUILD, cell.pos, cell.desired, pose.stand, pose.face, List.copyOf(needs)));
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
                throw new IllegalArgumentException("No reachable support face for " + cell.desired + " at " + cell.pos.toShortString());
            }
        }
        jobs.addAll(cleanup);
        cleanup.forEach(job -> changes.add(job.id()));
        List<String> prerequisites = List.copyOf(changes);
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            var stand = chooseStand(client, cell.pos, desired);
            if (stand == null) stand = findStand(client, cell.pos, null, true, pos -> safeStand(client, pos, desired));
            if (stand == null) throw new IllegalArgumentException("No safe verification position near " + cell.pos.toShortString());
            jobs.add(work("verify-" + i, ProjectQueue.Kind.VERIFY, cell.pos, cell.desired, stand, Direction.UP, prerequisites));
            area = StructureSupply.union(area, new ProjectBlock(cell.pos, stand, AIR));
        }
        if (jobs.size() > LIMIT) throw new IllegalArgumentException("Structure needs more than 256 jobs");
        return new Plan(List.copyOf(jobs), Map.copyOf(materials), area);
    }

    private static BuildPose buildPose(MinecraftClient client, Cell cell, Map<BlockPos, BlockState> desired,
                                       Map<BlockPos, BlockState> ready, Map<BlockPos, BlockState> cleared) {
        for (Direction face : ProjectPlacement.faces(cell.desired)) {
            var support = cell.pos.offset(face.getOpposite());
            if (!loaded(client, support)) continue;
            var state = desired.containsKey(support) ? ready.get(support) : client.world.getBlockState(support);
            if (state == null || state.hasBlockEntity() || !state.isFullCube(client.world, support)) continue;
            Predicate<BlockPos> visible = pos -> clearBuildFace(client, pos, cell.pos, support, face, desired);
            var stand = chooseStand(client, cell.pos, desired, face, visible);
            if (stand == null) {
                stand = findStand(client, cell.pos, face, true, pos ->
                        safeStand(client, pos, cleared) && safeStand(client, pos, desired) && visible.test(pos));
            }
            // Ordinary blocks may have a face that is only exposed before neighboring placements.
            if (stand != null) return new BuildPose(stand, face, support, false);
            if (face == Direction.UP) {
                var current = new HashMap<>(cleared);
                current.putAll(ready);
                stand = findStand(client, cell.pos, face, true, pos ->
                        safeStand(client, pos, cleared) && safeStand(client, pos, desired)
                                && clearBuildFace(client, pos, cell.pos, support, face, current));
                // Later placements are gated on this one so they cannot close off the exposed face.
                if (stand != null) return new BuildPose(stand, face, support, true);
            }
        }
        return null;
    }

    private static TemporarySupport temporarySupport(MinecraftClient client, Cell cell, Map<BlockPos, BlockState> layout,
                                                       Map<BlockPos, BlockState> cleared) {
        if (ProjectPlacement.faces(cell.desired).contains(Direction.UP)) return null;
        for (var face : ProjectPlacement.faces(cell.desired)) {
            var support = cell.pos.offset(face.getOpposite());
            var floorPos = support.down();
            if (!loaded(client, support) || !loaded(client, floorPos)) continue;
            var planned = layout.get(support);
            if ((planned != null && !planned.isAir()) || !cleared.getOrDefault(support, client.world.getBlockState(support)).isAir()) continue;
            var floor = client.world.getBlockState(floorPos);
            if (floor.hasBlockEntity() || !floor.isFullCube(client.world, floorPos)
                    || !cleared.getOrDefault(floorPos, floor).equals(floor)
                    || !layout.getOrDefault(floorPos, floor).equals(floor)) continue;
            var simulated = new HashMap<>(layout);
            simulated.put(support, Blocks.COBBLESTONE.getDefaultState());
            var supportStand = chooseStand(client, support, simulated, Direction.UP,
                    pos -> clearBuildFace(client, pos, support, floorPos, Direction.UP, simulated)
                            && canRemoveBlock(client, pos, support, simulated));
            if (supportStand == null) continue;
            var pillarStand = chooseStand(client, cell.pos, simulated, face,
                    pos -> clearBuildFace(client, pos, cell.pos, support, face, simulated));
            if (pillarStand != null) return new TemporarySupport(new BuildPose(pillarStand, face, support, false), supportStand);
        }
        return null;
    }

    private static boolean clearBuildFace(MinecraftClient client, BlockPos stand, BlockPos target,
                                          BlockPos support, Direction face, Map<BlockPos, BlockState> desired) {
        var eye = Vec3d.ofBottomCenter(stand).add(0, 1.62, 0);
        var point = ProjectPlacement.facePoint(support, face);
        var hit = plannedRay(client, eye, point, desired, target);
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

    private static BlockPos findStand(MinecraftClient client, BlockPos target, Direction face,
                                      boolean allowDiagonal, Predicate<BlockPos> suitable) {
        for (int dy : new int[]{0, 1, -1}) for (int distance : new int[]{2, 1, 3}) {
            for (var direction : Direction.Type.HORIZONTAL) {
                if (face != null && face != Direction.UP && direction != face) continue;
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
                && layout.getOrDefault(stand, world.getBlockState(stand)).isAir()
                && layout.getOrDefault(above, world.getBlockState(above)).isAir();
    }

    private static boolean loaded(MinecraftClient client, BlockPos pos) {
        return client.world.isInBuildLimit(pos) && client.world.getWorldBorder().contains(pos)
                && client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }
}
