package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Blocks;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans one shared workbench before a project's table-bound production starts. */
record WorkbenchBootstrap(ProjectBlock station, ProjectGather.Area protectedArea, List<ProjectQueue.Work> jobs,
                          List<String> ready) {
    private static final Identifier ITEM = Identifier.of("minecraft", "crafting_table");
    private static final String PLACE = "setup-workbench";

    static WorkbenchBootstrap plan(MinecraftClient client, BlockPos center, ProjectGather.Area protectedArea) {
        var station = chooseSite(client, center, protectedArea);
        if (station == null) throw new IllegalArgumentException("No clear workbench site near the supply chest");
        var area = StructureSupply.union(protectedArea, station);
        var recipe = new MaterialRecipe(client, false, false).plan(ITEM, 1, false);
        var jobs = new ArrayList<>(recipe.jobs("setup-workbench-produce-", area, null, null));
        String last = jobs.get(jobs.size() - 1).id();
        var payload = MaterialRecipe.stationPayload(station).toString();
        jobs.add(station.reserve(new ProjectQueue.Work(PLACE, ProjectQueue.Kind.BUILD, payload,
                List.of(last), Set.of(), last)));
        return new WorkbenchBootstrap(station, area, List.copyOf(jobs), List.of(PLACE));
    }

    private static ProjectBlock chooseSite(MinecraftClient client, BlockPos center, ProjectGather.Area protectedArea) {
        var world = client.world;
        var bench = Blocks.CRAFTING_TABLE.getDefaultState();
        for (var pos : BlockPos.iterateOutwards(center, 8, 2, 8)) {
            if (protectedArea.contains(pos) || BuildProtection.blocked(pos) || !world.isInBuildLimit(pos)
                    || !world.getWorldBorder().contains(pos) || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || !world.getBlockState(pos).isAir() || !world.getBlockState(pos.up()).isAir()) continue;
            var floor = pos.down();
            var support = world.getBlockState(floor);
            if (!ProjectPlacement.support(support, bench, Direction.UP, world, floor)) continue;
            var click = ProjectPlacement.facePoint(floor, Direction.UP, support, bench);
            var stand = StructureProject.chooseStand(client, pos, Map.of(), Direction.UP, candidate -> {
                if (protectedArea.contains(candidate) || BuildProtection.blocked(candidate)) return false;
                var eye = Vec3d.ofBottomCenter(candidate).add(0, 1.62, 0);
                if (eye.squaredDistanceTo(click) > 4.3 * 4.3) return false;
                var hit = world.raycast(new RaycastContext(eye, click, RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE, client.player));
                return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(floor) && hit.getSide() == Direction.UP;
            });
            if (stand != null) return new ProjectBlock(pos.toImmutable(), stand, ITEM);
        }
        return null;
    }
}
