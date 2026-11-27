package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.List;

final class TaskDefense {
    enum Mode {
        AUTO("auto"), ON("on"), OFF("off");

        final String wire;

        Mode(String wire) { this.wire = wire; }

        static Mode parse(String wire) {
            return switch (wire) {
                case "auto" -> AUTO;
                case "on" -> ON;
                case "off" -> OFF;
                default -> throw new IllegalArgumentException("Invalid task defense mode");
            };
        }

        boolean enabled(boolean projectWorker) { return this == ON || this == AUTO && projectWorker; }

        Mode next() {
            return switch (this) {
                case AUTO -> ON;
                case ON -> OFF;
                case OFF -> AUTO;
            };
        }
    }

    record Config(Mode mode,String server,String dimension,String owner,List<String> protectedNames) {}
    private static Config config;
    private static long receivedAt,holdUntil;
    private static long lastWalkEvent;
    private static boolean pathPaused;
    private static boolean retreatingFromWarden;
    private static String shelterStatus;
    private static boolean protectingWork;
    private static boolean eating;
    private static long mealStartedAt, mealConfirmedAt;
    private static int hungerBeforeMeal;
    private static AnimalEntity prey;
    private static Vec3d walkingDirection;
    private static BlockPos retreatStep;
    private static int retreatStartY;
    private static boolean retreatJump;
    static Config parse(String value){
        String[] parts=value.split("\\|",-1);
        if(parts.length!=5
                ||!parts[3].matches("[A-Za-z0-9_]{1,16}")||parts[1].isBlank()||parts[2].isBlank())
            throw new IllegalArgumentException("Invalid task defense configuration");
        return new Config(Mode.parse(parts[0]),parts[1],parts[2],parts[3],BotFriends.parse(parts[4]));
    }
    static void configure(Config next){config=next;receivedAt=System.nanoTime();}
    static boolean enabled(){return config!=null&&config.mode.enabled(ProjectWorker.enabled())&&System.nanoTime()-receivedAt<8_000_000_000L;}

