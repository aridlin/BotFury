package com.botfury;

import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

final class ProjectInventory {
    private static final int TERRAIN_STACKS_TO_KEEP = 2;
    private static final Set<Identifier> EXCESS_TERRAIN = Set.of(Identifier.of("minecraft", "cobblestone"),
            Identifier.of("minecraft", "cobbled_deepslate"), Identifier.of("minecraft", "andesite"),
            Identifier.of("minecraft", "diorite"), Identifier.of("minecraft", "granite"), Identifier.of("minecraft", "tuff"));

    private ProjectInventory() {}

    static boolean canFit(MinecraftClient client, ItemStack wanted, int count) {
        var inventory = client.player.getInventory();
        if (inventory.getEmptySlot() >= 0) return true;
        int remaining = count;
        for (int i = 0; i < 36 && remaining > 0; i++) {
            var stack = inventory.getStack(i);
            if (ItemStack.canCombine(stack, wanted)) remaining -= stack.getMaxCount() - stack.getCount();
        }
        return remaining <= 0;
    }

    static boolean releaseSurplus(MinecraftClient client, ScreenHandler screen, Predicate<ItemStack> reserved,
                                  String activity) {
        var player = client.player;
        if (player.currentScreenHandler != screen || !screen.getCursorStack().isEmpty()) return false;
        var inventory = player.getInventory();
        Slot surplus = null;
        for (var slot : screen.slots) {
            if (slot.inventory != inventory || slot.getIndex() >= 36 || !slot.canTakeItems(player)) continue;
            var stack = slot.getStack();
            if (!EXCESS_TERRAIN.contains(Registries.ITEM.getId(stack.getItem())) || stack.hasNbt() || reserved.test(stack)
                    || inventory.count(stack.getItem()) - stack.getCount() < TERRAIN_STACKS_TO_KEEP * stack.getMaxCount()) continue;
            if (surplus == null || stack.getCount() < surplus.getStack().getCount()) surplus = slot;
        }
        if (surplus == null) return false;
        var stack = surplus.getStack();
        BotEvents.add("info", "projects", "Making room for " + activity + " by dropping " + stack.getCount()
                + " " + Registries.ITEM.getId(stack.getItem()));
        client.interactionManager.clickSlot(screen.syncId, surplus.id, 1, SlotActionType.THROW, player);
        return true;
    }
}
