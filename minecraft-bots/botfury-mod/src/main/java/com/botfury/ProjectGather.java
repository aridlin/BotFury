package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

record ProjectGather(String resource, String variant, Identifier item, int count) {
    private record Payload(String resource, String variant, String item, Integer count) {}

    static ProjectGather parse(String json) {
        var payload = new Gson().fromJson(json, Payload.class);
        if (payload == null || payload.resource == null || payload.variant == null || payload.item == null
                || payload.count == null || payload.count < 1 || payload.count > 576) {
            throw new IllegalArgumentException("Provide mining resource, variant, item and count (1–576)");
        }
        ResourceCatalog.blocks(payload.resource, payload.variant);
        Identifier item = Identifier.tryParse(payload.item);
        if (item == null || !Registries.ITEM.containsId(item) || item.equals(Identifier.of("minecraft", "air"))) {
            throw new IllegalArgumentException("Unknown gathering item");
        }
        return new ProjectGather(payload.resource, payload.variant, item, payload.count);
    }

    int inventoryCount(MinecraftClient client) {
        Item expected = Registries.ITEM.get(item);
        int total = 0;
        for (int i = 0; i < 36; i++) {
            var stack = client.player.getInventory().getStack(i);
            if (stack.isOf(expected)) total += stack.getCount();
        }
        return total;
    }

    MiningTask.Job mining(int port) { return new MiningTask.Job(resource, variant, port); }
}
