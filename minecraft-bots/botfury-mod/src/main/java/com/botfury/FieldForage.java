package com.botfury;

import java.util.Comparator;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

final class FieldForage {
    static final int SEARCH_RADIUS = 32;
    record Source(UUID animal, ProjectQueue.ResourceSite site, String item) {}
    enum Result { WORKING, READY, FAILED }

    private final Source source;
    private final Item item;
    private final BlockPos origin;
    private final DroppedItemSearch pickup = new DroppedItemSearch(12);
    private BlockPos goal;
    private Vec3d lastSeen;
    private BaritoneSettings settings;
    private int age, lastSeenAt;

    FieldForage(Source source) {
        this.source = source;
        item = Registries.ITEM.get(new Identifier(source.item));
        origin = new BlockPos(source.site.x(), source.site.y(), source.site.z());
    }

    static Item food(AnimalEntity animal) {
        if (animal.isBaby() || animal.hasCustomName() || animal.isLeashed()) return null;
        var type = animal.getType();
        if (type == EntityType.COW) return Items.BEEF;
        if (type == EntityType.PIG) return Items.PORKCHOP;
        if (type == EntityType.SHEEP) return Items.MUTTON;
        if (type == EntityType.RABBIT) return Items.RABBIT;
        return null;
    }

    static Source available(MinecraftClient client) {
        var player = client.player;
        return client.world.getEntitiesByClass(AnimalEntity.class, player.getBoundingBox().expand(SEARCH_RADIUS, 20, SEARCH_RADIUS),
                animal -> {
                    var meat = food(animal);
                    return animal.isAlive() && meat != null && !BuildProtection.blocked(animal.getBlockPos())
                            && player.squaredDistanceTo(animal) <= SEARCH_RADIUS * SEARCH_RADIUS
                            && ProjectInventory.canFit(client, meat.getDefaultStack(), 1);
                }).stream().min(Comparator.comparingDouble(player::squaredDistanceTo)).map(animal -> {
                    var pos = animal.getBlockPos();
                    return new Source(animal.getUuid(), new ProjectQueue.ResourceSite(pos.getX(), pos.getY(), pos.getZ()),
                            Registries.ITEM.getId(food(animal)).toString());
                }).orElse(null);
    }

    Result tick(MinecraftClient client) {
        age++;
        var player = client.player;
        if (player.getInventory().count(item) > 0) return Result.READY;
        if (age >= 800 || !ProjectInventory.canFit(client, item.getDefaultStack(), 1)) return Result.FAILED;
        var animal = client.world.getEntitiesByClass(AnimalEntity.class, new Box(origin).expand(16),
                candidate -> candidate.getUuid().equals(source.animal)).stream().findFirst().orElse(null);
        if (animal == null || !animal.isAlive()) {
            stopApproach();
            if (lastSeen == null || age - lastSeenAt > 160) return Result.FAILED;
            pickup.tickNear(client, stack -> stack.isOf(item), lastSeen, 6, stack -> 0);
            return Result.WORKING;
        }
        var pos = animal.getBlockPos();
        if (food(animal) != item || BuildProtection.blocked(pos)) return Result.FAILED;
        lastSeen = animal.getPos();
        lastSeenAt = age;
        if (player.squaredDistanceTo(animal) <= 9 && player.canSee(animal)) {
            stopApproach();
            if (TaskDefense.equipWeapon(client) && player.getAttackCooldownProgress(.5f) >= .95f) {
                LocalFormation.punch(client, animal);
            }
        } else if (age % 10 == 1) {
            if (settings == null) {
                try {
                    settings = new BaritoneSettings(false);
                } catch (ReflectiveOperationException | LinkageError unavailable) {
                    return Result.FAILED;
                }
            }
            if (!pos.equals(goal) || !BaritoneClient.pursuing(pos)) {
                goal = pos;
                if (!BaritoneClient.goTo(pos)) return Result.FAILED;
            }
        }
        return Result.WORKING;
    }

    private void stopApproach() {
        if (settings == null) return;
        BaritoneClient.execute("stop");
        settings.close();
        settings = null;
        goal = null;
    }

    void close() {
        stopApproach();
        pickup.reset();
    }
}
