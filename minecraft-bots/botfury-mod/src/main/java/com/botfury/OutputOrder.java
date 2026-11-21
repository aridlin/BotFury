package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.*;

final class OutputOrder {
    private static final Gson JSON = new Gson();
    private static final Identifier CHEST = Identifier.of("minecraft", "chest");
    private static final int JOB_LIMIT = 256;
    record Plan(List<ProjectQueue.Work> jobs, ProjectQueue.Repeat repeat) {}
    private record Storage(BlockPos key, BlockPos other) {
        int slots() { return other == null ? 27 : 54; }
    }

    private OutputOrder() {}

    static int submit(MinecraftClient client, Identifier item, int batch) {
        return submit(client, item, batch, client.crosshairTarget);
    }

    static int submit(MinecraftClient client, Identifier item, int batch, HitResult aim) {
        var here = LocalFormation.here();
        int port = BotFuryMod.controlPort();
        if (here == null || client.world == null || port < 1024 || Boolean.getBoolean("botfury.managedHttp")) return 0;
        try {
            var storage = aimedStorage(client, aim);
            if (!Registries.ITEM.containsId(item) || item.equals(Identifier.of("minecraft", "air"))) {
                throw new IllegalArgumentException("Choose an item from the inventory picker or give a valid item ID");
            }
            var stackSize = Registries.ITEM.get(item).getMaxCount();
            if (batch < 1 || batch > 64 || batch > Math.min(storage.slots(), 36) * stackSize) {
                throw new IllegalArgumentException("Choose a smaller batch that fits in one worker's inventory and the chest");
            }
            var chestStand = StructureProject.chooseStand(client, storage.key, Map.of());
            if (chestStand == null) throw new IllegalArgumentException("No clear stand next to the chest");
            var chest = new ProjectBlock(storage.key, chestStand, CHEST);
            var table = findStation(client, storage.key, Identifier.of("minecraft", "crafting_table"));
            var furnace = findStation(client, storage.key, Identifier.of("minecraft", "furnace"));
            var steps = new OutputRecipe(client, table != null, furnace != null).plan(item);
            int selected = (int) BotTracker.targets().stream()
                    .filter(bot -> bot.fresh() && bot.connected() && bot.alive()).count();
            var plan = plan(storage, chest, table, furnace, item, batch, stackSize, Math.max(1, selected), steps);
            String id = "output-" + UUID.randomUUID().toString().substring(0, 8);
            ProjectBroker.submitOutput(id, here, plan);
            int workers = BotTracker.sendSelected("project:worker " + port);
            String method = steps.get(item).kind() == ProjectQueue.Kind.GATHER
                    && "dropped".equals(steps.get(item).resource()) ? " (carried or dropped items)" : "";
            ClientActions.message(client, "Queued " + id + " for batches of " + batch + " " + item
                    + method + " · " + workers + " bots requested");
            return 1;
        } catch (IOException | IllegalArgumentException error) {
            ClientActions.message(client, "Could not queue output order: " + error.getMessage());
            return 0;
        }
    }

    static int cancel(MinecraftClient client) { return cancel(client, client.crosshairTarget); }

    static int cancel(MinecraftClient client, HitResult aim) {
        var here = LocalFormation.here();
        if (here == null || client.world == null || Boolean.getBoolean("botfury.managedHttp")) return 0;
        try {
            var storage = aimedStorage(client, aim);
            boolean cancelled = ProjectBroker.cancelOutput(here, ProjectBlock.key(storage.key));
            ClientActions.message(client, cancelled ? "Output order cancelled" : "No output order at this chest");
            return cancelled ? 1 : 0;
        } catch (IOException | IllegalArgumentException error) {
            ClientActions.message(client, "Could not cancel output order: " + error.getMessage());
            return 0;
        }
    }

