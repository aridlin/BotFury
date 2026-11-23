package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

record ProjectGather(String resource, String variant, Identifier item, int count, Area exclude) {
    private record Payload(String resource, String variant, String item, Integer count, Bounds exclude) {}

    record Bounds(ProjectBlock.Position min, ProjectBlock.Position max) {
        Area checked() {
            if (min == null || max == null) throw new IllegalArgumentException("Invalid protected area");
            var a = min.checked();
            var b = max.checked();
            if (a.getX() > b.getX() || a.getY() > b.getY() || a.getZ() > b.getZ()) {
                throw new IllegalArgumentException("Invalid protected area");
            }
            return new Area(a, b);
        }
    }
    record Area(BlockPos min, BlockPos max) {
        boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                    && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }
    }

    static ProjectGather parse(String json) {
        var payload = new Gson().fromJson(json, Payload.class);
        if (payload == null || payload.resource == null || payload.variant == null || payload.item == null
                || payload.count == null || payload.count < 1 || payload.count > 576) {
            throw new IllegalArgumentException("Provide mining resource, variant, item and count (1–576)");
        }
        if (!payload.resource.equals("dropped")) ResourceCatalog.blocks(payload.resource, payload.variant);
        Identifier item = Identifier.tryParse(payload.item);
        if (item == null || !Registries.ITEM.containsId(item) || item.equals(Identifier.of("minecraft", "air"))) {
            throw new IllegalArgumentException("Unknown gathering item");
        }
        String resource = payload.resource;
        String flower = resource.equals("dropped") ? MaterialRecipe.flowerSource(item) : null;
        if (flower != null) resource = flower;
        return new ProjectGather(resource, payload.variant, item, payload.count, payload.exclude == null ? null : payload.exclude.checked());
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

    boolean fromDrops() { return resource.equals("dropped"); }

    MiningTask.Job mining(int port) { return new MiningTask.Job(resource, variant, port); }
}