    static boolean hasMeal(MinecraftClient client) {
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            if (!stack.isEmpty() && emergencyFood(stack.getItem())) return true;
        }
        return false;
    }

    static boolean requestsMeal(MinecraftClient client) {
        var player = client.player;
        if (!enabled() || !player.isAlive()) return false;
        int hunger = player.getHungerManager().getFoodLevel();
        return (hunger <= 6 || hunger < 18 && player.getHealth() <= player.getMaxHealth() / 2)
                && !hasMeal(client);
    }

    static boolean emergencyFood(net.minecraft.item.Item item) {
        return nourishing(item) || rawForageFood(item);
    }

    static boolean rawForageFood(net.minecraft.item.Item item) {
        return item == Items.BEEF || item == Items.PORKCHOP
                || item == Items.MUTTON || item == Items.RABBIT;
    }

    static FieldForage.Source accessibleFoodSource(MinecraftClient client) {
        var player = client.player;
        if (!eligible(client, true) || player.getHungerManager().getFoodLevel() < 15
                || player.getHealth() <= player.getMaxHealth() / 2) return null;
        return FieldForage.available(client);
    }

    static boolean recentlyAteMeal(MinecraftClient client) {
        long now = System.nanoTime();
        if (mealStartedAt != 0) {
            if (now - mealStartedAt >= 3_000_000_000L) mealStartedAt = 0;
            else if (client.player.getHungerManager().getFoodLevel() > hungerBeforeMeal) {
                mealStartedAt = 0;
                mealConfirmedAt = now;
            }
        }
        return mealConfirmedAt != 0 && now - mealConfirmedAt < 8_000_000_000L;
    }

    static boolean nourishing(net.minecraft.item.Item item) {
        var food = item.getFoodComponent();
        return food != null && food.getHunger() >= 4
                && food.getStatusEffects().stream().allMatch(effect -> effect.getFirst().getEffectType().isBeneficial());
    }

    static String spareMeal(MinecraftClient client) {
        var player = client.player;
        if (player.getHungerManager().getFoodLevel() < 15 || player.getHealth() <= player.getMaxHealth() / 2) return null;
        var inventory = player.getInventory();
        net.minecraft.item.Item raw = null;
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            var item = stack.getItem();
            if (!stack.isEmpty() && nourishing(item) && inventory.count(item) >= 3) {
                return net.minecraft.registry.Registries.ITEM.getId(item).toString();
            }
            if (!stack.isEmpty() && raw == null && rawForageFood(item)) raw = item;
        }
        return raw == null ? null : net.minecraft.registry.Registries.ITEM.getId(raw).toString();
    }
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
        ThreatEvasion.clearDetour();
        PhantomShelter.clear();
        RangedShelter.clear();
        protectingWork = false;
        pathPaused=false;
        holdUntil=0;
        shelterStatus=null;
        prey = null;
    }
    static String evasionStatus() { return shelterStatus == null ? "Avoiding danger" : shelterStatus; }

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
        if((working||shelterStatus!=null)&&System.nanoTime()<holdUntil){
            if(walkingDirection!=null)walk(client);
            else if (shelterStatus != null) defendInPlace(client);
            return true;
        }
        stopWalking(client);
        var action=working ? ThreatEvasion.dodge(client) : null;
        shelterStatus = null;
        if (action == null) {
            action = RangedShelter.seek(client);
            if (action != null) shelterStatus = "Taking cover from skeleton fire";
        }
        if (action == null) {
            action = PhantomShelter.seek(client);
            if (action != null) shelterStatus = "Sheltering from a nearby Phantom";
        }
        if(action!=null){
            walkingDirection=action.walkingDirection();
            if (shelterStatus != null && walkingDirection == null) defendInPlace(client);
            else stopEating(client);
            retreatingFromWarden = action.wardensNearby();
            retreatStep = action.step();
            retreatStartY = client.player.getBlockY();
            if(!pathPaused&&BaritoneClient.available())pathPaused=BaritoneClient.execute("pause");
            if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();
            BotInventory.evaded();
            long now=System.nanoTime();
            if(walkingDirection!=null) walk(client);
            if ((shelterStatus != null || walkingDirection != null || retreatingFromWarden) && now - lastWalkEvent >= 5_000_000_000L) {
                lastWalkEvent = now;
                String reason = shelterStatus != null ? shelterStatus
                        : !retreatingFromWarden && retreatStep != null ? "Changing elevation to escape a blast"
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
            ThreatEvasion.clearDetour();
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
    private static boolean defendInPlace(MinecraftClient client) {
        if (mealStartedAt != 0) recentlyAteMeal(client);
        if (eligible(client, true) && client.currentScreen == null && client.interactionManager != null
                && client.player.currentScreenHandler == client.player.playerScreenHandler
                && client.player.playerScreenHandler.getCursorStack().isEmpty()) {
            var target = LocalFormation.defenseTarget(client, config.owner, config.protectedNames,
                    other -> other instanceof net.minecraft.entity.mob.Monster
                            && !(other instanceof net.minecraft.entity.mob.CreeperEntity || other instanceof WardenEntity));
            if (target != null) {
                stopEating(client);
                attackThreat(client, target);
                return true;
            }
            if (eatWhenNeeded(client)) return true;
            if (forageInReach(client)) return true;
        }
        prey = null;
        stopEating(client);
        return false;
    }

    private static boolean forageInReach(MinecraftClient client) {
        var player = client.player;
        if (player.getHungerManager().getFoodLevel() > 6 || player.getInventory().getEmptySlot() < 0) return false;
        if (!client.world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(3),
                entity -> entity.isAlive() && emergencyFood(entity.getStack().getItem())).isEmpty()) return false;
        var target = LocalFormation.defenseTarget(client, config.owner, config.protectedNames,
                other -> other instanceof AnimalEntity animal && FieldForage.food(animal) != null
                        && !BuildProtection.blocked(animal.getBlockPos()));
        if (!(target instanceof AnimalEntity animal)) return false;
        if (prey != animal) {
            prey = animal;
            BotEvents.add("info", "defense", "Foraging for food: " + animal.getName().getString());
        }
        attackThreat(client, animal);
        return true;
    }

    static boolean defendProject(MinecraftClient client) {
        protectingWork = defendInPlace(client);
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

    static void attackThreat(MinecraftClient client, net.minecraft.entity.LivingEntity target) {
        var defense = config;
        String owner = defense == null ? client.player.getGameProfile().getName() : defense.owner;
        LocalFormation.attack(client, target, owner, defense == null ? List.of() : defense.protectedNames, true);
    }

    static boolean equipWeapon(MinecraftClient client, boolean avoidSword) {
        var inventory = client.player.getInventory();
        int slot = -1;
        double best = 0;
        for (int i = 0; i < 36; i++) {
            var stack = inventory.getStack(i);
            var item = stack.getItem();
            if (avoidSword && item instanceof SwordItem) continue;
            double score = item instanceof SwordItem sword ? sword.getAttackDamage() * 1.6
                    : item instanceof AxeItem axe ? axe.getAttackDamage() : avoidSword ? 1 : 0;
            if (score > best || score == best && score > 0 && i == inventory.selectedSlot) {
                slot = i;
                best = score;
            }
        }
        return slot < 0 ? !avoidSword : select(client, slot);
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
            if (emergencyFood(inventory.getMainHandStack().getItem())) {
                mealStartedAt = System.nanoTime();
                hungerBeforeMeal = hunger;
            }
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
