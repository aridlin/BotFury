package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;

final class MaterialRecipe {
    private static final Gson JSON = new Gson();
    private record Step(ProjectQueue.Kind kind, String resource, Identifier recipe, int yield,
                Map<Identifier, Integer> ingredients, int cookTime) {}

    private record Quantity(Identifier item, Step step, int count) {}
    record Batch(Plan plan, int count) {}

    private static final class BatchTooLarge extends IllegalArgumentException {
        BatchTooLarge(Identifier item) { super("Split the material batch for " + item); }
    }

    static final class Plan {
        private final List<Quantity> quantities;
        private Plan(List<Quantity> quantities) { this.quantities = List.copyOf(quantities); }

        int size() { return quantities.size(); }
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
                    station = smelting ? furnace : table;
                    if (station == null) {
                        throw new IllegalArgumentException("Place a reachable " + (smelting ? "furnace" : "crafting table")
                                + " near the chest for " + quantity.item);
                    }
                    payload = stationPayload(station);
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
            Map.entry("clay_ball", "block:minecraft:clay"));
    private final Map<Identifier, List<Recipe<?>>> recipes = new HashMap<>();
    private final MinecraftClient client;

    private int remaining = 512;

    MaterialRecipe(MinecraftClient client, boolean canCraft, boolean canSmelt) {
        this.client = client;
        client.world.getRecipeManager().values().stream()
                .filter(recipe -> canCraft && recipe instanceof CraftingRecipe || canSmelt && recipe instanceof SmeltingRecipe)
                .sorted(Comparator.comparing(recipe -> recipe.getId().toString()))
                .forEach(recipe -> {
                    var output = recipe.getOutput(client.world.getRegistryManager());
                    if (!output.isEmpty()) recipes.computeIfAbsent(Registries.ITEM.getId(output.getItem()),
                            ignored -> new ArrayList<>()).add(recipe);
                });
    }

    Plan plan(Identifier item, int count, boolean allowDrops) {
        return quantities(item, count, allowDrops, resolveSteps(item, allowDrops));
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
        var fullBatch = new Batch(quantities(item, lower, false, steps), lower);
        var batches = new ArrayList<Batch>();
        for (; count >= lower; count -= lower) batches.add(fullBatch);
        if (count > 0) batches.add(new Batch(quantities(item, count, false, steps), count));
        return List.copyOf(batches);
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
                    for (var stack : ingredient.getMatchingStacks()) {
                        var id = Registries.ITEM.getId(stack.getItem());
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
                        Collections.unmodifiableMap(inputs), cookTime));
                steps.putAll(candidate);
                return true;
            }
            return false;
        } finally {
            ancestors.remove(item);
        }
    }

    private static Step gather(String source) {
        return new Step(ProjectQueue.Kind.GATHER, source, null, 1, Map.of(), 0);
    }

    private static String naturalSource(Identifier item) {
        if (!item.getNamespace().equals("minecraft")) return null;
        String id = item.getPath();
        if (ORES.containsKey(id)) return ORES.get(id);
        if (!(Registries.ITEM.get(item) instanceof BlockItem block)
                || !item.equals(Registries.BLOCK.getId(block.getBlock()))) return null;
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

    private static Identifier mc(String id) { return Identifier.of("minecraft", id); }
}
