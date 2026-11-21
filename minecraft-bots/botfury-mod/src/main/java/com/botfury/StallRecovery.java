package com.botfury;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/** A short Baritone detour that preserves the caller's route and work assignment. */
final class StallRecovery {
    enum State { FOLLOW_ROUTE, DETOUR, REPLAN }

    private static final long STALL = Duration.ofMinutes(1).toNanos();
    private static final long OVERLAP = Duration.ofSeconds(6).toNanos();
    private static final long DETOUR_TIME = Duration.ofSeconds(7).toNanos();
    private static final long CLEAR_TIME = Duration.ofSeconds(2).toNanos();
    private static final long COOLDOWN = Duration.ofSeconds(12).toNanos();

    private BlockPos route;
    private Vec3d lastPosition;
    private long lastMoved, lastRouteProgress, overlapSince, retryAt;
    private double bestDistance;
    private BlockPos detour, previousDetour;
    private long detourEnds, clearAt;
    private BaritoneSettings settings;

    State tick(MinecraftClient client, BlockPos goal, Predicate<BlockPos> allowed) {
        var player = client.player;
        if (player == null || client.world == null || !player.isAlive()
                || player.currentScreenHandler != player.playerScreenHandler) {
            reset();
            return State.FOLLOW_ROUTE;
        }
        long now = System.nanoTime();
        if (!Objects.equals(route, goal)) {
            reset();
            route = goal;
        }
        if (detour != null) {
            if (clearAt == 0 && player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(detour)) < .64) {
                clearAt = now + CLEAR_TIME;
            }
            if (now < detourEnds && (clearAt == 0 || now < clearAt)) return State.DETOUR;
            endDetour();
            lastPosition = player.getPos();
            lastMoved = lastRouteProgress = now;
            bestDistance = distance(player, goal);
            overlapSince = 0;
            retryAt = now + COOLDOWN;
            return State.REPLAN;
        }
        double distance = distance(player, goal);
        if (lastPosition == null) {
            lastPosition = player.getPos();
            bestDistance = distance;
            lastMoved = lastRouteProgress = now;
        } else if (player.getPos().squaredDistanceTo(lastPosition) >= .81) {
            lastPosition = player.getPos();
            lastMoved = now;
        }
        if (bestDistance - distance >= .75) {
            bestDistance = distance;
            lastRouteProgress = now;
        }
        if (now < retryAt) return State.FOLLOW_ROUTE;
        var blocker = client.world.getOtherEntities(player, player.getBoundingBox().expand(.4, .05, .4),
                entity -> entity instanceof PlayerEntity other && other.isAlive() && !other.isSpectator()
                        && BotCollisionPolicy.known(other.getGameProfile().getName())).stream()
                .map(PlayerEntity.class::cast).min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
        if (blocker == null) overlapSince = 0;
        else if (overlapSince == 0) overlapSince = now;
        boolean overlapStall = overlapSince != 0 && now - overlapSince >= OVERLAP && now - lastMoved >= OVERLAP;
        boolean routeStall = now - lastMoved >= STALL || goal != null && now - lastRouteProgress >= STALL;
        if (!overlapStall && !routeStall) return State.FOLLOW_ROUTE;

        var waypoint = waypoint(client, goal, blocker, allowed);
        if (waypoint == null) {
            retryAt = now + COOLDOWN;
            return State.REPLAN;
        }
        try {
            settings = new BaritoneSettings(false);
        } catch (ReflectiveOperationException | LinkageError error) {
            retryAt = now + COOLDOWN;
            return State.REPLAN;
        }
        if (!BaritoneClient.execute("goto " + waypoint.getX() + " " + waypoint.getY() + " " + waypoint.getZ())) {
            endDetour();
            retryAt = now + COOLDOWN;
            return State.REPLAN;
        }
        detour = previousDetour = waypoint;
        detourEnds = now + DETOUR_TIME;
        BotEvents.add("info", "movement", blocker != null
                ? "Moving aside for fleet bot " + blocker.getGameProfile().getName()
                : "Trying a short detour after a stalled route");
        return State.DETOUR;
    }

    private static double distance(PlayerEntity player, BlockPos destination) {
        return destination == null ? 0 : player.getPos().distanceTo(Vec3d.ofBottomCenter(destination));
    }

    private BlockPos waypoint(MinecraftClient client, BlockPos goal, PlayerEntity blocker, Predicate<BlockPos> allowed) {
        var player = client.player;
        var origin = player.getBlockPos();
        Vec3d away = Vec3d.ZERO;
        if (blocker != null) {
            away = player.getPos().subtract(blocker.getPos()).multiply(1, 0, 1);
            if (away.horizontalLengthSquared() < .04) {
                boolean first = player.getGameProfile().getName().compareToIgnoreCase(blocker.getGameProfile().getName()) < 0;
                away = new Vec3d(first ? -1 : 1, 0, 0);
            }
        }
        var heading = goal == null ? Vec3d.ZERO : Vec3d.ofCenter(goal).subtract(player.getPos()).multiply(1, 0, 1).normalize();
        var outward = away.normalize();
        double best = -Double.MAX_VALUE;
        BlockPos choice = null;
        for (var direction : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            for (int distance = 1; distance <= 3; distance++) {
                var candidate = origin.offset(direction, distance);
                if (!walkable(client, candidate, distance >= 2)) break;
                if (distance < 2 || !allowed.test(candidate)) continue;
                var delta = Vec3d.ofCenter(candidate).subtract(Vec3d.ofCenter(origin)).normalize();
                // Give intersecting peers opposite exits, and favor lateral moves around an ordinary obstruction.
                double score = blocker != null ? 4 * delta.dotProduct(outward) : -2 * Math.abs(delta.dotProduct(heading));
                score += distance * .1;
                if (candidate.equals(previousDetour)) score -= 3;
                if (score > best) {
                    best = score;
                    choice = candidate;
                }
            }
        }
        return choice;
    }

    private static boolean walkable(MinecraftClient client, BlockPos feet, boolean checkOccupants) {
        var world = client.world;
        var player = client.player;
        if (!world.getWorldBorder().contains(feet) || !world.isInBuildLimit(feet.up())
                || !world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)
                || !world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.up()).isEmpty()) return false;
        var floor = world.getBlockState(feet.down());
        if (!floor.isFullCube(world, feet.down()) || floor.isOf(Blocks.MAGMA_BLOCK)) return false;
        var box = player.getBoundingBox().offset(Vec3d.ofBottomCenter(feet).subtract(player.getPos()));
        return world.isSpaceEmpty(player, box) && (!checkOccupants || world.getOtherEntities(player, box.expand(.15, 0, .15),
                entity -> entity instanceof PlayerEntity other && other.isAlive() && !other.isSpectator()).isEmpty());
    }

    void reset() {
        endDetour();
        route = previousDetour = null;
        lastPosition = null;
        lastMoved = lastRouteProgress = overlapSince = retryAt = 0;
    }

    private void endDetour() {
        if (settings != null) {
            BaritoneClient.execute("stop");
            settings.close();
        }
        settings = null;
        detour = null;
        clearAt = 0;
    }
}
