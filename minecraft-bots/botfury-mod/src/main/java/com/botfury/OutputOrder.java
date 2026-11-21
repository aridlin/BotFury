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
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class OutputOrder {
    private static final Gson JSON = new Gson();
    private static final Identifier CHEST = Identifier.of("minecraft", "chest");
    private static final Identifier TABLE = Identifier.of("minecraft", "crafting_table");
    private static final String ITEM = "minecraft:diamond_block";
    record Plan(List<ProjectQueue.Work> jobs, ProjectQueue.Repeat repeat) {}
    private OutputOrder() {}

    static int submit(MinecraftClient client, int batch) {
        var here = LocalFormation.here();
        int port = BotFuryMod.controlPort();
        if (here == null || client.world == null || port < 1024 || Boolean.getBoolean("botfury.managedHttp")) return 0;
        try {
            if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
                throw new IllegalArgumentException("Look at a single chest");
            }
            BlockPos chest = hit.getBlockPos();
            var state = client.world.getBlockState(chest);
            if (!state.isOf(Blocks.CHEST) || state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                throw new IllegalArgumentException("Look at a single chest");
            }
            var chestStand = StructureProject.chooseStand(client, chest, Map.of());
            if (chestStand == null) throw new IllegalArgumentException("No clear stand next to the chest");
            ProjectBlock table = findTable(client, chest);
            if (table == null) throw new IllegalArgumentException("Place a reachable crafting table near the chest");
            var plan = plan(new ProjectBlock(chest, chestStand, CHEST), table, batch);
            String id = "diamonds-" + UUID.randomUUID().toString().substring(0, 8);
            ProjectBroker.submitOutput(id, here, plan);
            int workers = BotTracker.sendSelected("project:worker " + port);
            ClientActions.message(client, "Queued " + id + " for batches of " + batch + " diamond blocks (" + workers + " bots requested)");
            return 1;
        } catch (IOException | IllegalArgumentException error) {
            ClientActions.message(client, "Could not queue output order: " + error.getMessage());
            return 0;
        }
    }

    static int cancel(MinecraftClient client) {
        var here = LocalFormation.here();
        if (here == null || client.world == null || Boolean.getBoolean("botfury.managedHttp")
                || !(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return 0;
        try {
            boolean cancelled = ProjectBroker.cancelOutput(here, ProjectBlock.key(hit.getBlockPos()));
            ClientActions.message(client, cancelled ? "Output order cancelled" : "No output order at this block");
            return cancelled ? 1 : 0;
        } catch (IOException error) {
            ClientActions.message(client, "Could not save output cancellation");
            return 0;
        }
    }

    private static ProjectBlock findTable(MinecraftClient client, BlockPos chest) {
        for (var position : BlockPos.iterateOutwards(chest, 12, 3, 12)) {
            if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)
                    || !client.world.getBlockState(position).isOf(Blocks.CRAFTING_TABLE)) continue;
            var stand = StructureProject.chooseStand(client, position, Map.of());
            if (stand != null) return new ProjectBlock(position.toImmutable(), stand, TABLE);
        }
        return null;
    }

    static Plan plan(ProjectBlock chest, ProjectBlock table, int batch) {
        if (batch < 1 || batch > 64) throw new IllegalArgumentException("Batch must be between 1 and 64");
        var gather = new ProjectQueue.Work("gather", ProjectQueue.Kind.GATHER, JSON.toJson(Map.of(
                "resource", "diamond", "variant", "both", "item", "minecraft:diamond", "count", batch * 9)),
                List.of(), Set.of());
        var crafting = payload(table);
        crafting.addProperty("recipe", ITEM);
        crafting.addProperty("count", batch);
        var inspect = payload(chest);
        inspect.addProperty("direction", "INSPECT");
        var deposit = payload(chest);
        deposit.addProperty("direction", "DEPOSIT");
        deposit.addProperty("item", ITEM);
        deposit.addProperty("stock", 0);
        var jobs = List.of(gather,
                station("craft", ProjectQueue.Kind.CRAFT, table, crafting, "gather"),
                station("inspect", ProjectQueue.Kind.TRANSFER, chest, inspect, "craft"),
                station("deposit", ProjectQueue.Kind.TRANSFER, chest, deposit, "inspect"));
        return new Plan(jobs, new ProjectQueue.Repeat(batch, ITEM, ProjectBlock.key(chest.target()), 0, null, false));
    }

    private static ProjectQueue.Work station(String id, ProjectQueue.Kind kind, ProjectBlock location,
                                             JsonObject payload, String after) {
        return location.reserve(new ProjectQueue.Work(id, kind, JSON.toJson(payload), List.of(after), Set.of(), after));
    }

    private static JsonObject payload(ProjectBlock location) {
        var json = new JsonObject();
        json.add("target", position(location.target()));
        json.add("stand", position(location.stand()));
        json.addProperty("block", location.block().toString());
        return json;
    }

    private static JsonObject position(BlockPos pos) {
        return JSON.toJsonTree(Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ())).getAsJsonObject();
    }
}
