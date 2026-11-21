package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.util.*;

/** Generates material jobs before the build jobs that consume them. */
final class StructureSupply {
    private static final Gson JSON = new Gson();
    record Supplied(List<ProjectQueue.Work> jobs, String chest) {}

    private StructureSupply() {}

    static Supplied compile(MinecraftClient client, StructureProject.Plan plan) {
        var selected = ProjectChest.aimedStorage(client, client.crosshairTarget);
        var chest = selected.key();
        var area = plan.area();
        if (area.contains(chest) || selected.other() != null && area.contains(selected.other())) {
            throw new IllegalArgumentException("Supply chest must be outside the work area");
        }
        var storage = selected.approach(client, stand -> !area.contains(stand) && !area.contains(stand.down())
                && !area.contains(stand.up()));
        if (storage == null) throw new IllegalArgumentException("Supply chest needs a reachable stand outside the work area");
        var furnace = findStation(client, chest, mc("furnace"), area);
        var table = findStation(client, chest, mc("crafting_table"), area);
        var protectedArea = union(selected.include(area), storage, furnace, table);
        boolean needsWater = plan.materials().containsKey(mc("water_bucket"));
        var water = needsWater ? findWater(client, chest, protectedArea) : null;
        if (needsWater && water == null) throw new IllegalArgumentException("A supplied water build needs reachable renewable water near the supply chest");
        if (water != null) {
            protectedArea = union(protectedArea, water);
            for (var direction : Direction.Type.HORIZONTAL) {
                protectedArea = include(protectedArea, water.target().offset(direction));
            }
        }
        var jobs = new ArrayList<ProjectQueue.Work>();
        var materialPrefixes = new TreeMap<Identifier, String>();
        var recipes = new MaterialRecipe(client, true, true);
        int material = 0;
        for (var entry : new TreeMap<>(plan.materials()).entrySet()) {
            Identifier item = entry.getKey();
            String prefix = "supply-" + material++ + "-";
            int count = entry.getValue();
            boolean fillWater = item.equals(mc("water_bucket"));
            var production = recipes.plan(fillWater ? mc("bucket") : item, count, false)
                    .jobs(prefix + "produce-", protectedArea, table, furnace);
            jobs.addAll(production);
            String previous = production.get(production.size() - 1).id();
            if (fillWater) {
                var spec = MaterialRecipe.stationPayload(water);
                spec.addProperty("count", count);
                String payload = JSON.toJson(spec);
                String id = prefix + "fill";
                jobs.add(ProjectWater.Fill.parse(payload).reserve(new ProjectQueue.Work(
                        id, ProjectQueue.Kind.FILL_BUCKETS, payload, List.of(previous), Set.of(), previous)));
                previous = id;
            }
            jobs.add(transfer(prefix + "store", selected, storage, item, count, ProjectChest.Direction.DEPOSIT, previous));
            materialPrefixes.put(item, prefix);
        }

        int available = ProjectQueue.MAX_JOBS - jobs.size() - plan.jobs().size();
        if (available < materialPrefixes.size()) throw new IllegalArgumentException("Supplied build needs more than " + ProjectQueue.MAX_JOBS + " jobs");
        int workers = (int) BotTracker.targets().stream()
                .filter(bot -> bot.fresh() && bot.connected() && bot.alive()).count();
        var pickups = new HashMap<Identifier, Deque<String>>();
        int extra = available - materialPrefixes.size();
        for (var entry : materialPrefixes.entrySet()) {
            var item = entry.getKey();
            int count = plan.materials().get(item);
            int batches = Math.min(count, Math.min(Math.max(1, workers), extra + 1));
            extra -= batches - 1;
            var assignments = new ArrayDeque<String>();
            for (int batch = 0; batch < batches; batch++) {
                int quantity = count / batches + (batch < count % batches ? 1 : 0);
                String prefix = entry.getValue();
                String id = prefix + "take-" + batch;
                jobs.add(transfer(id, selected, storage, item, quantity, ProjectChest.Direction.TAKE, prefix + "store"));
                for (int i = 0; i < quantity; i++) assignments.addLast(id);
            }
            pickups.put(item, assignments);
        }
        for (var job : plan.jobs()) {
            if (job.kind() != ProjectQueue.Kind.BUILD) {
                jobs.add(job);
                continue;
            }
            var block = ProjectBlock.parse(job.payload());
            var item = net.minecraft.registry.Registries.ITEM.getId(
                    ProjectPlacement.material(block.desired()));
            String supplier = pickups.get(item).removeFirst();
            var needs = new ArrayList<>(job.needs());
            needs.add(supplier);
            jobs.add(new ProjectQueue.Work(job.id(), job.kind(), job.payload(), needs, job.locks(), supplier));
        }
        return new Supplied(List.copyOf(jobs), ProjectBlock.key(chest));
    }

