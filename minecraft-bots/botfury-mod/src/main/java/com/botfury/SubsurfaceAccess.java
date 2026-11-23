package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.PickaxeItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Makes short, reversible Baritone approaches when a cave does not reach the ore layer. */
final class SubsurfaceAccess {
    private static final Direction[] HEADINGS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int REACH = 12;
    private BlockPos origin, goal;
    private ProjectGather.Area excluded;
    private int started, retryAt, heading;
    private BaritoneSettings settings;

    boolean active() { return settings != null; }

    boolean advance(MinecraftClient client, int age, int depth, ProjectGather.Area area) {
        return advance(client, age, depth, area, null);
    }

    boolean advanceToward(MinecraftClient client, int age, BlockPos destination, ProjectGather.Area area) {
        return advance(client, age, destination.getY(), area, destination);
    }

    private boolean advance(MinecraftClient client, int age, int height, ProjectGather.Area area, BlockPos destinationHint) {
        var position = client.player.getBlockPos();
        if (active()) {
            if (!position.equals(goal) && age - started < 600
                    && (age - started < 60 || BaritoneClient.pursuing(goal))) return true;
            if (!position.equals(goal)) heading++;
            stop();
        }
        if (age < retryAt) return true;
        if (!client.player.isOnGround()) return false;
        if (area != null && (area.contains(position) || area.contains(position.down()))) return false;

        int initial = destinationHint == null ? Math.floorMod(client.player.getUuid().hashCode(), HEADINGS.length)
                : headingToward(position, destinationHint);
        int segmentY = position.getY() + Math.max(-4, Math.min(4, height - position.getY()));
        for (int attempt = 0; attempt < HEADINGS.length; attempt++) {
            var direction = HEADINGS[Math.floorMod(initial + heading + attempt, HEADINGS.length)];
            var destination = position.offset(direction, 7).withY(segmentY);
            if (!client.world.isInBuildLimit(destination.down()) || !client.world.getWorldBorder().contains(destination)
                    || !client.world.isChunkLoaded(destination.getX() >> 4, destination.getZ() >> 4)
                    || area != null && (area.contains(destination) || area.contains(destination.up()))
                    || BuildProtection.blocked(destination) || BuildProtection.blocked(destination.up())) continue;
            boolean returning = destinationHint != null;
            if (!equipDiggingTool(client, returning) && !returning) return false;
            try {
                settings = BaritoneSettings.prospecting();
            } catch (ReflectiveOperationException | LinkageError error) {
                return false;
            }
            origin = position;
            goal = destination;
            excluded = area;
            started = age;
            heading += attempt;
            retryAt = age + 20;
            if (BaritoneClient.goTo(goal)) return true;
            stop();
            return false;
        }
        return false;
    }

    private int headingToward(BlockPos from, BlockPos destination) {
        int x = destination.getX() - from.getX();
        int z = destination.getZ() - from.getZ();
        if (Math.abs(x) > Math.abs(z)) return x > 0 ? 1 : 3;
        return z > 0 ? 2 : 0;
    }

    boolean blocksBreaking(MinecraftClient client, BlockPos pos) {
        if (!active()) return false;
        if (client.world == null || !pos.isWithinDistance(origin, REACH)
                || excluded != null && excluded.contains(pos)
                || !BaritoneSettings.recoverableTerrain(client.world.getBlockState(pos).getBlock())) return true;
        return client.world.getPlayers().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                && player.getBlockPos().down().equals(pos));
    }

    private boolean equipDiggingTool(MinecraftClient client, boolean returning) {
        var player = client.player;
        var inventory = player.getInventory();
        int best = -1;
        int level = Integer.MAX_VALUE;
        for (int index = 0; index < 36; index++) {
            var stack = inventory.getStack(index);
            if (stack.getItem() instanceof PickaxeItem pickaxe
                    && stack.getMaxDamage() - stack.getDamage() > (returning ? 0 : 20)
                    && pickaxe.getMaterial().getMiningLevel() < level) {
                best = index;
                level = pickaxe.getMaterial().getMiningLevel();
            }
        }
        if (best < 0) return false;
        if (best < 9) {
            inventory.selectedSlot = best;
        } else {
            if (player.currentScreenHandler != player.playerScreenHandler
                    || !player.playerScreenHandler.getCursorStack().isEmpty()) return false;
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, best, inventory.selectedSlot,
                    SlotActionType.SWAP, player);
        }
        return true;
    }

    void stop() {
        if (settings == null) return;
        BaritoneClient.execute("stop");
        settings.close();
        settings = null;
        origin = goal = null;
        excluded = null;
    }

    void reset() {
        stop();
        retryAt = heading = 0;
    }
}
