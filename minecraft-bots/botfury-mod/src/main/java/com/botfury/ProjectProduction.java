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
import java.util.Set;

/** A workstation job with a bounded inventory target. Uses the server's own recipe and slot rules. */
final class ProjectProduction {
    private static final double[] OUTLINE_SAMPLES = {.2, .8};
    record Spec(ProjectBlock station, ProjectQueue.Kind kind, Identifier recipe, Identifier input,
                Identifier fuel, int count, Set<Identifier> fuelChoices) {
        Spec(ProjectBlock station, ProjectQueue.Kind kind, Identifier recipe, Identifier input,
             Identifier fuel, int count) {
            this(station, kind, recipe, input, fuel, count, Set.of());
        }
    }
    record FuelSelection(Identifier first, Set<String> choices) {}
    record Result(boolean success, String detail, boolean loadedFurnace) {
        Result(boolean success, String detail) { this(success, detail, false); }
    }

    static boolean resumesLoadedFurnace(String payload) {
        var data = new Gson().fromJson(payload, com.google.gson.JsonObject.class);
        if (data == null) return false;
        var flag = data.get("resumeLoadedFurnace");
        return flag != null && flag.isJsonPrimitive() && flag.getAsBoolean();
    }
    private record Payload(String recipe, String input, String fuel, Integer count, boolean inventory, Set<String> fuelChoices) {}
    private static final int FURNACE_BURN_TIME = 0;
    private static final int FURNACE_COOK_TIME = 2;
    private static final long SETTLE = Duration.ofMillis(750).toNanos();
    private final Spec spec;
    private ScreenHandler screen;
    private boolean opening;
    private boolean contactedStation;
    private long nextAction;
    private long nextCraftInputCheck;
    private Map<Integer, ItemStack> craftingInputs;
    private ItemStack substitutedFuel = ItemStack.EMPTY;

    ProjectProduction(Spec spec) { this.spec = spec; }

    boolean canUsePortableWorkbench(MinecraftClient client) {
        var inventory = client.player.getInventory();
        if (inventory.contains(net.minecraft.item.Items.CRAFTING_TABLE.getDefaultStack())) return false;
        if (spec.kind == ProjectQueue.Kind.CRAFT) return canCraft(client, spec.recipe);
        return spec.kind == ProjectQueue.Kind.SMELT
                && inventory.contains(Registries.ITEM.get(spec.input).getDefaultStack())
                && !inventory.contains(net.minecraft.item.Items.FURNACE.getDefaultStack())
                && canCraft(client, Identifier.of("minecraft", "furnace"));
    }

    ProjectProduction atStation(ProjectBlock station) {
        var relocated = new ProjectProduction(new Spec(station, spec.kind, spec.recipe, spec.input, spec.fuel,
                spec.count, spec.fuelChoices));
        relocated.contactedStation = contactedStation;
        return relocated;
    }

    boolean contactedStation() { return contactedStation; }

    boolean carryingUnstartedSmeltingInput(MinecraftClient client) {
        return spec.kind == ProjectQueue.Kind.SMELT && !contactedStation && hasSmeltingInput(client);
    }

    Result failedIfSmeltingInputLost(MinecraftClient client) {
        var finished = finishedFromInventory(client);
        return finished != null ? finished
                : hasSmeltingInput(client) ? null : failure("Missing furnace input " + spec.input);
    }

    private boolean hasSmeltingInput(MinecraftClient client) {
        return client.player.getInventory().contains(Registries.ITEM.get(spec.input).getDefaultStack());
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
        var fuel = smelt ? item(payload.fuel) : null;
        Set<Identifier> choices = smelt && payload.fuelChoices != null
                ? payload.fuelChoices.stream().map(ProjectProduction::item).collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.of();
        if (!choices.isEmpty() && !choices.contains(fuel)) throw new IllegalArgumentException("Invalid recovered furnace fuel");
        return new Spec(station, kind, recipe, smelt ? item(payload.input) : null, fuel, payload.count, choices);
    }

    static Spec useInventoryGrid(MinecraftClient client, Spec spec) {
        if (client.world == null || spec.station == null || spec.kind != ProjectQueue.Kind.CRAFT) return spec;
        if (fitsInventory(client, spec.recipe)) {
            return new Spec(null, spec.kind, spec.recipe, spec.input, spec.fuel, spec.count);
        }
        return spec;
    }

