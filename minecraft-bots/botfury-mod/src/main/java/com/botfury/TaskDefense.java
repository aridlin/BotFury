package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.util.math.Vec3d;
import java.util.List;

final class TaskDefense {
    record Config(boolean enabled,String server,String dimension,String owner,List<String> protectedNames) {}
    private static Config config;
    private static long receivedAt,holdUntil;
    private static long lastWalkEvent;
    private static boolean pathPaused;
    private static boolean retreatingFromWarden;
    private static Vec3d walkingDirection;
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
    static void cancelEvasion(){
        stopWalking(MinecraftClient.getInstance());
        pathPaused=false;
        holdUntil=0;
    }
    static boolean evade(MinecraftClient client,boolean working){
        if(!eligible(client,working)){
            if(pathPaused)BaritoneClient.execute("stop");
            cancelEvasion();return false;
        }
        if(walkingDirection!=null&&(!client.player.isOnGround()||client.currentScreen!=null)){
            stopWalking(client);
            holdUntil=0;
        }
        if(System.nanoTime()<holdUntil){
            if(walkingDirection!=null)walk(client);
            return true;
        }
        stopWalking(client);
        var action=ThreatEvasion.dodge(client);
        if(action!=null){
            walkingDirection=action.walkingDirection();
            retreatingFromWarden = action.wardensNearby();
            if(!pathPaused&&BaritoneClient.available())pathPaused=BaritoneClient.execute("pause");
            if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();
            BotInventory.evaded();
            long now=System.nanoTime();
            if(walkingDirection!=null) walk(client);
            if ((walkingDirection != null || retreatingFromWarden) && now - lastWalkEvent >= 5_000_000_000L) {
                lastWalkEvent = now;
                String reason = !retreatingFromWarden ? "Stepping out of a projectile or blast path"
                        : walkingDirection == null ? "Holding position near a Warden" : "Sneaking away from a Warden";
                BotEvents.add("info", "defense", reason);
            }
            holdUntil=now+200_000_000L;
            return true;
        }
        if(pathPaused){BaritoneClient.execute("resume");pathPaused=false;}
        return false;
    }

    private static void walk(MinecraftClient client) {
        client.player.lookAt(EntityAnchor.EYES, client.player.getEyePos().add(walkingDirection));
        client.options.forwardKey.setPressed(true);
        if (retreatingFromWarden) {
            client.player.setSprinting(false);
            client.options.sneakKey.setPressed(true);
        }
    }

    private static void stopWalking(MinecraftClient client) {
        if(walkingDirection!=null)client.options.forwardKey.setPressed(false);
        if (retreatingFromWarden) client.options.sneakKey.setPressed(false);
        walkingDirection=null;
        retreatingFromWarden = false;
    }
    static boolean defendProject(MinecraftClient client) {
        return eligible(client, true) && client.player.currentScreenHandler == client.player.playerScreenHandler
                && LocalFormation.defend(client, config.owner, config.protectedNames,
                other -> other instanceof net.minecraft.entity.mob.Monster
                        && !(other instanceof net.minecraft.entity.mob.CreeperEntity || other instanceof WardenEntity));
    }

    static boolean tick(MinecraftClient client,boolean working){
        if(!eligible(client,working))return false;
        var safe=new java.util.ArrayList<>(config.protectedNames);
        if(!BotInventory.recipient().isEmpty())safe.add(BotInventory.recipient());
        return LocalFormation.defend(client,config.owner,safe, other -> !(other instanceof WardenEntity));
    }
}
