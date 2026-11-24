package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.*;

final class MaterialRecipe {
    private static final Gson JSON = new Gson();
    private record Step(ProjectQueue.Kind kind, String resource, Identifier recipe, int yield,
                Map<Identifier, Integer> ingredients, int cookTime, boolean inventoryCraft) {}

    private record Quantity(Identifier item, Step step, int count) {}
    record Batch(Plan plan, int count) {}

    private static final class BatchTooLarge extends IllegalArgumentException {
        BatchTooLarge(Identifier item) { super("Split the material batch for " + item); }
    }

    static final class Plan {
        private final List<Quantity> quantities;
        private Plan(List<Quantity> quantities) { this.quantities = List.copyOf(quantities); }

        int size() { return quantities.size(); }

        boolean needsTable() {
            return quantities.stream().anyMatch(quantity -> quantity.step.kind == ProjectQueue.Kind.CRAFT
                    && !quantity.step.inventoryCraft);
        }
        boolean needsFurnace() {
            return quantities.stream().anyMatch(quantity -> quantity.step.kind == ProjectQueue.Kind.SMELT);
        }

        boolean usesDroppedItems() { return "dropped".equals(quantities.get(0).step.resource); }

        List<ProjectQueue.Work> jobs(String prefix, ProjectGather.Area protectedArea,
                                     ProjectBlock table, ProjectBlock furnace) {
            return jobs(prefix, protectedArea, table, furnace, List.of());
        }

        List<ProjectQueue.Work> jobs(String prefix, ProjectGather.Area protectedArea,
                                     ProjectBlock table, ProjectBlock furnace, List<String> startAfter) {
            var jobs = new ArrayList<ProjectQueue.Work>();
            String previous = null;
            for (var quantity : quantities) {
                var step = quantity.step;
                ProjectBlock station = null;
                JsonObject payload;
                if (step.kind == ProjectQueue.Kind.GATHER) {
                    payload = new JsonObject();
                    payload.addProperty("resource", step.resource);
                    payload.addProperty("variant", "both");
                    payload.addProperty("item", quantity.item.toString());
                    payload.add("exclude", JSON.toJsonTree(Map.of("min", StructureSupply.position(protectedArea.min()),
                            "max", StructureSupply.position(protectedArea.max()))));
                } else {
                    boolean smelting = step.kind == ProjectQueue.Kind.SMELT;
                    if (smelting) station = furnace;
                    else if (!step.inventoryCraft) station = table;
                    if (station == null && !step.inventoryCraft) {
                        throw new IllegalArgumentException("Place a reachable " + (smelting ? "furnace" : "crafting table")
                                + " near the chest for " + quantity.item);
                    }
                    payload = station == null ? new JsonObject() : stationPayload(station);
                    if (station == null) payload.addProperty("inventory", true);
                    payload.addProperty("recipe", step.recipe.toString());
                    if (smelting) {
                        payload.addProperty("input", step.ingredients.keySet().iterator().next().toString());
                        payload.addProperty("fuel", COAL.toString());
                    }
                }
                payload.addProperty("count", quantity.count);
                String id = prefix + jobs.size();
                var job = new ProjectQueue.Work(id, step.kind, JSON.toJson(payload),
                        previous == null ? startAfter : List.of(previous), Set.of(), previous);
                jobs.add(station == null ? job : station.reserve(job));
                previous = id;
            }
            return List.copyOf(jobs);
        }
    }

    static JsonObject stationPayload(ProjectBlock station) {
        return JSON.toJsonTree(new ProjectBlock.Payload(StructureSupply.position(station.target()),
                StructureSupply.position(station.stand()), station.block().toString())).getAsJsonObject();
    }

