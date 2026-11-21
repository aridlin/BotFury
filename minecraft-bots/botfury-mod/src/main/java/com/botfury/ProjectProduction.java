package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeMatcher;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.time.Duration;

/** A workstation job with a bounded inventory target. Uses the server's own recipe and slot rules. */
final class ProjectProduction {
    record Spec(ProjectBlock station, ProjectQueue.Kind kind, Identifier recipe, Identifier input,
                Identifier fuel, int count) {}
    record Result(boolean success, String detail) {}
    private record Payload(String recipe, String input, String fuel, Integer count) {}
    private static final long SETTLE = Duration.ofMillis(750).toNanos();
    private final Spec spec;
    private ScreenHandler screen;
    private boolean opening;
    private long nextAction;

    ProjectProduction(Spec spec) { this.spec = spec; }

    static Spec parse(String json, ProjectQueue.Kind kind) {
        var station = ProjectBlock.parse(json);
        var payload = new Gson().fromJson(json, Payload.class);
        boolean smelt = kind == ProjectQueue.Kind.SMELT;
        if (!station.block().equals(Identifier.of("minecraft", smelt ? "furnace" : "crafting_table"))
                || payload.count == null || payload.count < 1 || payload.count > 256) {
            throw new IllegalArgumentException("Provide a matching workstation and output count (1–256)");
        }
        var recipe = payload.recipe == null ? null : Identifier.tryParse(payload.recipe);
        if (recipe == null) throw new IllegalArgumentException("Invalid recipe ID");
        return new Spec(station, kind, recipe, smelt ? item(payload.input) : null,
                smelt ? item(payload.fuel) : null, payload.count);
    }

    private static Identifier item(String name) {
        Identifier id = name == null ? null : Identifier.tryParse(name);
        if (id == null || !Registries.ITEM.containsId(id) || id.equals(Identifier.of("minecraft", "air"))) {
            throw new IllegalArgumentException("Invalid furnace input or fuel item");
        }
        return id;
    }

    void close(MinecraftClient client) {
        if (client.player != null && (screen != null && client.player.currentScreenHandler == screen
                || opening && matching(client.player.currentScreenHandler))) client.player.closeHandledScreen();
        screen = null;
        opening = false;
    }

    private boolean matching(ScreenHandler handler) {
        return spec.kind == ProjectQueue.Kind.CRAFT ? handler instanceof CraftingScreenHandler
                : handler instanceof FurnaceScreenHandler;
    }

