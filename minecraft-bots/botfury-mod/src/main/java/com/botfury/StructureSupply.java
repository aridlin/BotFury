package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
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

    private static final class SupplyLane {
        final String prefix;
        final List<MaterialRecipe.Batch> batches;
        final Deque<String> pickups = new ArrayDeque<>();
        final List<String> consumers = new ArrayList<>();
        int index;

        SupplyLane(String prefix, List<MaterialRecipe.Batch> batches) {
            this.prefix = prefix;
            this.batches = batches;
        }
    }

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
        var lanes = new HashMap<Identifier, SupplyLane>();
        var recipes = new MaterialRecipe(client, true, true);
        int required = plan.jobs().size();
        for (var entry : new TreeMap<>(plan.materials()).entrySet()) {
            Identifier item = entry.getKey();
            boolean fillWater = item.equals(mc("water_bucket"));
            // Leave room for tools, fuel and ingredients in the worker's inventory.
            int capacity = fillWater ? ProjectWater.MAX_BUCKETS : 16 * Registries.ITEM.get(item).getMaxCount();
            var batches = recipes.batches(fillWater ? mc("bucket") : item, entry.getValue(), capacity);
            for (var batch : batches) required += batch.plan().size() + (fillWater ? 3 : 2);
            lanes.put(item, new SupplyLane("supply-" + lanes.size() + "-", batches));
        }
        if (required > ProjectQueue.MAX_JOBS) {
            throw new IllegalArgumentException("Supplied build needs more than " + ProjectQueue.MAX_JOBS + " jobs");
        }
        int workers = Math.max(1, (int) BotTracker.targets().stream()
                .filter(bot -> bot.fresh() && bot.connected() && bot.alive()).count());
        int extra = ProjectQueue.MAX_JOBS - required;
        var jobs = new ArrayList<ProjectQueue.Work>();
        for (var job : plan.jobs()) {
            if (job.kind() != ProjectQueue.Kind.BUILD) {
                jobs.add(job);
                continue;
            }
            var block = ProjectBlock.parse(job.payload());
            var item = Registries.ITEM.getId(ProjectPlacement.material(block.desired()));
            var lane = lanes.get(item);
            if (lane.pickups.isEmpty()) {
                var batch = lane.batches.get(lane.index);
                int receivers = Math.min(batch.count(), Math.min(workers, extra + 1));
                extra -= receivers - 1;
                String prefix = lane.prefix + lane.index++ + "-";
                // Reuse the same chest stock only after the preceding consumers placed their blocks.
                var production = batch.plan().jobs(prefix + "produce-", protectedArea, table, furnace,
                        List.copyOf(lane.consumers));
                lane.consumers.clear();
                jobs.addAll(production);
                String previous = production.get(production.size() - 1).id();
                if (item.equals(mc("water_bucket"))) {
                    var spec = MaterialRecipe.stationPayload(water);
                    spec.addProperty("count", batch.count());
                    String payload = JSON.toJson(spec);
                    String id = prefix + "fill";
                    jobs.add(ProjectWater.Fill.parse(payload).reserve(new ProjectQueue.Work(
                            id, ProjectQueue.Kind.FILL_BUCKETS, payload, List.of(previous), Set.of(), previous)));
                    previous = id;
                }
                String store = prefix + "store";
                jobs.add(transfer(store, selected, storage, item, batch.count(), ProjectChest.Direction.DEPOSIT, previous));
                for (int receiver = 0; receiver < receivers; receiver++) {
                    int quantity = batch.count() / receivers + (receiver < batch.count() % receivers ? 1 : 0);
                    String pickup = prefix + "take-" + receiver;
                    jobs.add(transfer(pickup, selected, storage, item, quantity, ProjectChest.Direction.TAKE, store));
                    for (int i = 0; i < quantity; i++) lane.pickups.addLast(pickup);
                }
            }
            String supplier = lane.pickups.removeFirst();
            lane.consumers.add(job.id());
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