    private static ProjectQueue.Work transfer(String id, ProjectChest.Storage storage, ProjectBlock chest, Identifier item, int count,
                                              ProjectChest.Direction direction, String after) {
        var spec = MaterialRecipe.stationPayload(chest);
        spec.addProperty("direction", direction.name());
        spec.addProperty("item", item.toString());
        spec.addProperty("stock", count);
        storage.addPartner(spec);
        var payload = JSON.toJson(spec);
        boolean deposit = direction == ProjectChest.Direction.DEPOSIT;
        return ProjectChest.parse(payload).reserve(new ProjectQueue.Work(id, ProjectQueue.Kind.TRANSFER, payload,
                List.of(after), Set.of(), deposit ? after : null, null, deposit ? null : item.toString()));
    }

    static ProjectBlock.Position position(BlockPos pos) {
        return new ProjectBlock.Position(pos.getX(), pos.getY(), pos.getZ());
    }

    private static ProjectBlock findWater(MinecraftClient client, BlockPos center, ProjectGather.Area area) {
        for (var pos : BlockPos.iterateOutwards(center, 12, 3, 12)) {
            if (area.contains(pos) || !client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || !ProjectWater.renewable(client.world, pos)) continue;
            var stand = StructureProject.chooseStand(client, pos, Map.of(), null, candidate ->
                    !area.contains(candidate) && client.world.raycast(new RaycastContext(
                            Vec3d.ofBottomCenter(candidate).add(0, 1.62, 0),
                            Vec3d.ofCenter(pos), RaycastContext.ShapeType.OUTLINE,
                            RaycastContext.FluidHandling.SOURCE_ONLY, client.player)).getBlockPos().equals(pos));
            if (stand != null) return new ProjectBlock(pos.toImmutable(), stand, mc("water"));
        }
        return null;
    }

    private static ProjectBlock findStation(MinecraftClient client, BlockPos center, Identifier block,
                                            ProjectGather.Area area) {
        var expected = net.minecraft.registry.Registries.BLOCK.get(block);
        for (var pos : BlockPos.iterateOutwards(center, 12, 3, 12)) {
            if (area.contains(pos) || !client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || !client.world.getBlockState(pos).isOf(expected)) continue;
            var stand = StructureProject.chooseStand(client, pos, Map.of());
            if (stand != null && !area.contains(stand)) return new ProjectBlock(pos.toImmutable(), stand, block);
        }
        return null;
    }

    static ProjectGather.Area union(ProjectGather.Area area, ProjectBlock... stations) {
        for (var station : stations) {
            if (station == null) continue;
            area = include(area, station.target());
            area = include(area, station.stand().down());
            area = include(area, station.stand().up());
        }
        return area;
    }

    static ProjectGather.Area include(ProjectGather.Area area, BlockPos pos) {
        var min = area.min();
        var max = area.max();
        return new ProjectGather.Area(
                new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ())),
                new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ())));
    }

    private static Identifier mc(String path) { return Identifier.of("minecraft", path); }
}
