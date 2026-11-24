package com.botfury;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;

record ProjectItem(String key, List<Identifier> ids) {
    static ProjectItem parse(String key) {
        if ("any_logs".equals(key)) {
            return new ProjectItem(key, ResourceCatalog.blocks(key, "both").stream().map(Identifier::new).toList());
        }
        if (ResourceCatalog.FURNACE_STONE.equals(key)) {
            return new ProjectItem(key, ResourceCatalog.FURNACE_STONE_DROPS.stream().map(Identifier::new).toList());
        }
        var id = key == null ? null : Identifier.tryParse(key);
        if (id == null || !Registries.ITEM.containsId(id) || id.equals(Identifier.of("minecraft", "air"))) {
            throw new IllegalArgumentException("Unknown project item " + key);
        }
        return new ProjectItem(id.toString(), List.of(id));
    }

    boolean grouped() { return ids.size() > 1; }
    Item sample() { return Registries.ITEM.get(ids.get(0)); }
    boolean matches(ItemStack stack) {
        return !stack.isEmpty() && ids.contains(Registries.ITEM.getId(stack.getItem()));
    }
    int count(Map<String, Integer> stock) {
        return ids.stream().mapToInt(id -> stock.getOrDefault(id.toString(), 0)).sum();
    }
    @Override public String toString() { return key; }
}
