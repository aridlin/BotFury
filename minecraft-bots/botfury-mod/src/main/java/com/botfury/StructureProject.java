package com.botfury;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Compiles a small structure into leased excavation, placement and verification jobs. */
final class StructureProject {
    private static final Gson JSON = new Gson();
    private static final int LIMIT = 256;
    private static final Identifier AIR = Identifier.of("minecraft", "air");

    record Plan(List<ProjectQueue.Work> jobs, Map<Identifier, Integer> materials, ProjectGather.Area area) {}
    private record Cell(BlockPos pos, Identifier desired, BlockState observed, BlockPos stand) {}

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
            var id = Registries.BLOCK.getId(state.getBlock());
            if (!entry.getValue().equals(id) && !state.isAir()
                    && (state.hasBlockEntity() || !state.getFluidState().isEmpty() || state.getHardness(client.world, pos) < 0)) {
                throw new IllegalArgumentException("Protected block or fluid at " + pos.toShortString());
            }
            var stand = chooseStand(client, pos, desired);
            if (stand == null) throw new IllegalArgumentException("No safe working position near " + pos.toShortString());
            cells.add(new Cell(pos, entry.getValue(), state, stand));
        }
        cells.sort(Comparator.comparingInt((Cell cell) -> cell.pos.getY())
                .thenComparingInt(cell -> cell.pos.getX()).thenComparingInt(cell -> cell.pos.getZ()));
        return compile(client, cells, desired);
    }

    private static Map<BlockPos, Identifier> cells(NbtCompound structure, BlockPos origin) {
        NbtList size = structure.getList("size", NbtElement.INT_TYPE);
        NbtList palette = structure.getList("palette", NbtElement.COMPOUND_TYPE);
        NbtList blocks = structure.getList("blocks", NbtElement.COMPOUND_TYPE);
        if (size.size() != 3 || palette.isEmpty() || blocks.isEmpty() || blocks.size() > LIMIT
                || !structure.getList("entities", NbtElement.COMPOUND_TYPE).isEmpty()) {
            throw new IllegalArgumentException("Expected a single-palette structure with at most 256 blocks and no entities");
        }
        Map<BlockPos, Identifier> result = new HashMap<>();
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
            var id = supportedBlock(state.getString("Name"), !state.getCompound("Properties").isEmpty());
            var pos = absolute(origin, position.getInt(0), position.getInt(1), position.getInt(2));
            if (result.put(pos, id) != null) throw new IllegalArgumentException("Duplicate structure position");
        }
        return result;
    }

    private static Map<BlockPos, Identifier> schematicCells(NbtCompound schematic, BlockPos origin) {
        var result = new HashMap<BlockPos, Identifier>();
        SpongeSchematic.blocks(schematic, LIMIT).forEach((pos, state) -> {
            var id = supportedBlock(state, state.contains("["));
            result.put(absolute(origin, pos.getX(), pos.getY(), pos.getZ()), id);
        });
        return result;
    }

    private static Identifier supportedBlock(String state, boolean hasProperties) {
        var id = Identifier.tryParse(state);
        if (id == null || !Registries.BLOCK.containsId(id) || hasProperties
                || !id.equals(AIR) && !ProjectPlacement.supported(Registries.BLOCK.get(id).getDefaultState())) {
            throw new IllegalArgumentException("Unsupported structure block " + state);
        }
        return id;
    }

    private static BlockPos absolute(BlockPos origin, int x, int y, int z) {
        return new ProjectBlock.Position(Math.addExact(origin.getX(), x), Math.addExact(origin.getY(), y),
                Math.addExact(origin.getZ(), z)).checked();
    }

    private static Plan compile(MinecraftClient client, List<Cell> cells, Map<BlockPos, Identifier> desired) {
        var jobs = new ArrayList<ProjectQueue.Work>();
        var changes = new ArrayList<String>();
        var placed = new HashMap<BlockPos, String>();
        var excavation = new HashMap<BlockPos, String>();
        var materials = new TreeMap<Identifier, Integer>();
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            var current = Registries.BLOCK.getId(cell.observed.getBlock());
            if (!current.equals(cell.desired) && !cell.observed.isAir()) {
                String id = "clear-" + i;
                excavation.put(cell.pos, id);
                changes.add(id);
                jobs.add(work(id, ProjectQueue.Kind.EXCAVATE, cell, current, List.of()));
            }
        }
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            var wanted = cell.desired;
            if (wanted.equals(AIR) || wanted.equals(Registries.BLOCK.getId(cell.observed.getBlock()))) continue;
            var state = Registries.BLOCK.get(wanted).getDefaultState();
            if (!state.isFullCube(client.world, cell.pos)) {
                throw new IllegalArgumentException("Not a full cube: " + wanted);
            }
            var below = cell.pos.down();
            var support = desired.get(below);
            String predecessor = placed.get(below);
            if (AIR.equals(support) || predecessor == null && (!loaded(client, below)
                    || !client.world.getBlockState(below).isFullCube(client.world, below)
                    || client.world.getBlockState(below).hasBlockEntity())) {
                throw new IllegalArgumentException("No solid support below " + cell.pos.toShortString());
            }
            var needs = new ArrayList<String>();
            if (excavation.containsKey(cell.pos)) needs.add(excavation.get(cell.pos));
            if (predecessor != null) needs.add(predecessor);
            String id = "place-" + i;
            placed.put(cell.pos, id);
            changes.add(id);
            jobs.add(work(id, ProjectQueue.Kind.BUILD, cell, wanted, needs));
            materials.merge(Registries.ITEM.getId(state.getBlock().asItem()), 1, Integer::sum);
        }
        List<String> prerequisites = List.copyOf(changes);
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            jobs.add(work("verify-" + i, ProjectQueue.Kind.VERIFY, cell, cell.desired, prerequisites));
        }
        if (jobs.size() > LIMIT) throw new IllegalArgumentException("Structure needs more than 256 jobs");
        var origin = cells.get(0).pos;
        var area = new ProjectGather.Area(origin, origin);
        for (var cell : cells) {
            area = StructureSupply.union(area, new ProjectBlock(cell.pos.down(), cell.stand, cell.desired),
                    new ProjectBlock(cell.pos, cell.stand, cell.desired));
        }
        return new Plan(List.copyOf(jobs), Map.copyOf(materials), area);
    }

    private static ProjectQueue.Work work(String id, ProjectQueue.Kind kind, Cell cell,
                                          Identifier block, List<String> needs) {
        var location = new ProjectBlock(cell.pos, cell.stand, block);
        var payload = new ProjectBlock.Payload(position(cell.pos), position(cell.stand), block.toString());
        return location.reserve(new ProjectQueue.Work(id, kind, JSON.toJson(payload), needs, Set.of()));
    }

    private static ProjectBlock.Position position(BlockPos pos) {
        return new ProjectBlock.Position(pos.getX(), pos.getY(), pos.getZ());
    }

    static BlockPos chooseStand(MinecraftClient client, BlockPos target, Map<BlockPos, Identifier> cells) {
        // Keep stands and their supports outside the planned footprint at every height.
        for (int dy : new int[]{0, 1, -1}) for (int distance : new int[]{2, 1, 3}) {
            for (var direction : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                var stand = target.offset(direction, distance).up(dy);
                if (!loaded(client, stand) || cells.containsKey(stand) || cells.containsKey(stand.up())
                        || cells.containsKey(stand.down())) continue;
                var floor = client.world.getBlockState(stand.down());
                if (floor.isFullCube(client.world, stand.down()) && floor.getFluidState().isEmpty()
                        && client.world.getBlockState(stand).isAir() && client.world.getBlockState(stand.up()).isAir()) {
                    return stand;
                }
            }
        }
        return null;
    }

    private static boolean loaded(MinecraftClient client, BlockPos pos) {
        return client.world.isInBuildLimit(pos) && client.world.getWorldBorder().contains(pos)
                && client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }
}