    static boolean fitsInventory(MinecraftClient client, Identifier id) {
        if (id == null || client.world == null) return false;
        var recipe = client.world.getRecipeManager().get(id).orElse(null);
        return recipe instanceof CraftingRecipe craft && craft.fits(2, 2);
    }

    static boolean canCraft(MinecraftClient client, Identifier id) {
        return canCraft(client, id, ItemStack.EMPTY);
    }

    static boolean canCraft(MinecraftClient client, Identifier id, ItemStack additional) {
        var recipe = client.world.getRecipeManager().get(id).orElse(null);
        return recipe instanceof CraftingRecipe crafting && matchesInventoryRecipe(client, crafting, additional);
    }

    private static boolean matchesInventoryRecipe(MinecraftClient client, CraftingRecipe recipe, ItemStack additional) {
        var matcher = new RecipeMatcher();
        client.player.getInventory().populateRecipeFinder(matcher);
        if (!additional.isEmpty()) matcher.addInput(additional);
        return matcher.match(recipe, null);
    }

    static java.util.Set<String> carriedCraftingIngredients(MinecraftClient client, String work,
                                                            Map<String, Integer> inventory) {
        var spec = parse(work, ProjectQueue.Kind.CRAFT);
        var recipe = client.world.getRecipeManager().get(spec.recipe).orElse(null);
        if (!(recipe instanceof CraftingRecipe)) return java.util.Set.of();
        int yield = recipe.getOutput(client.world.getRegistryManager()).getCount();
        if (yield < 1) return java.util.Set.of();
        var matcher = new RecipeMatcher();
        inventory.forEach((key, count) -> {
            var id = Identifier.tryParse(key);
            if (id == null || count == null || count <= 0) return;
            Registries.ITEM.getOrEmpty(id).ifPresent(item -> {
                int held = Math.min(count, 36 * item.getMaxCount());
                matcher.addInput(new ItemStack(item, held), held);
            });
        });
        var matched = new IntArrayList();
        if (!matcher.match(recipe, matched, (spec.count + yield - 1) / yield)) return java.util.Set.of();
        var inputs = new java.util.HashSet<String>();
        for (int code : matched) {
            var stack = RecipeMatcher.getStackFromId(code);
            if (!stack.isEmpty()) inputs.add(Registries.ITEM.getId(stack.getItem()).toString());
        }
        return inputs;
    }

    static Identifier alternativeFuel(MinecraftClient client, ProjectQueue.FuelUse plan) {
        var recipe = client.world.getRecipeManager().get(Identifier.tryParse(plan.recipe())).orElse(null);
        if (!(recipe instanceof SmeltingRecipe smelting)) return null;
        int output = smelting.getOutput(client.world.getRegistryManager()).getCount();
        if (output < 1) return null;
        long cookTicks = (long) smelting.getCookTime() * ((plan.count() + output - 1) / output);
        return alternativeFuel(carriedFuel(client), Identifier.tryParse(plan.input()),
                Identifier.tryParse(plan.expectedFuel()), cookTicks, false);
    }

