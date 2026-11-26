package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Finds air for submerged workers and dry footing for stalled station approaches. */
final class BreathingRecovery {
    private static final int START_AIR = 180;
    private static final int SEARCH_STEPS = 18;
    private static final int SEARCH_CELLS = 2048;
    private static final int SHORE_STALL_TICKS = 36;
    private static final int SHORE_SWIM_TICKS = 200;
    private static final int SHORE_STEP_STALL_TICKS = 24;
    private static final long SHORE_RETRY_TICKS = 800;
    private static final int MAX_REJECTED_SHORE_STEPS = 32;
    private static final Direction[] SWIM_DIRECTIONS = {
            Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    private record Step(BlockPos feet, BlockPos first, int distance) {}
    private record ShoreStep(BlockPos from, BlockPos to) {}

    private boolean recovering;
    private boolean paused;
    private boolean reachingShore;
    private BlockPos waypoint;
    private int ticksToSearch;
    private Vec3d shoreProbe;
    private int stationaryTicks;
    private int shoreTicksLeft;
    private final Map<ShoreStep, Long> rejectedShoreSteps = new LinkedHashMap<>();
    private ClientWorld shoreWorld;
    private ShoreStep shoreStep;
    private double closestToShoreStep;
    private int shoreStepStall;

    boolean reachingShore() { return reachingShore; }

    boolean tick(MinecraftClient client, boolean needsDryFooting) {
        var player = client.player;
        boolean submerged = player.isSubmergedInWater();
        boolean hasAir = recovering && !reachingShore ? !submerged && player.getAir() >= player.getMaxAir() - 20
                : !submerged || player.getAir() > START_AIR;
        if (shoreWorld != client.world || !player.isAlive()) {
            rejectedShoreSteps.clear();
            shoreWorld = client.world;
        }
        if (!player.isAlive()) {
            stop(client);
            return false;
        }
        if (hasAir && recovering && !reachingShore) stop(client);
        if (!hasAir) {
            if (reachingShore) {
                reachingShore = false;
                ticksToSearch = 0;
            }
        } else if (needsDryFooting && (player.isTouchingWater() || reachingShore && !onDryFooting(client))) {
            if (!reachingShore) {
                var position = player.getPos();
                if (shoreProbe == null || shoreProbe.squaredDistanceTo(position) >= .36) {
                    shoreProbe = position;
                    stationaryTicks = 0;
                }
                if (++stationaryTicks < SHORE_STALL_TICKS) return false;
                stationaryTicks = 0;
                selectShoreStep(client);
                if (waypoint == null) return false;
                reachingShore = true;
                shoreTicksLeft = SHORE_SWIM_TICKS;
                ticksToSearch = 12;
                BotEvents.add("info", "projects", "Swimming to dry footing");
            }
            if (--shoreTicksLeft <= 0) {
                stop(client);
                return false;
            }
        } else {
            stop(client);
            return false;
        }

        if (!recovering) {
            recovering = true;
            TaskDefense.cancelEvasion();
            paused = BaritoneClient.execute("pause");
            if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
            if (!reachingShore) BotEvents.add("info", "projects", "Swimming toward air");
        }
        if (client.currentScreen != null) player.closeHandledScreen();

        if (!reachingShore && !submerged) {
            swim(client, false, false, player.isTouchingWater());
            return true;
        }

        if (reachingShore) {
            advanceShoreStep(client);
        } else if (--ticksToSearch <= 0 || waypoint != null && player.getPos().squaredDistanceTo(center(waypoint)) < .12) {
            waypoint = nextStep(client, false, Map.of());
            ticksToSearch = 12;
        }
        if (waypoint == null) {
            if (reachingShore) {
                stop(client);
                return false;
            }
            swim(client, false, false, true);
            return true;
        }

        var direction = center(waypoint).subtract(player.getPos());
        var alignment = reachingShore ? alignWithShoreStep(player.getPos(), shoreStep) : null;
        if (alignment != null) direction = alignment;
        boolean moving = direction.horizontalLengthSquared() > (alignment == null ? .09 : .01);
        if (moving) {
            player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
                    player.getEyePos().add(direction.x, 0, direction.z));
        }
        swim(client, moving, direction.y < -.35, direction.y > .07);
        return true;
    }

    private static Vec3d alignWithShoreStep(Vec3d position, ShoreStep step) {
        if (step == null) return null;
        var from = center(step.from);
        double rise = from.y - position.y;
        // Square up to the passage before crossing a narrow water cell or climbing its bank.
        if (step.from.getX() != step.to.getX() && Math.abs(from.z - position.z) > .16) {
            return new Vec3d(0, rise, from.z - position.z);
        }
        if (step.from.getZ() != step.to.getZ() && Math.abs(from.x - position.x) > .16) {
            return new Vec3d(from.x - position.x, rise, 0);
        }
        return null;
    }

    private void selectShoreStep(MinecraftClient client) {
        long now = client.world.getTime();
        rejectedShoreSteps.values().removeIf(expiry -> expiry <= now);
        waypoint = nextStep(client, true, rejectedShoreSteps);
        shoreStep = waypoint == null ? null : new ShoreStep(client.player.getBlockPos(), waypoint);
        closestToShoreStep = Double.POSITIVE_INFINITY;
        shoreStepStall = 0;
    }

