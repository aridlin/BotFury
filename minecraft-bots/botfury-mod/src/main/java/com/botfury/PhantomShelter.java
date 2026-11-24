package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashSet;

/** Uses short paths through existing terrain to put a roof between a vulnerable worker and a Phantom. */
final class PhantomShelter {
    private static final int MAX_STEPS = 12;
    private static final ArrayDeque<BlockPos> route = new ArrayDeque<>();
    private static long nextThreatCheck;
    private static long nextSearch;
    private static boolean threatened;
    private record Step(BlockPos cell, Step previous, int distance) {}

    static void clear() {
        route.clear();
        threatened = false;
        nextThreatCheck = nextSearch = 0;
    }

    static ThreatEvasion.Action seek(MinecraftClient client) {
        long now = System.nanoTime();
        var player = client.player;
        if (now >= nextThreatCheck) {
            nextThreatCheck = now + 250_000_000L;
            double radius = threatened || player.getHealth() <= player.getMaxHealth() / 2 ? 20 : 8;
            threatened = !client.world.getEntitiesByClass(PhantomEntity.class, player.getBoundingBox().expand(radius),
                    phantom -> phantom.isAlive() && player.squaredDistanceTo(phantom) <= radius * radius).isEmpty();
        }
        if (!threatened) {
            route.clear();
            return null;
        }
        if (client.currentScreen != null || player.isTouchingWater() || !player.isOnGround()) return null;
        var feet = player.getBlockPos();
        if (covered(client, feet)) return new ThreatEvasion.Action(null, false, null);
        if (route.isEmpty() && now >= nextSearch) {
            nextSearch = now + 1_000_000_000L;
            search(client, feet);
        }
        while (!route.isEmpty()) {
            var next = route.getFirst();
            var direction = new Vec3d(next.getX() + .5 - player.getX(), 0, next.getZ() + .5 - player.getZ());
            int height = next.getY() - feet.getY();
            if (height == 0 && direction.lengthSquared() <= .04) {
                route.removeFirst();
                continue;
            }
            if (Math.abs(height) <= 1 && direction.lengthSquared() <= 3
                    && ThreatEvasion.walkableStep(client, player.getBoundingBox(), direction, next, height)) {
                return new ThreatEvasion.Action(direction, false, next);
            }
            route.clear();
        }
        return null;
    }

    private static void search(MinecraftClient client, BlockPos start) {
        var player = client.player;
        var initial = player.getPos();
        var body = player.getBoundingBox();
        var open = new ArrayDeque<Step>();
        var seen = new HashSet<BlockPos>();
        open.add(new Step(start, null, 0));
        seen.add(start);
        while (!open.isEmpty()) {
            var step = open.removeFirst();
            if (covered(client, step.cell)) {
                for (var point = step; point.previous != null; point = point.previous) route.addFirst(point.cell);
                return;
            }
            if (step.distance >= MAX_STEPS) continue;
            var from = step.distance == 0 ? initial : Vec3d.ofBottomCenter(step.cell);
            var bounds = body.offset(from.subtract(initial));
            for (var side : Direction.Type.HORIZONTAL) {
                var adjacent = step.cell.offset(side);
                if (!client.world.isChunkLoaded(adjacent.getX() >> 4, adjacent.getZ() >> 4)) continue;
                var direction = new Vec3d(adjacent.getX() + .5 - from.x, 0, adjacent.getZ() + .5 - from.z);
                for (int height = -1; height <= 1; height++) {
                    var next = adjacent.up(height);
                    if (seen.contains(next) || !ThreatEvasion.walkableStep(client, bounds, direction, next, height)) continue;
                    seen.add(next);
                    open.addLast(new Step(next, step, step.distance + 1));
                }
            }
        }
    }

    private static boolean covered(MinecraftClient client, BlockPos feet) {
        for (int height = 2; height <= 4; height++) {
            var roof = feet.up(height);
            if (!solid(client, roof)) continue;
            int edges = 0;
            for (var side : Direction.Type.HORIZONTAL) {
                if (solid(client, roof.offset(side))) edges++;
            }
            if (edges >= 2) return true;
        }
        return false;
    }

    private static boolean solid(MinecraftClient client, BlockPos pos) {
        return client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                && client.world.getBlockState(pos).isFullCube(client.world, pos);
    }
}