    private static final Identifier COAL = mc("coal");
    private static final Map<String, String> ORES = Map.ofEntries(
            Map.entry("coal", "coal"), Map.entry("raw_iron", "iron"), Map.entry("raw_copper", "copper"),
            Map.entry("raw_gold", "gold"), Map.entry("redstone", "redstone"), Map.entry("lapis_lazuli", "lapis"),
            Map.entry("diamond", "diamond"), Map.entry("emerald", "emerald"), Map.entry("quartz", "quartz"),
            Map.entry("gold_nugget", "nether_gold"), Map.entry("ancient_debris", "ancient_debris"),
            Map.entry("cobblestone", "stone"), Map.entry("cobbled_deepslate", "deepslate"),
            Map.entry("clay_ball", "block:minecraft:clay"), Map.entry("dirt", "dirt"));
    private final Map<Identifier, List<Recipe<?>>> recipes = new HashMap<>();
    private final MinecraftClient client;
    private final Map<Identifier, Integer> ingredientAvailability = new HashMap<>();
    private Map<Block, Integer> nearbyBlocks;

    private int remaining = 512;

    MaterialRecipe(MinecraftClient client, boolean canCraft, boolean canSmelt) {
        this.client = client;
        client.world.getRecipeManager().values().stream()
                .filter(recipe -> recipe instanceof CraftingRecipe && (canCraft || recipe.fits(2, 2))
                        || canSmelt && recipe instanceof SmeltingRecipe)
                .sorted(Comparator.comparing(recipe -> recipe.getId().toString()))
                .forEach(recipe -> {
                    var output = recipe.getOutput(client.world.getRegistryManager());
                    if (!output.isEmpty()) recipes.computeIfAbsent(Registries.ITEM.getId(output.getItem()),
                            ignored -> new ArrayList<>()).add(recipe);
                });
    }

    Plan plan(Identifier item, int count, boolean allowDrops) {
        return prepareTools(quantities(item, count, allowDrops, resolveSteps(item, allowDrops)));
    }

    List<Batch> batches(Identifier item, int count, int maxPerBatch) {
        var steps = resolveSteps(item, false);
        int lower = 1;
        int upper = Math.min(count, maxPerBatch);
        while (lower < upper) {
            int candidate = lower + (upper - lower + 1) / 2;
            try {
                quantities(item, candidate, false, steps);
                lower = candidate;
            } catch (BatchTooLarge ignored) {
                upper = candidate - 1;
            }
        }
        var fullBatch = new Batch(prepareTools(quantities(item, lower, false, steps)), lower);
        var batches = new ArrayList<Batch>();
        for (; count >= lower; count -= lower) batches.add(fullBatch);
        if (count > 0) batches.add(new Batch(prepareTools(quantities(item, count, false, steps)), count));
        return List.copyOf(batches);
    }

    private static final List<net.minecraft.item.Item> PICKAXES = List.of(Items.WOODEN_PICKAXE,
            Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE);

    private Plan prepareTools(Plan plan) {
        int highest = -1;
        int miningUses = 0;
        boolean deepProspecting = false;
        for (var quantity : plan.quantities) {
            var source = quantity.step.resource;
            if (source == null || source.equals("dropped")) continue;
            int tier = requiredPickaxe(source);
            if (tier < 0) continue;
            highest = Math.max(highest, tier);
            deepProspecting |= SubsurfaceAccess.deepResource(source);
            // One use per requested drop is conservative for multi-drop blocks and harvesting enchantments.
            miningUses = Math.addExact(miningUses, quantity.count);
        }
        if (highest < 0) return plan;
        var stages = new ArrayList<Quantity>();
        for (int tier = 0; tier <= highest; tier++) {
            var pickaxe = PICKAXES.get(tier);
            var item = Registries.ITEM.getId(pickaxe);
            int copies = tier == highest ? 1 + (miningUses - 1) / pickaxe.getMaxDamage() : 1;
            if (tier == 1 && deepProspecting) {
                // Reserve a separate route budget for descent and branching through natural terrain.
                copies = Math.max(copies, 1 + (512 - 1) / pickaxe.getMaxDamage());
            }
            var tools = quantities(item, copies, false, resolveSteps(item, false)).quantities;
            if (tier == highest && deepProspecting && recipes.containsKey(mc("crafting_table"))) {
                // Stock the portable kit before the final tool craft, which consumes two sticks per pickaxe.
                stages.addAll(tools.subList(0, tools.size() - 1));
                var table = mc("crafting_table");
                var sticks = mc("stick");
                stages.addAll(quantities(table, 1, false, resolveSteps(table, false)).quantities);
                stages.addAll(quantities(sticks, 4 + 2 * copies, false, resolveSteps(sticks, false)).quantities);
                stages.add(tools.get(tools.size() - 1));
            } else {
                // Earlier stages provide the tools for this recipe before the production batch begins.
                stages.addAll(tools);
            }
            if (tier == 1 && deepProspecting && BotUiConfig.defense) {
                var sword = Registries.ITEM.getId(Items.STONE_SWORD);
                if (recipes.containsKey(sword)) {
                    stages.addAll(quantities(sword, 1, false, resolveSteps(sword, false)).quantities);
                }
            }
        }
        stages.addAll(plan.quantities);
        return new Plan(stages);
    }

