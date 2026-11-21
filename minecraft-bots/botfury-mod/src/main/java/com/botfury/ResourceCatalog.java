package com.botfury;
import java.util.*;

final class ResourceCatalog {
    record Resource(String id,String label,String category,List<String> normal,List<String> deepslate) {}
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
        RESOURCES=List.copyOf(list);
    }
    static String title(String id){String text=id.replace('_',' ');return Character.toUpperCase(text.charAt(0))+text.substring(1);}
    static List<String> blocks(String id,String variant){
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
}
