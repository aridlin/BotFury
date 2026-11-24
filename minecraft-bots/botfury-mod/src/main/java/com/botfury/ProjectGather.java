package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

record ProjectGather(String resource, String variant, ProjectItem item, int count, Area exclude) {
    static final int MAX_COUNT = 576;
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
                || payload.count == null || payload.count < 1 || payload.count > MAX_COUNT) {
            throw new IllegalArgumentException("Provide mining resource, variant, item and count (1–576)");
        }
        if (!payload.resource.equals("dropped")) ResourceCatalog.blocks(payload.resource, payload.variant);
        var item = ProjectItem.parse(payload.item);
        String resource = payload.resource;
        String flower = resource.equals("dropped") && !item.grouped() ? MaterialRecipe.flowerSource(item.ids().get(0)) : null;
        if (flower != null) resource = flower;
        return new ProjectGather(resource, payload.variant, item, payload.count, payload.exclude == null ? null : payload.exclude.checked());
    }

    int inventoryCount(MinecraftClient client) {
        return item.count(client);
    }

    boolean fromDrops() { return resource.equals("dropped"); }

    MiningTask.Job mining(int port) { return new MiningTask.Job(resource, variant, port); }
}