    private static Map<Identifier, Integer> carriedFuel(MinecraftClient client) {
        var carried = new LinkedHashMap<Identifier, Integer>();
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            if (!stack.isEmpty() && !stack.isDamageable() && !stack.hasNbt()) {
                carried.merge(Registries.ITEM.getId(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        return carried;
    }

    static Identifier smeltingOutput(MinecraftClient client, String work) {
        var spec = parse(work, ProjectQueue.Kind.SMELT);
        var recipe = client.world.getRecipeManager().get(spec.recipe).orElse(null);
        if (!(recipe instanceof SmeltingRecipe smelting)) return null;
        var output = smelting.getOutput(client.world.getRegistryManager());
        return output.isEmpty() || output.hasNbt() ? null : Registries.ITEM.getId(output.getItem());
    }

    static String retainedPickaxeMaterial(MinecraftClient client, String pickaxe, String furnace) {
        var output = smeltingOutput(client, furnace);
        if (output == null || output.equals(Identifier.of("minecraft", "stick"))) return null;
        String item = output.toString();
        int count = parse(furnace, ProjectQueue.Kind.SMELT).count();
        var ingredients = carriedCraftingIngredients(client, pickaxe, Map.of(item, count, "minecraft:stick", 2));
        return ingredients.contains(item) ? item : null;
    }

    static FuelSelection carriedSmeltingFuel(MinecraftClient client, String work, Map<String, Integer> inventory,
                                              Set<String> reserved, boolean supportsFuelChoices) {
        var spec = parse(work, ProjectQueue.Kind.SMELT);
        if (inventory.getOrDefault(spec.input.toString(), 0) == 0) return null;
        var recipe = client.world.getRecipeManager().get(spec.recipe).orElse(null);
        if (!(recipe instanceof SmeltingRecipe smelt)
                || !smelt.getIngredients().get(0).test(Registries.ITEM.get(spec.input).getDefaultStack())) return null;
        int output = smelt.getOutput(client.world.getRegistryManager()).getCount();
        if (output < 1) return null;
        int required = (spec.count + output - 1) / output;
        if (inventory.getOrDefault(spec.input.toString(), 0) < required || reserved.contains(spec.input.toString())) return null;
        long cookTicks = (long) smelt.getCookTime() * required;
        if (cookTicks <= 0) return null;
        var burnTimes = net.minecraft.block.entity.AbstractFurnaceBlockEntity.createFuelTimeMap();
        var carried = new LinkedHashMap<Identifier, Integer>();
        inventory.forEach((key, count) -> {
            var id = Identifier.tryParse(key);
            if (id != null && count != null && count > 0 && !reserved.contains(key) && !id.equals(spec.input)
                    && burnTimes.getOrDefault(Registries.ITEM.get(id), 0) > 0
                    && (id.equals(spec.fuel) || automaticFuel(id))) carried.put(id, count);
        });
        long preferredBurn = (long) burnTimes.getOrDefault(Registries.ITEM.get(spec.fuel), 0)
                * carried.getOrDefault(spec.fuel, 0);
        var first = preferredBurn >= cookTicks ? spec.fuel
                : alternativeFuel(carried, spec.input, spec.fuel, cookTicks, supportsFuelChoices);
        if (first == null) return null;
        var choices = supportsFuelChoices ? carried.keySet().stream().map(Identifier::toString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.<String>of();
        return new FuelSelection(first, choices);
    }

    private static Identifier alternativeFuel(Map<Identifier, Integer> carried, Identifier input,
                                              Identifier preferred, long cookTicks, boolean mixFuels) {
        return alternativeFuel(carried, input, preferred, cookTicks, mixFuels, Set.of());
    }

    private static Identifier alternativeFuel(Map<Identifier, Integer> carried, Identifier input,
                                              Identifier preferred, long cookTicks, boolean mixFuels, Set<Identifier> choices) {
        if (cookTicks <= 0) return null;
        var burnTimes = net.minecraft.block.entity.AbstractFurnaceBlockEntity.createFuelTimeMap();
        Identifier best = null;
        Identifier longest = null;
        long bestWaste = Long.MAX_VALUE;
        long availableBurn = 0;
        int longestBurn = 0;
        for (var entry : carried.entrySet()) {
            var id = entry.getKey();
            int ticks = burnTimes.getOrDefault(Registries.ITEM.get(id), 0);
            if (ticks <= 0 || id.equals(input) || !allowedFuel(id, preferred, choices)) continue;
            long burn = (long) ticks * entry.getValue();
            availableBurn += burn;
            if (id.equals(preferred)) continue;
            if (ticks > longestBurn) {
                longest = id;
                longestBurn = ticks;
            }
            if (burn < cookTicks) continue;
            long waste = (cookTicks + ticks - 1) / ticks * ticks - cookTicks;
            if (waste < bestWaste) {
                best = id;
                bestWaste = waste;
            }
        }
        if (best != null) return best;
        return mixFuels && availableBurn >= cookTicks ? longest : null;
    }

    private static boolean allowedFuel(Identifier id, Identifier preferred, Set<Identifier> choices) {
        return choices.isEmpty() ? id.equals(preferred) || automaticFuel(id) : choices.contains(id);
    }

    private static boolean automaticFuel(Identifier id) {
        var stack = Registries.ITEM.get(id).getDefaultStack();
        return stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS) || stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)
                || stack.isOf(net.minecraft.item.Items.COAL) || stack.isOf(net.minecraft.item.Items.CHARCOAL)
                || stack.isOf(net.minecraft.item.Items.COAL_BLOCK) || stack.isOf(net.minecraft.item.Items.STICK)
                || stack.isOf(net.minecraft.item.Items.BAMBOO) || stack.isOf(net.minecraft.item.Items.DRIED_KELP_BLOCK);
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

    Result beforeApproach(MinecraftClient client, long now) {
        var finished = finishedFromInventory(client);
        if (finished != null || spec.kind != ProjectQueue.Kind.CRAFT || now < nextCraftInputCheck
                || client.player.currentScreenHandler != client.player.playerScreenHandler) return finished;
        nextCraftInputCheck = now + Duration.ofSeconds(1).toNanos();
        var recipe = client.world.getRecipeManager().get(spec.recipe).orElse(null);
        if (!(recipe instanceof CraftingRecipe crafting)) return null;
        return matchesInventoryRecipe(client, crafting, ItemStack.EMPTY)
                ? null : failure("Missing ingredients for " + spec.recipe);
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
            contactedStation = true;
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
            return takeOutput(client, 0);
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
        return takeOutput(client, resultSlot);
    }

    private Result takeOutput(MinecraftClient client, int resultSlot) {
        var result = screen.getSlot(resultSlot).getStack();
        if (!ProjectInventory.canFit(client, result, result.getCount())) {
            return ProjectInventory.releaseSurplus(client, screen, stack -> ItemStack.canCombine(stack, result), "production")
                    ? null : failure("Inventory is full for " + Registries.ITEM.getId(result.getItem()));
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
                    && !ItemStack.canCombine(existingFuel, substitutedFuel)
                    && !spec.fuelChoices.contains(Registries.ITEM.getId(existingFuel.getItem()))
                || !result.isEmpty() && !ItemStack.canCombine(result, output)) {
            return failure("Furnace contains unrelated items");
        }
        int outstanding = Math.max(0, remaining - result.getCount());
        int inputs = (outstanding + output.getCount() - 1) / output.getCount();
        int queued = existingInput.getCount();
        if (!result.isEmpty()) return takeOutput(client, 2);
        var progress = ((com.botfury.mixin.FurnaceProgressAccess) furnace).botfury$properties();
        var carried = carriedFuel(client);
        if (!spec.fuelChoices.isEmpty()) carried.keySet().retainAll(spec.fuelChoices);
        var burnTimes = net.minecraft.block.entity.AbstractFurnaceBlockEntity.createFuelTimeMap();
        long credited = (long) progress.get(FURNACE_COOK_TIME) + progress.get(FURNACE_BURN_TIME);
        long availableBurn = credited + (long) burnTimes.getOrDefault(existingFuel.getItem(), 0) * existingFuel.getCount();
        for (var entry : carried.entrySet()) {
            var id = entry.getKey();
            if (!id.equals(spec.input) && allowedFuel(id, spec.fuel, spec.fuelChoices)) {
                availableBurn += (long) burnTimes.getOrDefault(Registries.ITEM.get(id), 0) * entry.getValue();
            }
        }
        // Leave any input that cannot be cooked in the worker's inventory for recovery.
        int covered = (int) Math.min(inputs, availableBurn / Math.max(1, recipe.getCookTime()));
        int load = Math.min(covered, furnace.getSlot(0).getMaxItemCount(raw)) - queued;
        if (load > 0 && feed(client, 0, raw, load)) return null;
        if (queued == 0) return failure(carried.getOrDefault(spec.input, 0) > 0
                ? "Missing furnace fuel " + spec.fuel : "Missing furnace input " + spec.input);
        long fuelNeeded = (long) recipe.getCookTime() * queued - credited;
        if (existingFuel.isEmpty() && fuelNeeded > 0
                && !addFuel(client, furnace, fuel, fuelNeeded, carried) && !furnace.isBurning()) {
            return new Result(false, "Missing furnace fuel " + spec.fuel, true);
        }
        return null;
    }

    private boolean addFuel(MinecraftClient client, FurnaceScreenHandler furnace, ItemStack preferred, long cookTicks,
                            Map<Identifier, Integer> carried) {
        if (feed(client, 1, preferred, 1)) return true;
        if (!substitutedFuel.isEmpty() && feed(client, 1, substitutedFuel, 1)) return true;
        var alternative = alternativeFuel(carried, spec.input, spec.fuel, cookTicks, true, spec.fuelChoices);
        if (alternative == null) return false;
        var fuel = Registries.ITEM.get(alternative).getDefaultStack();
        if (!furnace.getSlot(1).canInsert(fuel) || !feed(client, 1, fuel, 1)) return false;
        substitutedFuel = fuel;
        BotEvents.add("info", "projects", "Furnace is using carried " + alternative);
        return true;
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
