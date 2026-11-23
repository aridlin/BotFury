package com.botfury;

import net.minecraft.client.MinecraftClient;
import java.util.List;

final class TaskDefense {
    record Config(boolean enabled,String server,String dimension,String owner,List<String> protectedNames) {}
    private static Config config;
    private static long receivedAt,holdUntil;
    private static boolean pathPaused;
    static Config parse(String value){
        String[] parts=value.split("\\|",-1);
        if(parts.length!=5||!java.util.Set.of("on","off").contains(parts[0])
                ||!parts[3].matches("[A-Za-z0-9_]{1,16}")||parts[1].isBlank()||parts[2].isBlank())
            throw new IllegalArgumentException("Invalid task defense configuration");
        return new Config(parts[0].equals("on"),parts[1],parts[2],parts[3],BotFriends.parse(parts[4]));
    }
    static void configure(Config next){config=next;receivedAt=System.nanoTime();}
    static boolean enabled(){return config!=null&&config.enabled&&System.nanoTime()-receivedAt<8_000_000_000L;}
    private static boolean eligible(MinecraftClient client,boolean working){
        var here=LocalFormation.here();
        return working&&Boolean.getBoolean("botfury.managedHttp")&&enabled()&&here!=null
                &&client.player!=null&&client.world!=null&&client.player.isAlive()
                &&config.server.equals(here.server())&&config.dimension.equals(here.dimension());
    }
    static void cancelEvasion(){pathPaused=false;holdUntil=0;}
    static boolean evade(MinecraftClient client,boolean working){
        if(!eligible(client,working)){
            if(pathPaused)BaritoneClient.execute("stop");
            cancelEvasion();return false;
        }
        if(System.nanoTime()<holdUntil)return true;
        if(ThreatEvasion.dodge(client)){
            if(!pathPaused&&BaritoneClient.available())pathPaused=BaritoneClient.execute("pause");
            if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();
            BotInventory.evaded();
            holdUntil=System.nanoTime()+200_000_000L;
            return true;
        }
        if(pathPaused){BaritoneClient.execute("resume");pathPaused=false;}
        return false;
    }
    static boolean defendProject(MinecraftClient client) {
        return eligible(client, true) && client.player.currentScreenHandler == client.player.playerScreenHandler
                && LocalFormation.defend(client, config.owner, config.protectedNames,
                other -> other instanceof net.minecraft.entity.mob.Monster
                        && !(other instanceof net.minecraft.entity.mob.CreeperEntity));
    }

    static boolean tick(MinecraftClient client,boolean working){
        if(!eligible(client,working))return false;
        var safe=new java.util.ArrayList<>(config.protectedNames);
        if(!BotInventory.recipient().isEmpty())safe.add(BotInventory.recipient());
        return LocalFormation.defend(client,config.owner,safe);
    }
}
