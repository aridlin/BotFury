package com.botfury;

import com.google.gson.Gson;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeGridAligner;
import net.minecraft.recipe.RecipeMatcher;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** A workstation job with a bounded inventory target. Uses the server's own recipe and slot rules. */
final class ProjectProduction {
    private static final double[] OUTLINE_SAMPLES = {.2, .8};
    record Spec(ProjectBlock station, ProjectQueue.Kind kind, Identifier recipe, Identifier input,
                Identifier fuel, int count) {}
    record Result(boolean success, String detail) {}
    private record Payload(String recipe, String input, String fuel, Integer count, boolean inventory) {}
    private static final long SETTLE = Duration.ofMillis(750).toNanos();
    private final Spec spec;
    private ScreenHandler screen;
    private boolean opening;
    private long nextAction;
    private Map<Integer, ItemStack> craftingInputs;

    ProjectProduction(Spec spec) { this.spec = spec; }

    ProjectProduction atStation(ProjectBlock station) {
        return new ProjectProduction(new Spec(station, spec.kind, spec.recipe, spec.input, spec.fuel, spec.count));
    }

    static Spec parse(String json, ProjectQueue.Kind kind) {
        var payload = new Gson().fromJson(json, Payload.class);
        boolean smelt = kind == ProjectQueue.Kind.SMELT;
        var station = payload.inventory ? null : ProjectBlock.parse(json);
        if (payload.inventory && smelt || station != null
                && !station.block().equals(Identifier.of("minecraft", smelt ? "furnace" : "crafting_table"))
                || payload.count == null || payload.count < 1 || payload.count > 256) {
            throw new IllegalArgumentException("Provide a matching workstation and output count (1–256)");
        }
        var recipe = payload.recipe == null ? null : Identifier.tryParse(payload.recipe);
        if (recipe == null) throw new IllegalArgumentException("Invalid recipe ID");
        return new Spec(station, kind, recipe, smelt ? item(payload.input) : null,
                smelt ? item(payload.fuel) : null, payload.count);
    }

    static Spec useInventoryGrid(MinecraftClient client, Spec spec) {
        if (client.world == null || spec.station == null || spec.kind != ProjectQueue.Kind.CRAFT) return spec;
        var recipe = client.world.getRecipeManager().get(spec.recipe).orElse(null);
        if (recipe instanceof CraftingRecipe craft && craft.fits(2, 2)) {
            return new Spec(null, spec.kind, spec.recipe, spec.input, spec.fuel, spec.count);
        }
        return spec;
    }

    static boolean canCraft(MinecraftClient client, Identifier id) {
        var recipe = client.world.getRecipeManager().get(id).orElse(null);
        if (!(recipe instanceof CraftingRecipe)) return false;
        var matcher = new net.minecraft.recipe.RecipeMatcher();
        client.player.getInventory().populateRecipeFinder(matcher);
        return matcher.match(recipe, null);
    }

