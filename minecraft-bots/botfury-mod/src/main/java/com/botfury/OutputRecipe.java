package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;

final class OutputRecipe {
    record Step(ProjectQueue.Kind kind, String resource, Identifier recipe, int yield,
                Map<Identifier, Integer> ingredients, int cookTime) {}

    static final Identifier COAL = mc("coal");
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

    OutputRecipe(MinecraftClient client, boolean canCraft, boolean canSmelt) {
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

    Map<Identifier, Step> plan(Identifier item) {
        var steps = new LinkedHashMap<Identifier, Step>();
        if (!resolve(item, steps, new HashSet<>())) steps.put(item, gather("dropped"));
        return steps;
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
