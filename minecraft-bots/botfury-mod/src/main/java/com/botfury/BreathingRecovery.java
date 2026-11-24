package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashSet;

/** Gives submerged project workers a chance to reach air before returning to their lease. */
final class BreathingRecovery {
    private static final int START_AIR = 180;
    private static final int SEARCH_STEPS = 18;
    private static final int SEARCH_CELLS = 2048;
    private static final Direction[] SWIM_DIRECTIONS = {
            Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    private record Step(BlockPos feet, BlockPos first, int distance) {}

    private boolean recovering;
    private boolean paused;
    private BlockPos waypoint;
    private int ticksToSearch;

    boolean tick(MinecraftClient client) {
        var player = client.player;
        boolean submerged = player.isSubmergedInWater();
        boolean hasAir = recovering ? !submerged && player.getAir() >= player.getMaxAir() - 20
                : !submerged || player.getAir() > START_AIR;
        if (!player.isAlive() || hasAir) {
            stop(client);
            return false;
        }

        if (!recovering) {
            recovering = true;
            TaskDefense.cancelEvasion();
            paused = BaritoneClient.execute("pause");
            if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
            BotEvents.add("info", "projects", "Swimming toward air");
        }
        if (client.currentScreen != null) player.closeHandledScreen();

        if (!submerged) {
            swim(client, false, false, player.isTouchingWater());
            return true;
        }

        if (--ticksToSearch <= 0 || waypoint != null && player.getPos().squaredDistanceTo(center(waypoint)) < .12) {
            waypoint = nextStep(client);
            ticksToSearch = 12;
        }
        if (waypoint == null) {
            swim(client, false, false, true);
            return true;
        }

        var direction = center(waypoint).subtract(player.getPos());
        boolean moving = direction.horizontalLengthSquared() > .09;
        if (moving) {
            player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
                    player.getEyePos().add(direction.x, 0, direction.z));
        }
        swim(client, moving, direction.y < -.35, direction.y > .07);
        return true;
    }

    void stop(MinecraftClient client) {
        if (!recovering) return;
        swim(client, false, false, false);
        if (paused) BaritoneClient.execute("resume");
        recovering = paused = false;
        waypoint = null;
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

    private static BlockPos nextStep(MinecraftClient client) {
        var start = client.player.getBlockPos();
        var open = new ArrayDeque<Step>();
        var seen = new HashSet<BlockPos>();
        open.add(new Step(start, null, 0));
        seen.add(start);
        while (!open.isEmpty()) {
            var step = open.removeFirst();
            if (step.first != null && client.world.getFluidState(step.feet.up()).isEmpty()) return step.first;
            if (step.distance >= SEARCH_STEPS || seen.size() >= SEARCH_CELLS) continue;
            for (var direction : SWIM_DIRECTIONS) {
                var next = step.feet.offset(direction);
                if (!seen.add(next) || !swimmable(client, next)) continue;
                open.addLast(new Step(next, step.first == null ? next : step.first, step.distance + 1));
            }
        }
        return null;
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
