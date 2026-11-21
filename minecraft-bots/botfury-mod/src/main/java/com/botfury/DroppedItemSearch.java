package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Follows loaded item entities and falls back to another drop if the route stalls. */
final class DroppedItemSearch {
    enum State { SEARCHING, APPROACHING, UNAVAILABLE }

    private static final int RADIUS = 32;
    private final Map<UUID, Integer> retry = new HashMap<>();
    private ItemEntity target;
    private BlockPos goal;
    private Vec3d progress;
    private int age, started, lastProgress, nextScan;
    private BaritoneSettings settings;

    State tick(MinecraftClient client, Item item) {
        age++;
        var player = client.player;
        if (target != null) {
            if (!target.isAlive() || !target.getStack().isOf(item) || player.squaredDistanceTo(target) > RADIUS * RADIUS) {
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
            target = client.world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(RADIUS),
                    entity -> entity.isAlive() && entity.getStack().isOf(item)
                            && player.squaredDistanceTo(entity) <= RADIUS * RADIUS
                            && !retry.containsKey(entity.getUuid())).stream()
                    .min(Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
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
        var next = target.getBlockPos();
        if (next.equals(goal) || (goal != null && age % 5 != 0)) return State.APPROACHING;
        goal = next;
        if (BaritoneClient.execute("goto " + goal.getX() + " " + goal.getY() + " " + goal.getZ())) return State.APPROACHING;
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
