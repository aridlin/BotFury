package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.List;

final class TaskDefense {
    record Config(boolean enabled,String server,String dimension,String owner,List<String> protectedNames) {}
    private static Config config;
    private static long receivedAt,holdUntil;
    private static long lastWalkEvent;
    private static boolean pathPaused;
    private static boolean retreatingFromWarden;
    private static boolean sheltering;
    private static boolean protectingWork;
    private static boolean eating;
    private static Vec3d walkingDirection;
    private static BlockPos retreatStep;
    private static int retreatStartY;
    private static boolean retreatJump;
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
        var client = MinecraftClient.getInstance();
        stopWalking(client);
        stopEating(client);
        ThreatEvasion.clearWardenDetour();
        PhantomShelter.clear();
        protectingWork = false;
        pathPaused=false;
        holdUntil=0;
        sheltering=false;
    }
    static boolean evade(MinecraftClient client,boolean working){
        if(!eligible(client,true)){
            if(pathPaused)BaritoneClient.execute("stop");
            cancelEvasion();return false;
        }
        if (walkingDirection != null && (client.currentScreen != null || client.player.isTouchingWater()
                || retreatStep == null && !client.player.isOnGround()
                || retreatStep != null && client.player.isOnGround()
                    && client.player.getBlockY() != retreatStartY)) {
            stopWalking(client);
            holdUntil = 0;
        }
        if((working||sheltering)&&System.nanoTime()<holdUntil){
            if(walkingDirection!=null)walk(client);
            return true;
        }
        stopWalking(client);
        var action=working ? ThreatEvasion.dodge(client) : null;
        boolean shelter = false;
        if (action == null) {
            action = PhantomShelter.seek(client);
            shelter = action != null;
        }
        sheltering = shelter;
        if(action!=null){
            stopEating(client);
            walkingDirection=action.walkingDirection();
            retreatingFromWarden = action.wardensNearby();
            retreatStep = action.step();
            retreatStartY = client.player.getBlockY();
            if(!pathPaused&&BaritoneClient.available())pathPaused=BaritoneClient.execute("pause");
            if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();
            BotInventory.evaded();
            long now=System.nanoTime();
            if(walkingDirection!=null) walk(client);
            if ((shelter || walkingDirection != null || retreatingFromWarden) && now - lastWalkEvent >= 5_000_000_000L) {
                lastWalkEvent = now;
                String reason = shelter ? "Sheltering from a nearby Phantom"
                        : !retreatingFromWarden ? "Stepping out of a projectile or blast path"
                        : retreatStep != null ? "Changing elevation to escape a Warden"
                        : walkingDirection == null ? "Holding position near a Warden" : "Sneaking away from a Warden";
                BotEvents.add("info", "defense", reason);
            }
            holdUntil = now + (retreatStep == null ? 200_000_000L : 750_000_000L);
            return true;
        }
        if (!working) {
            if (pathPaused) BaritoneClient.execute("stop");
            pathPaused = protectingWork = false;
            stopEating(client);
            ThreatEvasion.clearWardenDetour();
            holdUntil = 0;
        } else if (!protectingWork) resumePath();
        return false;
    }

    private static void walk(MinecraftClient client) {
        var direction = walkingDirection;
        if (retreatStep != null) {
            direction = new Vec3d(retreatStep.getX() + .5 - client.player.getX(), 0,
                    retreatStep.getZ() + .5 - client.player.getZ());
            boolean jump = retreatStep.getY() > retreatStartY && client.player.isOnGround();
            if (jump || retreatJump) client.options.jumpKey.setPressed(jump);
            retreatJump = jump;
        }
        boolean moving = direction.lengthSquared() > .04;
        if (moving) client.player.lookAt(EntityAnchor.EYES, client.player.getEyePos().add(direction));
        client.options.forwardKey.setPressed(moving);
        if (retreatingFromWarden) {
            client.player.setSprinting(false);
            client.options.sneakKey.setPressed(retreatStep == null);
        }
    }

    private static void stopWalking(MinecraftClient client) {
        if(walkingDirection!=null)client.options.forwardKey.setPressed(false);
        if (retreatingFromWarden) client.options.sneakKey.setPressed(false);
        if (retreatJump) client.options.jumpKey.setPressed(false);
        walkingDirection=null;
        retreatingFromWarden = false;
        retreatStep = null;
        retreatJump = false;
    }
    static boolean defendProject(MinecraftClient client) {
        protectingWork = false;
        if (eligible(client, true) && client.currentScreen == null && client.interactionManager != null
                && client.player.currentScreenHandler == client.player.playerScreenHandler
                && client.player.playerScreenHandler.getCursorStack().isEmpty()) {
            var target = LocalFormation.defenseTarget(client, config.owner, config.protectedNames,
                    other -> other instanceof net.minecraft.entity.mob.Monster
                            && !(other instanceof net.minecraft.entity.mob.CreeperEntity || other instanceof WardenEntity));
            if (target != null) {
                stopEating(client);
                protectingWork = true;
                if (equipWeapon(client) && client.player.getAttackCooldownProgress(.5f) >= .95f) {
                    LocalFormation.punch(client, target);
                }
            } else protectingWork = eatWhenNeeded(client);
        }
        if (protectingWork) {
            if (!pathPaused && BaritoneClient.available()) pathPaused = BaritoneClient.execute("pause");
        } else {
            stopEating(client);
            resumePath();
        }
        return protectingWork;
    }

    private static void resumePath() {
        if (!pathPaused) return;
        BaritoneClient.execute("resume");
        pathPaused = false;
    }

    private static boolean equipWeapon(MinecraftClient client) {
        var inventory = client.player.getInventory();
        int slot = -1;
        double best = 0;
        for (int i = 0; i < 36; i++) {
            var stack = inventory.getStack(i);
            var item = stack.getItem();
            double score = item instanceof SwordItem sword ? sword.getAttackDamage() * 1.6
                    : item instanceof AxeItem axe ? axe.getAttackDamage() : 0;
            if (score > best || score == best && score > 0 && i == inventory.selectedSlot) {
                slot = i;
                best = score;
            }
        }
        return slot < 0 || select(client, slot);
    }

    private static boolean eatWhenNeeded(MinecraftClient client) {
        var player = client.player;
        int hunger = player.getHungerManager().getFoodLevel();
        if (hunger >= 18 || (!eating && hunger > 15 && player.getHealth() > player.getMaxHealth() / 2)) return false;
        if (eating && player.isUsingItem()) return true;
        var inventory = player.getInventory();
        int slot = -1;
        double best = 0;
        for (int i = 0; i < 36; i++) {
            var stack = inventory.getStack(i);
            var food = stack.getItem().getFoodComponent();
            if (food == null) continue;
            boolean harmful = food.getStatusEffects().stream().anyMatch(effect -> !effect.getFirst().getEffectType().isBeneficial());
            if (harmful && (hunger > 5 || !stack.isOf(Items.ROTTEN_FLESH))) continue;
            double score = Math.min(20 - hunger, food.getHunger()) + food.getSaturationModifier() - (harmful ? 4 : 0);
            if (slot == -1 || score > best) {
                best = score;
                slot = i;
            }
        }
        if (slot < 0) return false;
        if (!select(client, slot)) return true;
        if (client.interactionManager.interactItem(player, Hand.MAIN_HAND).isAccepted()) {
            if (!eating) BotEvents.add("info", "defense", "Eating carried food");
            eating = true;
            client.options.useKey.setPressed(true);
            return true;
        }
        return false;
    }

    private static boolean select(MinecraftClient client, int slot) {
        var player = client.player;
        var inventory = player.getInventory();
        if (slot == inventory.selectedSlot) return true;
        if (slot < 9) inventory.selectedSlot = slot;
        else client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, inventory.selectedSlot,
                SlotActionType.SWAP, player);
        return false;
    }

    private static void stopEating(MinecraftClient client) {
        if (!eating) return;
        client.options.useKey.setPressed(false);
        if (client.player != null && client.player.isUsingItem() && client.interactionManager != null) {
            client.interactionManager.stopUsingItem(client.player);
        }
        eating = false;
    }

    static boolean tick(MinecraftClient client,boolean working){
        if(!eligible(client,working))return false;
        var safe=new java.util.ArrayList<>(config.protectedNames);
        if(!BotInventory.recipient().isEmpty())safe.add(BotInventory.recipient());
        return LocalFormation.defend(client,config.owner,safe, other -> !(other instanceof WardenEntity));
    }
}
