package com.botfury;

import java.time.Duration;
import java.util.ArrayDeque;
import net.minecraft.util.math.BlockPos;

final class OreRouteBackoff {
    private static final long RETRY_DELAY = Duration.ofMinutes(3).toNanos();
    private static final int MAX_FAILURES = 24;
    private static final int VEIN_RADIUS = 4;
    private static final int MIN_APPROACH_RADIUS = 24;
    private static final int MAX_APPROACH_RADIUS = 64;

    private record Failure(String world, String resource, BlockPos vein, BlockPos approach,
                           int approachRadius, long expiresAt) {
        boolean matches(String world, String resource, BlockPos ore, BlockPos feet) {
            return this.world.equals(world) && this.resource.equals(resource)
                    && vein.isWithinDistance(ore, VEIN_RADIUS) && approach.isWithinDistance(feet, approachRadius);
        }
    }

    private final ArrayDeque<Failure> failures = new ArrayDeque<>();

    void defer(String world, String resource, BlockPos ore, BlockPos feet, long now) {
        failures.removeIf(failure -> failure.expiresAt <= now || failure.matches(world, resource, ore, feet));
        if (failures.size() >= MAX_FAILURES) failures.removeFirst();
        int distance = (int) Math.ceil(Math.sqrt(ore.getSquaredDistance(feet)));
        int radius = Math.max(MIN_APPROACH_RADIUS, Math.min(MAX_APPROACH_RADIUS, distance));
        failures.addLast(new Failure(world, resource, ore.toImmutable(), feet.toImmutable(), radius, now + RETRY_DELAY));
    }

    boolean deferred(String world, String resource, BlockPos ore, BlockPos feet, long now) {
        return failures.stream().anyMatch(failure -> failure.expiresAt > now && failure.matches(world, resource, ore, feet));
    }
}
