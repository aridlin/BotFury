package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
    private record Source(String resource, String variant, String raw, String smelted, String smeltRecipe,
                          String craftRecipe, int inputPerCraft, int outputPerCraft) {}
    private static final Map<String, Source> SOURCES = Map.ofEntries(
            Map.entry("cobblestone", new Source("stone", "normal", "cobblestone", null, null, null, 1, 1)),
            Map.entry("stone", new Source("stone", "normal", "cobblestone", "stone", "stone", null, 1, 1)),
            Map.entry("stone_bricks", new Source("stone", "normal", "cobblestone", "stone", "stone", "stone_bricks", 4, 4)),
            Map.entry("diamond_block", new Source("diamond", "both", "diamond", null, null, "diamond_block", 9, 1)),
            Map.entry("iron_block", new Source("iron", "both", "raw_iron", "iron_ingot", "iron_ingot_from_smelting_raw_iron", "iron_block", 9, 1)),
            Map.entry("copper_block", new Source("copper", "both", "raw_copper", "copper_ingot", "copper_ingot_from_smelting_raw_copper", "copper_block", 9, 1)),
            Map.entry("oak_planks", new Source("block:minecraft:oak_log", "normal", "oak_log", null, null, "oak_planks", 1, 4)),
            Map.entry("spruce_planks", new Source("block:minecraft:spruce_log", "normal", "spruce_log", null, null, "spruce_planks", 1, 4)));

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
        int material = 0;
        for (var entry : new TreeMap<>(plan.materials()).entrySet()) {
            Identifier item = entry.getKey();
            Source source = item.getNamespace().equals("minecraft") ? SOURCES.get(item.getPath()) : null;
            if (source == null) throw new IllegalArgumentException("No automatic source for " + item);
            String prefix = "supply-" + material++ + "-";
            int count = entry.getValue();
            int batches = (count + source.outputPerCraft - 1) / source.outputPerCraft;
            int required = batches * source.inputPerCraft;
            if (required > 576 || source.smelted != null && required > 24) {
                throw new IllegalArgumentException("Split this build into smaller material batches for " + item);
            }
            String previous = prefix + "gather";
            jobs.add(gather(previous, source.resource, source.variant, source.raw, required, protectedArea, null));
            if (source.smelted != null) {
                if (furnace == null) throw new IllegalArgumentException("Place a reachable furnace near the supply chest");
                jobs.add(gather(prefix + "fuel", "coal", "both", "coal", (required + 7) / 8, protectedArea, previous));
                previous = prefix + "fuel";
                var spec = stationPayload(furnace);
                spec.addProperty("recipe", mc(source.smeltRecipe).toString());
                spec.addProperty("input", mc(source.raw).toString());
                spec.addProperty("fuel", "minecraft:coal");
                spec.addProperty("count", required);
                jobs.add(station(prefix + "smelt", ProjectQueue.Kind.SMELT, furnace, spec, previous, true));
                previous = prefix + "smelt";
            }
            if (source.craftRecipe != null) {
                if (table == null) throw new IllegalArgumentException("Place a reachable crafting table near the supply chest");
                var spec = stationPayload(table);
                spec.addProperty("recipe", mc(source.craftRecipe).toString());
                spec.addProperty("count", count);
                jobs.add(station(prefix + "craft", ProjectQueue.Kind.CRAFT, table, spec, previous, true));
                previous = prefix + "craft";
            }
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

    private static ProjectQueue.Work gather(String id, String resource, String variant, String raw, int count,
                                            ProjectGather.Area exclude, String after) {
        var spec = new JsonObject();
        spec.addProperty("resource", resource);
        spec.addProperty("variant", variant);
        spec.addProperty("item", mc(raw).toString());
        spec.addProperty("count", count);
        spec.add("exclude", JSON.toJsonTree(Map.of("min", position(exclude.min()), "max", position(exclude.max()))));
        return new ProjectQueue.Work(id, ProjectQueue.Kind.GATHER, JSON.toJson(spec),
                after == null ? List.of() : List.of(after), Set.of(), after);
    }

    private static ProjectQueue.Work transfer(String id, ProjectBlock chest, Identifier item, int count,
                                              String direction, String after, boolean sameWorker) {
        var spec = stationPayload(chest);
        spec.addProperty("direction", direction);
        spec.addProperty("item", item.toString());
        spec.addProperty("stock", count);
        return station(id, ProjectQueue.Kind.TRANSFER, chest, spec, after, sameWorker);
    }

    private static ProjectQueue.Work station(String id, ProjectQueue.Kind kind, ProjectBlock location,
                                             JsonObject payload, String after, boolean sameWorker) {
        return location.reserve(new ProjectQueue.Work(id, kind, JSON.toJson(payload), List.of(after),
                Set.of(), sameWorker ? after : null));
    }

    private static JsonObject stationPayload(ProjectBlock block) {
        return JSON.toJsonTree(new ProjectBlock.Payload(position(block.target()), position(block.stand()),
                block.block().toString())).getAsJsonObject();
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
