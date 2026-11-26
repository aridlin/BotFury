package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Follows loaded item entities and falls back to another drop if the route stalls. */
final class DroppedItemSearch {
    enum State { SEARCHING, APPROACHING, UNAVAILABLE }

    private static final int ROUTE_RETRY_TICKS = 30;
    private final int radius;
    private final Map<UUID, Integer> retry = new HashMap<>();
    private ItemEntity target;
    private BlockPos goal;
    private Vec3d progress;
    private int age, started, lastProgress, nextScan, nextPath;
    private BaritoneSettings settings;

    DroppedItemSearch() { this(32); }

    DroppedItemSearch(int radius) { this.radius = radius; }

    State tick(MinecraftClient client, Item item) {
        return tick(client, stack -> stack.isOf(item), null);
    }

    State tick(MinecraftClient client, Predicate<ItemStack> matches, Vec3d minedAt) {
        return tick(client, matches, minedAt, null, entity -> true, stack -> 0);
    }

    State approachReserved(MinecraftClient client, ItemEntity reserved) {
        return tick(client, stack -> !stack.isEmpty(), null, reserved, entity -> true, stack -> 0);
    }

    State tickNear(MinecraftClient client, Predicate<ItemStack> matches, Vec3d center, int distance,
                   ToIntFunction<ItemStack> priority) {
        return tick(client, matches, null, null, entity -> entity.squaredDistanceTo(center) <= distance * distance, priority);
    }

    private State tick(MinecraftClient client, Predicate<ItemStack> matches, Vec3d minedAt, ItemEntity reserved,
                       Predicate<ItemEntity> allowed, ToIntFunction<ItemStack> priority) {
        age++;
        var player = client.player;
        if (target != null) {
            if (!target.isAlive() || !allowed.test(target) || !matches.test(target.getStack())
                    || player.squaredDistanceTo(target) > radius * radius) {
                stop();
            } else if (age - started >= 400 || age - lastProgress >= 120) {
                retry.put(target.getUuid(), age + 200);
                stop();
            } else if (player.getPos().squaredDistanceTo(progress) >= 1) {
                progress = player.getPos();
                lastProgress = age;
            }
        }
        if (target == null && age >= nextScan) {
            retry.values().removeIf(retryAt -> retryAt <= age);
            nextScan = age + 5;
            target = reserved != null ? reserved : client.world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(radius),
                    entity -> entity.isAlive() && allowed.test(entity) && matches.test(entity.getStack())
                            && player.squaredDistanceTo(entity) <= radius * radius
                            && !retry.containsKey(entity.getUuid())
                            && (minedAt == null || Math.abs(entity.getX() - minedAt.x) <= 5
                            && Math.abs(entity.getZ() - minedAt.z) <= 5)).stream()
                    .min(Comparator.<ItemEntity>comparingInt(entity -> priority.applyAsInt(entity.getStack()))
                            .thenComparingDouble(player::squaredDistanceTo)).orElse(null);
            if (target != null) {
                try {
                    settings = new BaritoneSettings(false);
                } catch (ReflectiveOperationException | LinkageError error) {
                    stop();
                    return State.UNAVAILABLE;
                }
                progress = player.getPos();
                started = lastProgress = age;
            }
        }
        if (target == null) return State.SEARCHING;
        if (minedAt != null && !target.isOnGround() && age - started < 20) return State.APPROACHING;
        var next = target.getBlockPos();
        if (next.equals(goal)) {
            if (age < nextPath || next.equals(player.getBlockPos()) || BaritoneClient.pursuing(next)) {
                return State.APPROACHING;
            }
        } else if (goal != null && age % 5 != 0) {
            return State.APPROACHING;
        }
        goal = next;
        nextPath = age + ROUTE_RETRY_TICKS;
        if (BaritoneClient.goTo(goal)) return State.APPROACHING;
        stop();
        return State.UNAVAILABLE;
    }

    void repath() {
        goal = null;
        lastProgress = age;
    }

    void stop() {
        if (settings != null) {
            BaritoneClient.execute("stop");
            settings.close();
        }
        settings = null;
        target = null;
        goal = null;
    }

    void reset() {
        stop();
        retry.clear();
        age = nextScan = 0;
    }
}