    private static int requiredPickaxe(String source) {
        int required = -1;
        for (String id : ResourceCatalog.blocks(source, "both")) {
            var state = Registries.BLOCK.get(new Identifier(id)).getDefaultState();
            if (!state.isToolRequired()) continue;
            int tier = 0;
            while (tier < PICKAXES.size() && !PICKAXES.get(tier).getDefaultStack().isSuitableFor(state)) tier++;
            if (tier == PICKAXES.size()) throw new IllegalArgumentException("No automatic mining tool for " + id);
            required = Math.max(required, tier);
        }
        return required;
    }

    private LinkedHashMap<Identifier, Step> resolveSteps(Identifier item, boolean allowDrops) {
        remaining = 512;
        var steps = new LinkedHashMap<Identifier, Step>();
        if (!resolve(item, steps, new HashSet<>())) {
            if (!allowDrops) throw new IllegalArgumentException("No automatic source for " + item);
            steps.put(item, gather("dropped"));
        }
        return steps;
    }

    private static Plan quantities(Identifier item, int count, boolean allowDrops, Map<Identifier, Step> steps) {
        var demand = new HashMap<Identifier, Integer>();
        demand.put(item, count);
        var ordered = new ArrayList<>(steps.entrySet());
        var quantities = new ArrayList<Quantity>();
        for (int i = ordered.size() - 1; i >= 0; i--) {
            var entry = ordered.get(i);
            int needed = demand.getOrDefault(entry.getKey(), 0);
            if (needed == 0) continue;
            var step = entry.getValue();
            int batches = (needed + step.yield - 1) / step.yield;
            long cookTicks = (long) step.cookTime * batches;
            if (needed > (step.kind == ProjectQueue.Kind.GATHER ? 576 : 256) || (!allowDrops && cookTicks > 4800)) {
                throw new BatchTooLarge(entry.getKey());
            }
            step.ingredients.forEach((ingredient, amount) -> demand.merge(ingredient, Math.multiplyExact(amount, batches), Math::addExact));
            if (cookTicks > 0) demand.merge(COAL, (int) ((cookTicks + 1599) / 1600), Math::addExact);
            quantities.add(new Quantity(entry.getKey(), step, needed));
        }
        Collections.reverse(quantities);
        return new Plan(quantities);
    }

    private boolean resolve(Identifier item, LinkedHashMap<Identifier, Step> steps, Set<Identifier> ancestors) {
        if (ancestors.contains(item)) return false;
        if (steps.containsKey(item)) return true;
        String source = naturalSource(item);
        if (source != null) {
            steps.put(item, gather(source));
            return true;
        }
        if (ancestors.size() >= 9 || remaining-- <= 0) return false;
        ancestors.add(item);
        try {
            for (var recipe : recipes.getOrDefault(item, List.of())) {
                var candidate = new LinkedHashMap<>(steps);
                var inputs = new LinkedHashMap<Identifier, Integer>();
                boolean possible = true;
                for (var ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    Identifier match = null;
                    var alternatives = Arrays.stream(ingredient.getMatchingStacks())
                            .map(stack -> Registries.ITEM.getId(stack.getItem())).distinct().toList();
                    if (alternatives.size() > 1) {
                        alternatives = alternatives.stream()
                                .sorted(Comparator.comparingInt(this::ingredientAvailability).reversed()).toList();
                    }
                    for (var id : alternatives) {
                        if (resolve(id, candidate, ancestors)) { match = id; break; }
                    }
                    if (match == null) { possible = false; break; }
                    inputs.merge(match, 1, Integer::sum);
                }
                if (!possible || inputs.isEmpty()) continue;
                int cookTime = recipe instanceof SmeltingRecipe smelting ? smelting.getCookTime() : 0;
                if (cookTime > 0 && (inputs.size() != 1 || !resolve(COAL, candidate, ancestors))) continue;
                var kind = cookTime > 0 ? ProjectQueue.Kind.SMELT : ProjectQueue.Kind.CRAFT;
                int yield = recipe.getOutput(client.world.getRegistryManager()).getCount();
                candidate.put(item, new Step(kind, null, recipe.getId(), yield,
                        Collections.unmodifiableMap(inputs), cookTime, recipe instanceof CraftingRecipe && recipe.fits(2, 2)));
                steps.putAll(candidate);
                return true;
            }
            return false;
        } finally {
            ancestors.remove(item);
        }
    }

