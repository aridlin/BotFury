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
    private static final int NORMAL_FALL = 3;
    private static final int CARGO_FALL_LIMIT = 5;
    private static final float CARGO_FALL_MIN_HEALTH = 16;
    private final int radius;
    private final boolean recoveryDescent;
    private final Map<UUID, Integer> retry = new HashMap<>();
    private ItemEntity target;
    private BlockPos goal;
    private Vec3d progress;
    private int age, started, lastProgress, nextScan, nextPath;
    private BaritoneSettings settings;
    private boolean controlledDrop;

    DroppedItemSearch() { this(32); }

    DroppedItemSearch(int radius) { this(radius, false); }

    DroppedItemSearch(int radius, boolean recoveryDescent) {
        this.radius = radius;
        this.recoveryDescent = recoveryDescent;
    }

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
            if (reserved != null) {
                if (!retry.containsKey(reserved.getUuid())) target = reserved;
            } else {
                target = client.world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(radius),
                        entity -> entity.isAlive() && allowed.test(entity) && matches.test(entity.getStack())
                                && player.squaredDistanceTo(entity) <= radius * radius
                                && !retry.containsKey(entity.getUuid())
                                && (minedAt == null || Math.abs(entity.getX() - minedAt.x) <= 5
                                && Math.abs(entity.getZ() - minedAt.z) <= 5)).stream()
                        .min(Comparator.<ItemEntity>comparingInt(entity -> priority.applyAsInt(entity.getStack()))
                                .thenComparingDouble(player::squaredDistanceTo)).orElse(null);
            }
            if (target != null) {
                try {
                    settings = recoveryDescent ? BaritoneSettings.surfaceGathering() : new BaritoneSettings(false);
                    if (recoveryDescent) settings.setWalkingFallLimit(NORMAL_FALL);
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
        if (controlledDrop && (player.getHealth() < CARGO_FALL_MIN_HEALTH || !next.equals(goal))) {
            settings.setWalkingFallLimit(NORMAL_FALL);
            controlledDrop = false;
            BaritoneClient.execute("stop");
            goal = null;
        }
        if (recoveryDescent && !controlledDrop && age - lastProgress >= 40 && !BaritoneClient.pursuing(next)
                && canDescendToCargo(client, target, next) && settings.setWalkingFallLimit(CARGO_FALL_LIMIT)) {
            controlledDrop = true;
            goal = null;
            BotEvents.add("info", "projects", "Taking a short ledge drop to recover supplies");
        }
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

    private static boolean canDescendToCargo(MinecraftClient client, ItemEntity item, BlockPos landing) {
        var player = client.player;
        var feet = player.getBlockPos();
        int drop = feet.getY() - landing.getY();
        return drop > NORMAL_FALL && drop <= CARGO_FALL_LIMIT && landing.withY(feet.getY()).isWithinDistance(feet, 5)
                && player.getHealth() >= CARGO_FALL_MIN_HEALTH && SubsurfaceAccess.hasFooting(client)
                && !player.isTouchingWater() && item.isOnGround()
                && ProjectWorker.knownTrailFooting(client, landing);
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
        controlledDrop = false;
        target = null;
        goal = null;
    }

    void reset() {
        stop();
        retry.clear();
        age = nextScan = 0;
    }
}
