package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/** Uses short paths through existing terrain to put a roof between a vulnerable worker and a Phantom. */
final class PhantomShelter {
    private static final ShelterRoute route = new ShelterRoute();
    private static long nextThreatCheck;
    private static final long THREAT_GRACE = 3_000_000_000L;
    private static Map<Integer, Vec3d> lastPhantoms = Map.of();
    private static long threatenedUntil;

    static void clear() {
        route.clear();
        lastPhantoms = Map.of();
        threatenedUntil = nextThreatCheck = 0;
    }

    static ThreatEvasion.Action seek(MinecraftClient client) {
        long now = System.nanoTime();
        var player = client.player;
        if (now >= nextThreatCheck) {
            nextThreatCheck = now + 250_000_000L;
            boolean injured = player.getHealth() <= player.getMaxHealth() / 2;
            double radius = injured || now < threatenedUntil ? 20 : 8;
            double close = injured ? 6 : 4;
            var current = new HashMap<Integer, Vec3d>();
            var position = player.getPos();
            for (var phantom : client.world.getEntitiesByClass(PhantomEntity.class, player.getBoundingBox().expand(radius),
                    entity -> entity.isAlive() && player.squaredDistanceTo(entity) <= radius * radius)) {
                var observed = phantom.getPos();
                int id = phantom.getId();
                current.put(id, observed);
                var previous = lastPhantoms.get(id);
                double distance = position.distanceTo(observed);
                // Compare both observations from the same position so a moving bot does not look like a diving Phantom.
                if (distance <= close || previous != null && position.distanceTo(previous) - distance >= .25) {
                    threatenedUntil = now + THREAT_GRACE;
                }
            }
            lastPhantoms = current;
        }
        if (now >= threatenedUntil) {
            route.clear();
            return null;
        }
        return route.seek(client, cell -> covered(client, cell));
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
