package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.item.BowItem;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;
import java.util.List;

final class RangedShelter {
    private static final int RANGE = 16;
    private static final long COVER_GRACE = 4_000_000_000L;
    private static final ShelterRoute route = new ShelterRoute();
    private static List<AbstractSkeletonEntity> archers = List.of();
    private static long nextScan;
    private static long threatenedUntil;

    static void clear() {
        route.clear();
        archers = List.of();
        nextScan = threatenedUntil = 0;
    }

    static ThreatEvasion.Action seek(MinecraftClient client) {
        var player = client.player;
        long now = System.nanoTime();
        if (player.getHealth() > player.getMaxHealth() * .7f) {
            if (!archers.isEmpty()) clear();
            return null;
        }
        if (now >= nextScan) {
            nextScan = now + 250_000_000L;
            archers = client.world.getEntitiesByClass(AbstractSkeletonEntity.class, player.getBoundingBox().expand(RANGE),
                            archer -> archer.isAlive() && player.squaredDistanceTo(archer) <= RANGE * RANGE
                                    && (archer.isAttacking() || archers.contains(archer))
                                    && archer.isHolding(stack -> stack.getItem() instanceof BowItem))
                    .stream().sorted(Comparator.comparingDouble(player::squaredDistanceTo)).limit(3).toList();
            if (archers.isEmpty()) {
                route.clear();
                threatenedUntil = 0;
            } else if (!covered(client, player.getPos())) {
                threatenedUntil = now + COVER_GRACE;
            }
        }
        if (now >= threatenedUntil) {
            archers = List.of();
            route.clear();
            return null;
        }
        var feet = player.getBlockPos();
        return route.seek(client, cell -> covered(client, cell.equals(feet) ? player.getPos() : Vec3d.ofBottomCenter(cell)));
    }

    private static boolean covered(MinecraftClient client, Vec3d feet) {
        var eye = feet.add(0, client.player.getEyeY() - client.player.getY(), 0);
        var torso = feet.add(0, client.player.getHeight() * .5, 0);
        for (var archer : archers) {
            var source = archer.getEyePos();
            if (!blocked(client, archer, source, eye) || !blocked(client, archer, source, torso)) return false;
        }
        return true;
    }

    private static boolean blocked(MinecraftClient client, AbstractSkeletonEntity archer, Vec3d source, Vec3d target) {
        var ray = new RaycastContext(source, target, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, archer);
        return client.world.raycast(ray).getType() == HitResult.Type.BLOCK;
    }
}