    private static Storage aimedStorage(MinecraftClient client, HitResult aim) {
        if (!(aim instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            throw new IllegalArgumentException("Look at a chest");
        }
        var pos = hit.getBlockPos();
        var state = client.world.getBlockState(pos);
        if (!state.isOf(Blocks.CHEST)) throw new IllegalArgumentException("Look at a chest");
        var type = state.get(ChestBlock.CHEST_TYPE);
        if (type == ChestType.SINGLE) return new Storage(pos, null);
        var next = pos.offset(ChestBlock.getFacing(state));
        var other = client.world.getBlockState(next);
        if (!other.isOf(Blocks.CHEST) || other.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE
                || !next.offset(ChestBlock.getFacing(other)).equals(pos)) {
            throw new IllegalArgumentException("Load both halves of the double chest");
        }
        return type == ChestType.LEFT ? new Storage(pos, next) : new Storage(next, pos);
    }

    private static ProjectBlock findStation(MinecraftClient client, BlockPos chest, Identifier id) {
        var block = Registries.BLOCK.get(id);
        for (var position : BlockPos.iterateOutwards(chest, 12, 3, 12)) {
            if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)
                    || !client.world.getBlockState(position).isOf(block)) continue;
            var stand = StructureProject.chooseStand(client, position, Map.of());
            if (stand != null) return new ProjectBlock(position.toImmutable(), stand, id);
        }
        return null;
    }

    private static Plan plan(Storage storage, ProjectBlock chest, ProjectBlock table, ProjectBlock furnace,
                             Identifier item, int batch, int stackSize, int workers, Map<Identifier, OutputRecipe.Step> steps) {
        var demand = new HashMap<Identifier, Integer>();
        demand.put(item, batch);
        var ordered = new ArrayList<>(steps.entrySet());
        var reversed = new ArrayList<>(ordered);
        Collections.reverse(reversed);
        for (var entry : reversed) {
            int count = demand.getOrDefault(entry.getKey(), 0);
            var step = entry.getValue();
            int min = (count + step.yield() - 1) / step.yield();
            if (count > (step.kind() == ProjectQueue.Kind.GATHER ? 576 : 256)) {
                throw new IllegalArgumentException("This recipe needs a smaller output batch");
            }
            step.ingredients().forEach((ingredient, quantity) -> demand.merge(ingredient, quantity * min, Integer::sum));
            if (step.cookTime() > 0) {
                demand.merge(OutputRecipe.COAL, (int) (((long) step.cookTime() * min + 1599) / 1600), Integer::sum);
            }
        }
        int capacity = stackSize * storage.slots();
        int lanes = Math.min(workers, Math.min(Math.max(1, capacity / batch), JOB_LIMIT / (steps.size() + 2)));
        if (lanes == 0) throw new IllegalArgumentException("This recipe requires too many jobs");
        var jobs = new ArrayList<ProjectQueue.Work>();
        var protectedArea = StructureSupply.union(new ProjectGather.Area(chest.target(), chest.target()), chest, table, furnace);
        for (int lane = 0; lane < lanes; lane++) {
            String prefix = "lane-" + lane + "-";
            String previous = null;
            int stage = 0;
            for (var entry : ordered) {
                var id = entry.getKey();
                var step = entry.getValue();
                int quantity = demand.getOrDefault(id, 0);
                if (quantity == 0) continue;
                String name = prefix + "produce-" + stage++;
                JsonObject payload;
                ProjectBlock station = null;
                if (step.kind() == ProjectQueue.Kind.GATHER) {
                    payload = new JsonObject();
                    payload.addProperty("resource", step.resource());
                    payload.addProperty("variant", "both");
                    payload.addProperty("item", id.toString());
                    payload.add("exclude", JSON.toJsonTree(Map.of("min", StructureSupply.position(protectedArea.min()),
                            "max", StructureSupply.position(protectedArea.max()))));
                } else {
                    station = step.kind() == ProjectQueue.Kind.CRAFT ? table : furnace;
                    payload = payload(station);
                    payload.addProperty("recipe", step.recipe().toString());
                    if (step.cookTime() > 0) {
                        payload.addProperty("input", step.ingredients().keySet().iterator().next().toString());
                        payload.addProperty("fuel", OutputRecipe.COAL.toString());
                    }
                }
                payload.addProperty("count", quantity);
                jobs.add(work(name, step.kind(), station, payload, previous));
                previous = name;
            }
            var inspect = payload(chest);
            inspect.addProperty("direction", "INSPECT");
            inspect.addProperty("item", item.toString());
            var deposit = payload(chest);
            deposit.addProperty("direction", "DEPOSIT");
            deposit.addProperty("item", item.toString());
            deposit.addProperty("stock", 0);
            if (storage.other != null) {
                inspect.add("partner", JSON.toJsonTree(StructureSupply.position(storage.other)));
                deposit.add("partner", JSON.toJsonTree(StructureSupply.position(storage.other)));
            }
            for (var transfer : List.of(work(prefix + "inspect", ProjectQueue.Kind.TRANSFER, null, inspect, previous),
                    work(prefix + "deposit", ProjectQueue.Kind.TRANSFER, null, deposit, prefix + "inspect"))) {
                jobs.add(ProjectChest.parse(transfer.payload()).reserve(transfer));
            }
        }
        return new Plan(List.copyOf(jobs), new ProjectQueue.Repeat(batch, item.toString(),
                ProjectBlock.key(storage.key), 0, null, false, capacity));
    }

    private static ProjectQueue.Work work(String id, ProjectQueue.Kind kind, ProjectBlock station,
                                          JsonObject payload, String after) {
        var work = new ProjectQueue.Work(id, kind, JSON.toJson(payload), after == null ? List.of() : List.of(after), Set.of(), after);
        return station == null ? work : station.reserve(work);
    }

    private static JsonObject payload(ProjectBlock location) {
        return JSON.toJsonTree(new ProjectBlock.Payload(StructureSupply.position(location.target()),
                StructureSupply.position(location.stand()), location.block().toString())).getAsJsonObject();
    }
}
