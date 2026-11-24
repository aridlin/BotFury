package com.botfury;
import java.util.*;

final class ResourceCatalog {
    record Resource(String id,String label,String category,List<String> normal,List<String> deepslate) {}
    static final String FURNACE_STONE = "furnace_stone";
    static final List<String> FURNACE_STONE_DROPS = List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate",
            "minecraft:blackstone");
    private static final List<String> FURNACE_STONE_BLOCKS = List.of("minecraft:stone", "minecraft:deepslate",
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone");
    static final List<Resource> RESOURCES;
    static {
        var list=new ArrayList<Resource>();
        for(String name:List.of("coal","iron","copper","gold","redstone","lapis","diamond","emerald"))
            list.add(new Resource(name,title(name),"Ores",List.of("minecraft:"+name+"_ore"),List.of("minecraft:deepslate_"+name+"_ore")));
        list.add(new Resource("quartz","Nether quartz","Ores",List.of("minecraft:nether_quartz_ore"),List.of()));
        list.add(new Resource("nether_gold","Nether gold","Ores",List.of("minecraft:nether_gold_ore"),List.of()));
        list.add(new Resource("ancient_debris","Ancient debris","Ores",List.of("minecraft:ancient_debris"),List.of()));
        for(String wood:List.of("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry"))
            list.add(new Resource(wood,title(wood),"Wood",List.of("minecraft:"+wood+"_log","minecraft:"+wood+"_wood"),List.of()));
        for(String stone:List.of("stone","deepslate","granite","diorite","andesite","tuff","calcite","sandstone","netherrack","end_stone"))
            list.add(new Resource(stone,title(stone),"Stone",List.of("minecraft:"+stone),List.of()));
        for (String category : List.of("Wood", "Stone")) {
            var members = list.stream().filter(resource -> resource.category.equals(category)).flatMap(resource -> resource.normal.stream()).toList();
            list.add(0, new Resource("any_" + category.toLowerCase(Locale.ROOT), "Any " + category.toLowerCase(Locale.ROOT),
                    category, members, List.of()));
        }
        list.add(new Resource("any_logs", "Any logs", "Wood", list.stream()
                .filter(resource -> resource.id.equals("any_wood")).flatMap(resource -> resource.normal.stream())
                .filter(id -> id.endsWith("_log")).toList(), List.of()));
        list.add(new Resource("dirt", "Dirt", "Stone", List.of("minecraft:dirt", "minecraft:grass_block"), List.of()));
        RESOURCES=List.copyOf(list);
    }
    static String title(String id){String text=id.replace('_',' ');return Character.toUpperCase(text.charAt(0))+text.substring(1);}
    static List<String> blocks(String id,String variant){
        if (FURNACE_STONE.equals(id)) return FURNACE_STONE_BLOCKS;
        if(id.startsWith("block:")) {
            String block=id.substring(6);
            if(!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid block ID");
            return List.of(block);
        }
        Resource resource=RESOURCES.stream().filter(r->r.id.equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown mining resource"));
        if(!Set.of("both","normal","deepslate").contains(variant))throw new IllegalArgumentException("Unknown ore variant");
        if(variant.equals("normal"))return resource.normal;
        if(variant.equals("deepslate")) {
            if(resource.deepslate.isEmpty())throw new IllegalArgumentException("This resource has no deepslate variant");
            return resource.deepslate;
        }
        var result=new ArrayList<>(resource.normal);result.addAll(resource.deepslate);return List.copyOf(result);
    }

    static boolean isOre(String id) {
        String block = id.startsWith("block:") ? id.substring(6) : null;
        return RESOURCES.stream().anyMatch(resource -> resource.category.equals("Ores")
                && (resource.id.equals(id) || block != null
                    && (resource.normal.contains(block) || resource.deepslate.contains(block))));
    }

    static boolean isWood(String resource) {
        String block = resource.startsWith("block:") ? resource.substring(6) : null;
        return RESOURCES.stream().anyMatch(entry -> entry.category.equals("Wood")
                && (entry.id.equals(resource) || block != null && entry.normal.contains(block)));
    }

    static List<String> trackedDrops(String resource, String variant) {
        if (FURNACE_STONE.equals(resource)) return FURNACE_STONE_DROPS;
        var items = new LinkedHashSet<String>();
        String drop = switch (resource) {
            case "iron", "copper", "gold" -> "raw_" + resource;
            case "lapis" -> "lapis_lazuli";
            case "nether_gold" -> "gold_nugget";
            case "stone" -> "cobblestone";
            case "deepslate" -> "cobbled_deepslate";
            case "coal", "redstone", "diamond", "emerald", "quartz" -> resource;
            default -> null;
        };
        if (drop != null) items.add("minecraft:" + drop);
        if (resource.equals("any_stone")) items.addAll(List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate"));
        items.addAll(blocks(resource, variant));
        return List.copyOf(items);
    }
}
