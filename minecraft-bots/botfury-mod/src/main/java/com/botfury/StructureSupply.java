package com.botfury;

import com.google.gson.Gson;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Generates material jobs before the build jobs that consume them. */
final class StructureSupply {
    private static final Gson JSON = new Gson();
    record Supplied(List<ProjectQueue.Work> jobs, String chest) {}

    private StructureSupply() {}

    static Supplied compile(MinecraftClient client, StructureProject.Plan plan) {
        if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            throw new IllegalArgumentException("Look at a dedicated single supply chest");
        }
        BlockPos chest = hit.getBlockPos();
        var state = client.world.getBlockState(chest);
        if (!state.isOf(Blocks.CHEST) || state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            throw new IllegalArgumentException("Look at a dedicated single supply chest");
        }
        if (plan.area().contains(chest)) throw new IllegalArgumentException("Supply chest must be outside the work area");
        var chestStand = StructureProject.chooseStand(client, chest, Map.of());
        if (chestStand == null || plan.area().contains(chestStand)) {
            throw new IllegalArgumentException("Supply chest needs a standing position outside the work area");
        }
        var storage = new ProjectBlock(chest, chestStand, mc("chest"));
        var furnace = findStation(client, chest, mc("furnace"), plan.area());
        var table = findStation(client, chest, mc("crafting_table"), plan.area());
        var protectedArea = union(plan.area(), storage, furnace, table);
        var jobs = new ArrayList<ProjectQueue.Work>();
        var take = new HashMap<Identifier, String>();
        var recipes = new MaterialRecipe(client, true, true);
        int material = 0;
        for (var entry : new TreeMap<>(plan.materials()).entrySet()) {
            Identifier item = entry.getKey();
            String prefix = "supply-" + material++ + "-";
            int count = entry.getValue();
            var production = recipes.plan(item, count, false).jobs(prefix + "produce-", protectedArea, table, furnace);
            jobs.addAll(production);
            String previous = production.get(production.size() - 1).id();
            jobs.add(transfer(prefix + "store", storage, item, count, "DEPOSIT", previous, true));
            jobs.add(transfer(prefix + "take", storage, item, count, "TAKE", prefix + "store", false));
            take.put(item, prefix + "take");
        }
        for (var job : plan.jobs()) {
            if (job.kind() != ProjectQueue.Kind.BUILD) {
                jobs.add(job);
                continue;
            }
            var block = ProjectBlock.parse(job.payload());
            var item = net.minecraft.registry.Registries.ITEM.getId(
                    net.minecraft.registry.Registries.BLOCK.get(block.block()).asItem());
            String supplier = take.get(item);
            var needs = new ArrayList<>(job.needs());
            needs.add(supplier);
            jobs.add(new ProjectQueue.Work(job.id(), job.kind(), job.payload(), needs, job.locks(), supplier));
        }
        if (jobs.size() > 256) throw new IllegalArgumentException("Supplied build needs more than 256 jobs");
        return new Supplied(List.copyOf(jobs), ProjectBlock.key(chest));
    }

    private static ProjectQueue.Work transfer(String id, ProjectBlock chest, Identifier item, int count,
                                              String direction, String after, boolean sameWorker) {
        var spec = MaterialRecipe.stationPayload(chest);
        spec.addProperty("direction", direction);
        spec.addProperty("item", item.toString());
        spec.addProperty("stock", count);
        return chest.reserve(new ProjectQueue.Work(id, ProjectQueue.Kind.TRANSFER, JSON.toJson(spec),
                List.of(after), Set.of(), sameWorker ? after : null));
    }

    static ProjectBlock.Position position(BlockPos pos) {
        return new ProjectBlock.Position(pos.getX(), pos.getY(), pos.getZ());
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
        var min = area.min();
        var max = area.max();
        for (var station : stations) {
            if (station == null) continue;
            for (var pos : List.of(station.target(), station.stand().down(), station.stand().up())) {
                min = new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
                max = new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
            }
        }
        return new ProjectGather.Area(min, max);
    }

    private static Identifier mc(String path) { return Identifier.of("minecraft", path); }
}