    static Identifier alternativeFuel(MinecraftClient client, ProjectQueue.FuelUse plan) {
        var recipe = client.world.getRecipeManager().get(Identifier.tryParse(plan.recipe())).orElse(null);
        if (!(recipe instanceof SmeltingRecipe smelting)) return null;
        int output = smelting.getOutput(client.world.getRegistryManager()).getCount();
        if (output < 1) return null;
        long cookTicks = (long) smelting.getCookTime() * ((plan.count() + output - 1) / output);
        if (cookTicks <= 0) return null;
        var burnTimes = net.minecraft.block.entity.AbstractFurnaceBlockEntity.createFuelTimeMap();
        var carried = new java.util.LinkedHashMap<Identifier, Integer>();
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            if (!stack.isEmpty() && !stack.isDamageable() && !stack.hasNbt()) {
                carried.merge(Registries.ITEM.getId(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        Identifier best = null;
        long bestWaste = Long.MAX_VALUE;
        for (var entry : carried.entrySet()) {
            var id = entry.getKey();
            int ticks = burnTimes.getOrDefault(Registries.ITEM.get(id), 0);
            if (ticks <= 0 || id.toString().equals(plan.input()) || id.toString().equals(plan.expectedFuel())
                    || (long) ticks * entry.getValue() < cookTicks) continue;
            long waste = (cookTicks + (long) ticks - 1) / ticks * ticks - cookTicks;
            if (waste < bestWaste) {
                best = id;
                bestWaste = waste;
            }
        }
        return best;
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
        craftingInputs = null;
    }

    private boolean matching(ScreenHandler handler) {
        return spec.kind == ProjectQueue.Kind.CRAFT ? handler instanceof CraftingScreenHandler
                : handler instanceof FurnaceScreenHandler;
    }

    boolean reachableFromHere(MinecraftClient client) {
        if (spec.station == null) return false;
        if (screen != null && client.player.currentScreenHandler == screen) return true;
        if (client.player.isTouchingWater() || client.player.isClimbing()) return false;
        return workstationHit(client, spec.station.target()) != null;
    }

    Result finishedFromInventory(MinecraftClient client) {
        var recipe = client.world.getRecipeManager().get(spec.recipe).orElse(null);
        if (!(spec.kind == ProjectQueue.Kind.CRAFT ? recipe instanceof CraftingRecipe : recipe instanceof SmeltingRecipe)) {
            return null;
        }
        var output = recipe.getOutput(client.world.getRegistryManager());
        return output.isEmpty() ? null : observedOutput(output, inventoryCount(client, output));
    }

    private Result observedOutput(ItemStack output, int held) {
        return held >= spec.count ? new Result(true, "Observed " + held + " "
                + Registries.ITEM.getId(output.getItem()) + " in worker inventory") : null;
    }

    private static int inventoryCount(MinecraftClient client, ItemStack output) {
        var inventory = client.player.getInventory();
        int held = 0;
        for (int i = 0; i < 36; i++) {
            var stack = inventory.getStack(i);
            if (ItemStack.canCombine(stack, output)) held += stack.getCount();
        }
        return held;
    }

    private static BlockHitResult workstationHit(MinecraftClient client, BlockPos target) {
        return workstationHit(client, target, client.player.getEyePos());
    }

    static BlockHitResult workstationHit(MinecraftClient client, BlockPos target, Vec3d eye) {
        var center = Vec3d.ofCenter(target);
        double reach = client.interactionManager.getReachDistance() - .1;
        if (eye.squaredDistanceTo(center) > (reach + .9) * (reach + .9)) return null;
        var hit = traceWorkstation(client, target, eye, center, reach);
        if (hit != null) return hit;
        var outline = client.world.getBlockState(target).getOutlineShape(client.world, target);
        for (var box : outline.getBoundingBoxes()) {
            for (double x : OUTLINE_SAMPLES) for (double y : OUTLINE_SAMPLES) for (double z : OUTLINE_SAMPLES) {
                var point = new Vec3d(target.getX() + MathHelper.lerp(x, box.minX, box.maxX),
                        target.getY() + MathHelper.lerp(y, box.minY, box.maxY),
                        target.getZ() + MathHelper.lerp(z, box.minZ, box.maxZ));
                hit = traceWorkstation(client, target, eye, point, reach);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static BlockHitResult traceWorkstation(MinecraftClient client, BlockPos target, Vec3d eye, Vec3d point,
                                                   double reach) {
        var hit = client.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == HitResult.Type.BLOCK && target.equals(hit.getBlockPos())
                && eye.squaredDistanceTo(hit.getPos()) <= reach * reach ? hit : null;
    }

    Result tick(MinecraftClient client, long now) {
        if (now < nextAction) return null;
        var target = spec.station == null ? null : spec.station.target();
        if (target != null && !Registries.BLOCK.getId(client.world.getBlockState(target).getBlock()).equals(spec.station.block())) {
            return failure("Expected workstation is missing");
        }
        var recipe = client.world.getRecipeManager().get(spec.recipe).orElse(null);
        if (!(spec.kind == ProjectQueue.Kind.CRAFT ? recipe instanceof CraftingRecipe : recipe instanceof SmeltingRecipe)) {
            return failure("Server has no matching recipe " + spec.recipe);
        }
        if (target == null && !recipe.fits(2, 2)) return failure("Recipe does not fit the inventory crafting grid");
        var output = recipe.getOutput(client.world.getRegistryManager());
        if (output.isEmpty()) return failure("Recipe does not declare an output");
        int held = inventoryCount(client, output);
        var available = observedOutput(output, held);
        if (available != null) return available;
        var player = client.player;
        if (target == null) {
            screen = player.playerScreenHandler;
        } else if (screen == null && opening && matching(player.currentScreenHandler)) {
            screen = player.currentScreenHandler;
            opening = false;
        } else if (screen == null) {
            if (player.currentScreenHandler != player.playerScreenHandler) return failure("Another container is open");
            var hit = workstationHit(client, target);
            if (hit == null) return failure("Workstation obstructed or out of reach");
            opening = true;
            player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, hit.getPos());
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            nextAction = now + SETTLE;
            return null;
        }
        if (player.currentScreenHandler != screen || !screen.getCursorStack().isEmpty()) {
            return failure("Workstation screen changed or inventory cursor is occupied");
        }
        nextAction = now + SETTLE;
        if (recipe instanceof CraftingRecipe craft) {
            return spec.station == null ? craft(client, player.playerScreenHandler, craft, output)
                    : craft(client, (CraftingScreenHandler) screen, craft, output);
        }
        return smelt(client, (SmeltingRecipe) recipe, output, spec.count - held);
    }

    private Result craft(MinecraftClient client,
                         AbstractRecipeScreenHandler<net.minecraft.inventory.RecipeInputInventory> table,
                         CraftingRecipe recipe, ItemStack output) {
        if (craftingInputs != null) return fillCraftingGrid(client, table, recipe, output);
        var result = table.getSlot(0).getStack();
        if (!result.isEmpty()) {
            if (!ItemStack.canCombine(result, output) || !table.matches(recipe)) return failure("Unexpected crafting output");
            click(client, 0, 0, SlotActionType.QUICK_MOVE);
            return null;
        }
        if (!client.player.getRecipeBook().contains(recipe)) {
            return fillCraftingGrid(client, table, recipe, output);
        }
        var matcher = new RecipeMatcher();
        client.player.getInventory().populateRecipeFinder(matcher);
        table.populateRecipeFinder(matcher);
        if (!matcher.match(recipe, null)) return failure("Missing ingredients for " + spec.recipe);
        client.interactionManager.clickRecipe(screen.syncId, recipe, false);
        return null;
    }

    private Result fillCraftingGrid(MinecraftClient client,
                                    AbstractRecipeScreenHandler<net.minecraft.inventory.RecipeInputInventory> table,
                                    CraftingRecipe recipe, ItemStack output) {
        int resultSlot = table.getCraftingResultSlotIndex();
        if (craftingInputs == null) {
            int slots = table.getCraftingWidth() * table.getCraftingHeight();
            for (int slot = 0; slot <= slots; slot++) {
                if (slot != resultSlot && table.getSlot(slot).hasStack()) {
                    return failure("Clear the crafting grid before automatic crafting");
                }
            }
            var matcher = new RecipeMatcher();
            client.player.getInventory().populateRecipeFinder(matcher);
            var ingredients = new IntArrayList();
            if (!matcher.match(recipe, ingredients)) return failure("Missing ingredients for " + spec.recipe);
            craftingInputs = new LinkedHashMap<>();
            RecipeGridAligner<Integer> aligner = (input, slot, count, row, column) -> {
                var item = RecipeMatcher.getStackFromId(input.next());
                if (!item.isEmpty()) craftingInputs.put(slot, item);
            };
            aligner.alignRecipeToGrid(table.getCraftingWidth(), table.getCraftingHeight(), resultSlot,
                    recipe, ingredients.iterator(), 1);
        }
        for (var input : craftingInputs.entrySet()) {
            var slot = table.getSlot(input.getKey());
            if (ItemStack.canCombine(slot.getStack(), input.getValue())) continue;
            if (slot.hasStack() || !slot.canInsert(input.getValue())) return failure("Crafting grid changed");
            return feed(client, input.getKey(), input.getValue(), 1) ? null
                    : failure("Missing ingredients for " + spec.recipe);
        }
        // A partial layout can momentarily match a different recipe.
        if (!ItemStack.canCombine(table.getSlot(resultSlot).getStack(), output) || !table.matches(recipe)) {
            return failure("Server did not produce " + spec.recipe + " from the crafting grid");
        }
        click(client, resultSlot, 0, SlotActionType.QUICK_MOVE);
        craftingInputs = null;
        return null;
    }

    private Result smelt(MinecraftClient client, SmeltingRecipe recipe, ItemStack output, int remaining) {
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
        int outstanding = Math.max(0, remaining - result.getCount());
        int inputs = (outstanding + output.getCount() - 1) / output.getCount();
        int queued = existingInput.getCount();
        int load = Math.min(inputs, furnace.getSlot(0).getMaxItemCount(raw)) - queued;
        // Keep the next input ready so a live furnace does not burn fuel between items.
        if (load > 0 && feed(client, 0, raw, load)) return null;
        if (!result.isEmpty()) {
            click(client, 2, 0, SlotActionType.QUICK_MOVE);
            return null;
        }
        if (queued == 0) return failure("Missing furnace input " + spec.input);
        if (!furnace.isBurning() && existingFuel.isEmpty() && !feed(client, 1, fuel, 1)) {
            return failure("Missing furnace fuel " + spec.fuel);
        }
        return null;
    }

    private boolean feed(MinecraftClient client, int destination, ItemStack wanted, int count) {
        for (var sourceSlot : screen.slots) {
            if (sourceSlot.inventory != client.player.getInventory() || sourceSlot.getIndex() >= 36) continue;
            int i = sourceSlot.id;
            ItemStack source = sourceSlot.getStack();
            if (!ItemStack.canCombine(source, wanted)) continue;
            int available = source.getCount();
            click(client, i, 0, SlotActionType.PICKUP);
            if (available <= count) {
                click(client, destination, 0, SlotActionType.PICKUP);
            } else {
                for (int moved = 0; moved < count && !screen.getCursorStack().isEmpty(); moved++) {
                    click(client, destination, 1, SlotActionType.PICKUP);
                }
            }
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