    Result tick(MinecraftClient client, long now) {
        if (now < nextAction) return null;
        var target = spec.station.target();
        if (!Registries.BLOCK.getId(client.world.getBlockState(target).getBlock()).equals(spec.station.block())) {
            return failure("Expected workstation is missing");
        }
        var recipe = client.world.getRecipeManager().get(spec.recipe).orElse(null);
        if (!(spec.kind == ProjectQueue.Kind.CRAFT ? recipe instanceof CraftingRecipe : recipe instanceof SmeltingRecipe)) {
            return failure("Server has no matching recipe " + spec.recipe);
        }
        var output = recipe.getOutput(client.world.getRegistryManager());
        if (output.isEmpty()) return failure("Recipe does not declare an output");
        int held = 0;
        for (int i = 0; i < 36; i++) {
            var stack = client.player.getInventory().getStack(i);
            if (ItemStack.canCombine(stack, output)) held += stack.getCount();
        }
        if (held >= spec.count) return new Result(true, "Observed " + held + " " + Registries.ITEM.getId(output.getItem()) + " in worker inventory");
        var player = client.player;
        if (screen == null && opening && matching(player.currentScreenHandler)) {
            screen = player.currentScreenHandler;
            opening = false;
        } else if (screen == null) {
            if (player.currentScreenHandler != player.playerScreenHandler) return failure("Another container is open");
            Vec3d eye = player.getEyePos();
            Vec3d center = Vec3d.ofCenter(target);
            var hit = client.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, player));
            double reach = client.interactionManager.getReachDistance() - .1;
            if (eye.squaredDistanceTo(center) > reach * reach || hit.getType() != HitResult.Type.BLOCK
                    || !target.equals(hit.getBlockPos())) return failure("Workstation obstructed or out of reach");
            opening = true;
            player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, center);
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            nextAction = now + SETTLE;
            return null;
        }
        if (player.currentScreenHandler != screen || !screen.getCursorStack().isEmpty()) {
            return failure("Workstation screen changed or inventory cursor is occupied");
        }
        nextAction = now + SETTLE;
        if (recipe instanceof CraftingRecipe craft) return craft(client, craft, output);
        return smelt(client, (SmeltingRecipe) recipe, output);
    }

    private Result craft(MinecraftClient client, CraftingRecipe recipe, ItemStack output) {
        var table = (CraftingScreenHandler) screen;
        var result = table.getSlot(0).getStack();
        if (!result.isEmpty()) {
            if (!ItemStack.canCombine(result, output) || !table.matches(recipe)) return failure("Unexpected crafting output");
            click(client, 0, 0, SlotActionType.QUICK_MOVE);
            return null;
        }
        if (!client.player.getRecipeBook().contains(recipe)) return failure("Unlock recipe " + spec.recipe + " on this worker");
        var matcher = new RecipeMatcher();
        client.player.getInventory().populateRecipeFinder(matcher);
        table.populateRecipeFinder(matcher);
        if (!matcher.match(recipe, null)) return failure("Missing ingredients for " + spec.recipe);
        client.interactionManager.clickRecipe(screen.syncId, recipe, false);
        return null;
    }

    private Result smelt(MinecraftClient client, SmeltingRecipe recipe, ItemStack output) {
        var furnace = (FurnaceScreenHandler) screen;
        var raw = Registries.ITEM.get(spec.input).getDefaultStack();
        var fuel = Registries.ITEM.get(spec.fuel).getDefaultStack();
        if (!recipe.getIngredients().get(0).test(raw) || !furnace.getSlot(1).canInsert(fuel)) {
            return failure("Input or fuel does not match furnace job");
        }
        var result = furnace.getSlot(2).getStack();
        var existingInput = furnace.getSlot(0).getStack();
        var existingFuel = furnace.getSlot(1).getStack();
        if (!existingInput.isEmpty() && !ItemStack.canCombine(existingInput, raw)
                || !existingFuel.isEmpty() && !ItemStack.canCombine(existingFuel, fuel)
                || !result.isEmpty() && !ItemStack.canCombine(result, output)) {
            return failure("Furnace contains unrelated items");
        }
        if (!result.isEmpty()) {
            click(client, 2, 0, SlotActionType.QUICK_MOVE);
            return null;
        }
        if (existingInput.isEmpty() && !feed(client, 0, raw)) return failure("Missing furnace input " + spec.input);
        if (!furnace.isBurning() && existingFuel.isEmpty() && !feed(client, 1, fuel)) {
            return failure("Missing furnace fuel " + spec.fuel);
        }
        return null;
    }

    private boolean feed(MinecraftClient client, int destination, ItemStack wanted) {
        for (int i = 3; i < screen.slots.size(); i++) {
            ItemStack source = screen.getSlot(i).getStack();
            if (!ItemStack.canCombine(source, wanted)) continue;
            click(client, i, 0, SlotActionType.PICKUP);
            click(client, destination, 1, SlotActionType.PICKUP);
            if (!screen.getCursorStack().isEmpty()) click(client, i, 0, SlotActionType.PICKUP);
            return true;
        }
        return false;
    }

    private void click(MinecraftClient client, int slot, int button, SlotActionType action) {
        client.interactionManager.clickSlot(screen.syncId, slot, button, action, client.player);
    }

    private static Result failure(String reason) { return new Result(false, reason); }
}