    private void advanceShoreStep(MinecraftClient client) {
        if (shoreStep != null) {
            double distance = client.player.getPos().squaredDistanceTo(center(waypoint));
            if (distance < .12) {
                selectShoreStep(client);
                return;
            }
            if (distance + .015 < closestToShoreStep) {
                closestToShoreStep = distance;
                shoreStepStall = 0;
            } else if (++shoreStepStall >= SHORE_STEP_STALL_TICKS) {
                if (rejectedShoreSteps.size() >= MAX_REJECTED_SHORE_STEPS) {
                    rejectedShoreSteps.remove(rejectedShoreSteps.keySet().iterator().next());
                }
                rejectedShoreSteps.put(shoreStep, client.world.getTime() + SHORE_RETRY_TICKS);
                BotEvents.add("debug", "projects", "Trying another swim route after stalling at " + waypoint.toShortString());
                selectShoreStep(client);
            }
        }
    }

    void stop(MinecraftClient client) {
        if (client.world == null) {
            rejectedShoreSteps.clear();
            shoreWorld = null;
        }
        shoreProbe = null;
        stationaryTicks = 0;
        if (!recovering) return;
        swim(client, false, false, false);
        if (paused) BaritoneClient.execute("resume");
        recovering = paused = false;
        reachingShore = false;
        waypoint = null;
        shoreStep = null;
        ticksToSearch = 0;
    }

    private static void swim(MinecraftClient client, boolean forward, boolean down, boolean up) {
        client.options.forwardKey.setPressed(forward);
        client.options.sneakKey.setPressed(down);
        client.options.jumpKey.setPressed(up);
    }

    private static Vec3d center(BlockPos pos) {
        return Vec3d.ofBottomCenter(pos).add(0, .2, 0);
    }

    private static BlockPos nextStep(MinecraftClient client, boolean shore, Map<ShoreStep, Long> rejected) {
        var start = client.player.getBlockPos();
        var open = new ArrayDeque<Step>();
        var seen = new HashSet<BlockPos>();
        open.add(new Step(start, null, 0));
        seen.add(start);
        BlockPos isolatedLanding = null;
        while (!open.isEmpty()) {
            var step = open.removeFirst();
            if (step.first != null) {
                if (!shore && client.world.getFluidState(step.feet.up()).isEmpty()) return step.first;
                if (shore && dryStand(client, step.feet)) {
                    if (canWalkAway(client, step.feet)) return step.first;
                    if (isolatedLanding == null) isolatedLanding = step.first;
                    continue;
                }
            }
            if (step.distance >= SEARCH_STEPS || seen.size() >= SEARCH_CELLS) continue;
            for (var direction : SWIM_DIRECTIONS) {
                var next = step.feet.offset(direction);
                if (shore && direction.getAxis().isHorizontal()) {
                    var bank = next.up();
                    if (!rejected.containsKey(new ShoreStep(step.feet, bank))
                            && swimmable(client, step.feet.up()) && swimmable(client, bank) && dryStand(client, bank)) {
                        var first = step.first == null ? bank : step.first;
                        if (canWalkAway(client, bank)) return first;
                        if (isolatedLanding == null) isolatedLanding = first;
                    }
                }
                if (seen.contains(next) || !swimmable(client, next)) continue;
                if (shore && (rejected.containsKey(new ShoreStep(step.feet, next))
                        || client.world.getFluidState(next).isEmpty() && !dryStand(client, next))) continue;
                seen.add(next);
                open.addLast(new Step(next, step.first == null ? next : step.first, step.distance + 1));
            }
        }
        return isolatedLanding;
    }

    private static boolean canWalkAway(MinecraftClient client, BlockPos feet) {
        for (var side : Direction.Type.HORIZONTAL) {
            var adjacent = feet.offset(side);
            if (swimmable(client, adjacent) && dryStand(client, adjacent)) return true;
            var upper = adjacent.up();
            if (swimmable(client, feet.up()) && swimmable(client, upper) && dryStand(client, upper)) return true;
            var lower = adjacent.down();
            if (swimmable(client, adjacent) && swimmable(client, lower) && dryStand(client, lower)) return true;
        }
        return false;
    }

    static boolean onDryFooting(MinecraftClient client) {
        var player = client.player;
        return !player.isTouchingWater() && dryStand(client, player.getBlockPos()) && SubsurfaceAccess.hasFooting(client);
    }

    private static boolean dryStand(MinecraftClient client, BlockPos feet) {
        var world = client.world;
        var floor = feet.down();
        return world.getFluidState(feet).isEmpty() && world.getFluidState(feet.up()).isEmpty()
                && world.getFluidState(floor).isEmpty()
                && ProjectPlacement.flushFloor(world.getBlockState(floor), world, floor);
    }

    private static boolean swimmable(MinecraftClient client, BlockPos feet) {
        var world = client.world;
        if (!world.isInBuildLimit(feet.up()) || !world.getWorldBorder().contains(feet)
                || !world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        var lower = world.getFluidState(feet);
        var upper = world.getFluidState(feet.up());
        if (!lower.isIn(FluidTags.WATER) && !lower.isEmpty() || !upper.isIn(FluidTags.WATER) && !upper.isEmpty()) return false;
        double x = feet.getX(), y = feet.getY(), z = feet.getZ();
        return world.isSpaceEmpty(client.player, new Box(x + .2, y, z + .2, x + .8, y + 1.8, z + .8));
    }
}
