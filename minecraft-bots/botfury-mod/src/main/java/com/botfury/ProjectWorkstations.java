package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sets up only the shared stations required by the selected production plans. */
record ProjectWorkstations(ProjectBlock table, ProjectBlock furnace, ProjectGather.Area protectedArea,
                           List<ProjectQueue.Work> jobs, boolean buildsTable, boolean buildsFurnace) {
    private static final Identifier TABLE = Identifier.of("minecraft", "crafting_table");
    private static final Identifier FURNACE = Identifier.of("minecraft", "furnace");
    private static final Identifier FOOTING = Identifier.of("minecraft", "dirt");

    static ProjectWorkstations prepare(MinecraftClient client, BlockPos center, ProjectGather.Area area,
                                       ProjectBlock table, ProjectBlock furnace, boolean needTable, boolean needFurnace) throws IOException {
        boolean placeFurnace = needFurnace && furnace == null;
        boolean placeTable = table == null && (needTable || placeFurnace);
        if (placeTable) {
            table = chooseSite(client, center, area, TABLE);
            area = StructureSupply.union(area, table);
        }
        if (placeFurnace) {
            furnace = chooseSite(client, center, area, FURNACE);
            area = StructureSupply.union(area, furnace);
        }
        var jobs = new ArrayList<ProjectQueue.Work>();
        var tableReady = List.<String>of();
        if (placeTable) tableReady = List.of(addStation(client, table, area, null, List.of(), jobs, "setup-workbench"));
        if (placeFurnace) addStation(client, furnace, area, table, tableReady, jobs, "setup-furnace");
        return new ProjectWorkstations(table, furnace, area, List.copyOf(jobs), placeTable, placeFurnace);
    }

    List<String> prerequisites(MaterialRecipe.Plan plan) {
        var needs = new ArrayList<String>(2);
        if (buildsTable && plan.needsTable()) needs.add("setup-workbench");
        if (buildsFurnace && plan.needsFurnace()) needs.add("setup-furnace");
        return List.copyOf(needs);
    }

    List<ProjectQueue.Work> storageJobs(MinecraftClient client, ProjectBlock storage) {
        var steps = new ArrayList<ProjectQueue.Work>();
        addStation(client, storage, protectedArea, table, buildsTable ? List.of("setup-workbench") : List.of(),
                steps, "setup-storage");
        return List.copyOf(steps);
    }

    private static String addStation(MinecraftClient client, ProjectBlock station, ProjectGather.Area area,
                                     ProjectBlock table, List<String> needs, List<ProjectQueue.Work> jobs, String id) {
        var recipe = new MaterialRecipe(client, table != null, false).plan(station.block(), 1, false);
        var production = recipe.jobs(id + "-produce-", area, table, null, needs);
        jobs.addAll(production);
        String last = production.get(production.size() - 1).id();
        String payload = MaterialRecipe.stationPayload(station).toString();
        jobs.add(station.reserve(new ProjectQueue.Work(id, ProjectQueue.Kind.BUILD, payload,
                List.of(last), Set.of(), last)));
        return id;
    }

    static ProjectBlock chooseSite(MinecraftClient client, BlockPos center, ProjectGather.Area protectedArea,
                                   Identifier station) throws IOException {
        var reservedStorage = ProjectBroker.activeStorageKeys();
        var world = client.world;
        var desired = Registries.BLOCK.get(station).getDefaultState();
        for (var pos : BlockPos.iterateOutwards(center, 8, 2, 8)) {
            if (protectedArea.contains(pos) || BuildProtection.blocked(pos)
                    || reservedStorage.contains(ProjectBlock.key(pos)) || !world.isInBuildLimit(pos)
                    || !world.getWorldBorder().contains(pos) || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || !world.getBlockState(pos).isAir() || !world.getBlockState(pos.up()).isAir()
                    || !world.doesNotIntersectEntities(null, VoxelShapes.fullCube().offset(pos.getX(), pos.getY(), pos.getZ()))) continue;
            var floor = pos.down();
            var support = world.getBlockState(floor);
            if (!ProjectPlacement.support(support, desired, Direction.UP, world, floor)) continue;
            var click = ProjectPlacement.facePoint(floor, Direction.UP, support, desired);
            var stand = StructureProject.chooseStand(client, pos, Map.of(), Direction.UP, candidate -> {
                if (protectedArea.contains(candidate) || BuildProtection.blocked(candidate)
                        || reservedStorage.contains(ProjectBlock.key(candidate))
                        || !world.doesNotIntersectEntities(null, VoxelShapes.fullCube()
                                .offset(candidate.getX(), candidate.getY(), candidate.getZ()))) return false;
                var eye = Vec3d.ofBottomCenter(candidate).add(0, 1.62, 0);
                if (eye.squaredDistanceTo(click) > 4.3 * 4.3) return false;
                var hit = world.raycast(new RaycastContext(eye, click, RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE, client.player));
                return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(floor) && hit.getSide() == Direction.UP;
            });
            if (stand != null) return new ProjectBlock(pos.toImmutable(), stand, station);
        }
        throw new IllegalArgumentException("No clear site for " + station + " near the supply chest");
    }

    /** Returns null when the original station cannot be restored without altering existing blocks. */
    static List<ProjectQueue.Work> repairFooting(MinecraftClient client, String world, ProjectQueue.OwnedStation owner,
                                                 ProjectBlock station) {
        var level = client.world;
        var floor = station.support();
        if (ProjectPlacement.support(level.getBlockState(floor), station.desired(), station.face(), level, floor)) {
            return List.of();
        }
        if (station.face() != Direction.UP) return null;
        var footing = Registries.BLOCK.get(FOOTING).getDefaultState();
        var column = new java.util.LinkedHashMap<BlockPos, net.minecraft.block.BlockState>();
        column.put(station.target(), station.desired());
        var anchor = floor;
        while (column.size() <= 3 && level.isInBuildLimit(anchor) && level.isAir(anchor)
                && !BuildProtection.blocked(world, anchor, owner.project())) {
            column.put(anchor, footing);
            anchor = anchor.down();
        }
        if (anchor.equals(floor) || !ProjectPlacement.support(level.getBlockState(anchor), footing, Direction.UP, level, anchor)
                || !ProjectPlacement.support(footing, station.desired(), Direction.UP, level, floor)) return null;

        var area = StructureSupply.union(new ProjectGather.Area(anchor, station.target()), station);
        var placements = new ArrayList<ProjectBlock>();
        for (var pos = anchor.up(); pos.getY() <= floor.getY(); pos = pos.up()) {
            var stand = StructureProject.chooseStand(client, pos, column, Direction.UP,
                    candidate -> !BuildProtection.blocked(world, candidate, owner.project()));
            if (stand == null) return null;
            var placement = new ProjectBlock(pos, stand, FOOTING);
            placements.add(placement);
            area = StructureSupply.union(area, placement);
        }
        String prefix = "footing-" + owner.job() + "-" + Long.toUnsignedString(System.nanoTime(), 36) + "-";
        var recipe = new MaterialRecipe(client, false, false).plan(FOOTING, placements.size(), false);
        var jobs = new ArrayList<>(recipe.jobs(prefix + "gather-", area, null, null));
        String previous = jobs.get(jobs.size() - 1).id();
        for (var placement : placements) {
            String id = prefix + jobs.size();
            jobs.add(placement.reserve(new ProjectQueue.Work(id, ProjectQueue.Kind.BUILD,
                    MaterialRecipe.stationPayload(placement).toString(), List.of(previous), Set.of(), previous)));
            previous = id;
        }
        return List.copyOf(jobs);
    }
}