    private int ingredientAvailability(Identifier item) {
        return ingredientAvailability.computeIfAbsent(item, id -> {
            int best = nearbySources(id);
            for (var recipe : recipes.getOrDefault(id, List.of())) {
                int available = Integer.MAX_VALUE;
                for (var ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    int count = Arrays.stream(ingredient.getMatchingStacks())
                            .mapToInt(stack -> nearbySources(Registries.ITEM.getId(stack.getItem()))).max().orElse(0);
                    available = Math.min(available, count);
                    if (available == 0) break;
                }
                if (available != Integer.MAX_VALUE) best = Math.max(best, available);
            }
            return best;
        });
    }

    private int nearbySources(Identifier item) {
        String source = naturalSource(item);
        if (source == null) return 0;
        if (nearbyBlocks == null) {
            nearbyBlocks = new IdentityHashMap<>();
            if (client.player != null) {
                for (var pos : BlockPos.iterateOutwards(client.player.getBlockPos(), 16, 10, 16)) {
                    if (!client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                    var state = client.world.getBlockState(pos);
                    if (!state.isAir()) nearbyBlocks.merge(state.getBlock(), 1, Integer::sum);
                }
            }
        }
        return ResourceCatalog.blocks(source, "both").stream()
                .mapToInt(block -> nearbyBlocks.getOrDefault(Registries.BLOCK.get(new Identifier(block)), 0)).sum();
    }

    private static Step gather(String source) {
        return new Step(ProjectQueue.Kind.GATHER, source, null, 1, Map.of(), 0, false);
    }

    private static String naturalSource(Identifier item) {
        if (!item.getNamespace().equals("minecraft")) return null;
        String id = item.getPath();
        if (ORES.containsKey(id)) return ORES.get(id);
        if (!(Registries.ITEM.get(item) instanceof BlockItem block)
                || !item.equals(Registries.BLOCK.getId(block.getBlock()))) return null;
        String flower = flowerSource(item);
        if (flower != null) return flower;
        if (id.endsWith("_log") && !id.startsWith("stripped_")
                || id.equals("crimson_stem") || id.equals("warped_stem")
                || Set.of("dirt", "sand", "red_sand", "gravel", "granite", "diorite", "andesite", "tuff",
                        "calcite", "sandstone", "red_sandstone", "netherrack", "end_stone", "basalt", "blackstone",
                        "obsidian", "amethyst_block", "mud", "soul_sand", "soul_soil",
                        "terracotta").contains(id)) {
            return "block:" + item;
        }
        return null;
    }

    static String flowerSource(Identifier item) {
        if (Registries.ITEM.get(item) instanceof BlockItem itemBlock
                && item.equals(Registries.BLOCK.getId(itemBlock.getBlock()))
                && (itemBlock.getBlock() instanceof net.minecraft.block.FlowerBlock
                    || itemBlock.getBlock() instanceof net.minecraft.block.TallFlowerBlock)) {
            return "block:" + item;
        }
        return null;
    }

    private static Identifier mc(String id) { return Identifier.of("minecraft", id); }
}
