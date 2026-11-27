package com.botfury;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/** A short Baritone detour that preserves the caller's route and work assignment. */
final class StallRecovery {
    enum State { FOLLOW_ROUTE, DETOUR, REPLAN }

    private static final long STALL = Duration.ofMinutes(1).toNanos();
    private static final long OVERLAP = Duration.ofSeconds(6).toNanos();
    private static final long IDLE_OVERLAP = Duration.ofSeconds(2).toNanos();
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
        var blocker = overlappingBot(client, .4);
        if (blocker == null) overlapSince = 0;
        else if (overlapSince == 0) overlapSince = now;
        boolean overlapStall = overlapSince != 0 && now - overlapSince >= OVERLAP && now - lastMoved >= OVERLAP;
        boolean routeStall = now - lastMoved >= STALL || goal != null && now - lastRouteProgress >= STALL;
        if (!overlapStall && !routeStall) return State.FOLLOW_ROUTE;

        var waypoint = waypoint(client, goal, blocker, allowed);
        if (waypoint == null || !beginDetour(waypoint, now)) {
            retryAt = now + COOLDOWN;
            return State.REPLAN;
        }
        BotEvents.add("info", "movement", blocker != null
                ? "Moving aside for fleet bot " + blocker.getGameProfile().getName()
                : "Trying a short detour after a stalled route");
        return State.DETOUR;
    }

    boolean yieldWhileIdle(MinecraftClient client) {
        var player = client.player;
        if (player == null || client.world == null || !player.isAlive() || player.isTouchingWater()
                || client.currentScreen != null || player.currentScreenHandler != player.playerScreenHandler) {
            reset();
            return false;
        }
        long now = System.nanoTime();
        if (detour != null) {
            if (now < detourEnds && (!player.isOnGround()
                    || player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(detour)) >= .36)) return true;
            endDetour();
            overlapSince = 0;
            retryAt = now + COOLDOWN;
            return false;
        }
        if (now < retryAt || !player.isOnGround()) return false;
        var blocker = overlappingBot(client, .15);
        if (blocker == null) {
            overlapSince = 0;
            return false;
        }
        if (overlapSince == 0) overlapSince = now;
        if (now - overlapSince < IDLE_OVERLAP) return false;

        var step = idleStep(client, outward(player, blocker), candidate ->
                blocker.squaredDistanceTo(Vec3d.ofBottomCenter(candidate)) >= .81);
        if (step == null || !beginDetour(step, now)) {
            retryAt = now + COOLDOWN;
            return false;
        }
        BotEvents.add("info", "movement", "Yielding the work area to fleet bot " + blocker.getGameProfile().getName());
        return true;
    }

    boolean readyToClearPlacement(long now) {
        return detour == null && now >= retryAt;
    }

    boolean clearPlacement(MinecraftClient client, BlockPos target, long now) {
        var player = client.player;
        var blocked = new Box(target);
        if (player == null || client.world == null || !player.isAlive() || !player.isOnGround()
                || player.isTouchingWater() || client.currentScreen != null
                || player.currentScreenHandler != player.playerScreenHandler
                || !readyToClearPlacement(now) || !blocked.intersects(player.getBoundingBox())) return false;
        var away = player.getPos().subtract(Vec3d.ofBottomCenter(target)).multiply(1, 0, 1).normalize();
        var step = idleStep(client, away, candidate -> !blocked.intersects(player.getBoundingBox()
                .offset(Vec3d.ofBottomCenter(candidate).subtract(player.getPos()))));
        if (step == null || !beginDetour(step, now)) {
            retryAt = now + COOLDOWN;
            return false;
        }
        BotEvents.add("info", "movement", "Clearing active placement at " + target.toShortString());
        return true;
    }

    private boolean beginDetour(BlockPos waypoint, long now) {
        try {
            settings = new BaritoneSettings(false);
        } catch (ReflectiveOperationException | LinkageError error) {
            return false;
        }
        if (!BaritoneClient.goTo(waypoint)) {
            endDetour();
            return false;
        }
        detour = previousDetour = waypoint;
        detourEnds = now + DETOUR_TIME;
        return true;
    }

    private static PlayerEntity overlappingBot(MinecraftClient client, double margin) {
        var player = client.player;
        return client.world.getOtherEntities(player, player.getBoundingBox().expand(margin, .05, margin),
                entity -> entity instanceof PlayerEntity other && other.isAlive() && !other.isSpectator()
                        && BotCollisionPolicy.known(other.getGameProfile().getName())).stream()
                .map(PlayerEntity.class::cast).min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    private static BlockPos idleStep(MinecraftClient client, Vec3d away, Predicate<BlockPos> allowed) {
        var player = client.player;
        var origin = player.getBlockPos();
        double bestScore = -Double.MAX_VALUE;
        BlockPos best = null;
        for (var direction : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            var adjacent = origin.offset(direction);
            for (int height = -1; height <= 1; height++) {
                var candidate = adjacent.up(height);
                var point = Vec3d.ofBottomCenter(candidate);
                if (!allowed.test(candidate) || !walkable(client, candidate, true)) continue;
                var travel = point.subtract(player.getPos()).multiply(1, 0, 1);
                if (!ThreatEvasion.walkableStep(client, player.getBoundingBox(), travel, candidate, height)) continue;
                double score = travel.normalize().dotProduct(away) - Math.abs(height) * .2;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static Vec3d outward(PlayerEntity player, PlayerEntity blocker) {
        if (blocker == null) return Vec3d.ZERO;
        var away = player.getPos().subtract(blocker.getPos()).multiply(1, 0, 1);
        if (away.horizontalLengthSquared() >= .04) return away.normalize();
        boolean first = player.getGameProfile().getName().compareToIgnoreCase(blocker.getGameProfile().getName()) < 0;
        return new Vec3d(first ? -1 : 1, 0, 0);
    }

    private static double distance(PlayerEntity player, BlockPos destination) {
        return destination == null ? 0 : player.getPos().distanceTo(Vec3d.ofBottomCenter(destination));
    }

    private BlockPos waypoint(MinecraftClient client, BlockPos goal, PlayerEntity blocker, Predicate<BlockPos> allowed) {
        var player = client.player;
        var origin = player.getBlockPos();
        var heading = goal == null ? Vec3d.ZERO : Vec3d.ofCenter(goal).subtract(player.getPos()).multiply(1, 0, 1).normalize();
        var outward = outward(player, blocker);
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
