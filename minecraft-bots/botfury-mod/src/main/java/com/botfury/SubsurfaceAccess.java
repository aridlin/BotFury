package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.PickaxeItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** Makes short, reversible Baritone approaches when a cave does not reach the ore layer. */
final class SubsurfaceAccess {
    private static final Direction[] HEADINGS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int REACH = 12;
    private static final int HARVEST_RESERVE = 20;
    private static final int[] ROUTE_DISTANCES = {5, 3, 7};
    private static final int[] ROUTE_OFFSETS = {0, -2, 2};
    static final int PROSPECTING_DEPTH = -54;
    private BlockPos origin;
    private List<BlockPos> goals = List.of();
    private ProjectGather.Area excluded;
    private int started, movedAt, retryAt, heading;
    private Vec3d lastMovement;
    private BaritoneSettings settings;

    boolean active() { return settings != null; }

    static boolean deepResource(String resource) {
        return switch (resource) {
            case "diamond", "redstone", "block:minecraft:diamond_ore", "block:minecraft:deepslate_diamond_ore",
                    "block:minecraft:redstone_ore", "block:minecraft:deepslate_redstone_ore" -> true;
            default -> false;
        };
    }

    static boolean canDigRoute(MinecraftClient client) { return diggingToolSlot(client, false) >= 0; }

    static boolean hasFooting(MinecraftClient client) {
        var player = client.player;
        if (player.isOnGround()) return true;
        if (player.isTouchingWater() || player.isClimbing() || Math.abs(player.getVelocity().y) > .12) return false;
        // A stationary client can briefly clear its ground flag while standing on a block edge.
        return client.world.getBlockCollisions(player, player.getBoundingBox().offset(0, -.08, 0)).iterator().hasNext();
    }

    boolean advance(MinecraftClient client, int age, int depth, ProjectGather.Area area) {
        return advance(client, age, depth, area, null);
    }

    boolean advanceToward(MinecraftClient client, int age, BlockPos destination, ProjectGather.Area area) {
        return advance(client, age, destination.getY(), area, destination);
    }

    private boolean advance(MinecraftClient client, int age, int height, ProjectGather.Area area, BlockPos destinationHint) {
        var position = client.player.getBlockPos();
        if (active()) {
            var feet = client.player.getPos();
            if (feet.squaredDistanceTo(lastMovement) >= .36) {
                lastMovement = feet;
                movedAt = age;
            }
            boolean arrived = goals.contains(position);
            if (!arrived && age - started < 600 && age - movedAt < 240
                    && (age - started < 60 || BaritoneClient.pursuing(goals.get(0)))) return true;
            if (!arrived) heading++;
            else if (destinationHint != null) heading = 0;
            stop();
        }
        if (age < retryAt) return true;
        if (!hasFooting(client)) return false;
        if (area != null && (area.contains(position) || area.contains(position.down()))) return false;

        int segmentY = position.getY() + Math.max(-4, Math.min(4, height - position.getY()));
        if (destinationHint != null) {
            var exits = returnExits(client, position, destinationHint, area, segmentY);
            return !exits.isEmpty() && start(client, position, exits, area, age, true);
        }
        int initial = Math.floorMod(client.player.getUuid().hashCode(), HEADINGS.length);
        // Shorter, shallower alternatives can get around a wall without returning up the cave.
        int delta = height - position.getY();
        int fallbackY = position.getY() + Math.max(-2, Math.min(2, delta));
        for (int attempt = 0; attempt < HEADINGS.length * 2; attempt++) {
            int phase = Math.floorMod(heading + attempt, HEADINGS.length * 2);
            var direction = HEADINGS[Math.floorMod(initial + phase / 2, HEADINGS.length)];
            var exits = corridorExits(client, position, direction, area, phase % 2 == 0 ? segmentY : fallbackY);
            if (!exits.isEmpty()) {
                heading += attempt;
                return start(client, position, exits, area, age, false);
            }
        }
        return false;
    }

    private List<BlockPos> returnExits(MinecraftClient client, BlockPos position, BlockPos destination,
                                       ProjectGather.Area area, int segmentY) {
        int phase = Math.floorMod(heading, 4);
        int turn = switch (phase) {
            case 2 -> 1;
            case 3 -> -1;
            default -> 0;
        };
        var forward = HEADINGS[Math.floorMod(headingToward(position, destination) + turn, HEADINGS.length)];
        int exitY = phase == 0 ? segmentY : Math.min(segmentY, position.getY() + 2);
        var exits = corridorExits(client, position, forward, area, exitY);
        if (exits.isEmpty()) heading++;
        return exits;
    }

    private static List<BlockPos> corridorExits(MinecraftClient client, BlockPos position, Direction forward,
                                                ProjectGather.Area area, int exitY) {
        var exits = new ArrayList<BlockPos>();
        for (int distance : ROUTE_DISTANCES) for (int lateral : ROUTE_OFFSETS) {
            var exit = position.offset(forward, distance).offset(forward.rotateYClockwise(), lateral).withY(exitY);
            if (!validDestination(client, exit, area)) continue;
            var floor = client.world.getBlockState(exit.down());
            if (!ProjectPlacement.flushFloor(floor, client.world, exit.down()) || floor.isOf(Blocks.MAGMA_BLOCK)
                    || !client.world.getFluidState(exit).isEmpty() || !client.world.getFluidState(exit.up()).isEmpty()) continue;
            exits.add(exit);
        }
        return exits;
    }

    private static boolean validDestination(MinecraftClient client, BlockPos destination, ProjectGather.Area area) {
        return client.world.isInBuildLimit(destination.down()) && client.world.getWorldBorder().contains(destination)
                && client.world.isChunkLoaded(destination.getX() >> 4, destination.getZ() >> 4)
                && (area == null || !area.contains(destination) && !area.contains(destination.up()))
                && !BuildProtection.blocked(destination) && !BuildProtection.blocked(destination.up());
    }

    private boolean start(MinecraftClient client, BlockPos position, List<BlockPos> destinations,
                          ProjectGather.Area area, int age, boolean returning) {
        if (!equipDiggingTool(client, returning) && !returning) return false;
        try {
            settings = BaritoneSettings.prospecting();
        } catch (ReflectiveOperationException | LinkageError error) {
            return false;
        }
        origin = position;
        goals = destinations;
        excluded = area;
        started = age;
        movedAt = age;
        lastMovement = client.player.getPos();
        retryAt = age + 20;
        if (BaritoneClient.goToAny(goals)) return true;
        stop();
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

    private static int diggingToolSlot(MinecraftClient client, boolean returning) {
        var inventory = client.player.getInventory();
        int best = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int index = 0; index < 36; index++) {
            var stack = inventory.getStack(index);
            if (!(stack.getItem() instanceof PickaxeItem pickaxe)) continue;
            int level = pickaxe.getMaterial().getMiningLevel();
            int reserve = !returning && level >= 2 ? HARVEST_RESERVE : 0;
            if (stack.getMaxDamage() - stack.getDamage() <= reserve) continue;
            int priority = returning ? level : level == 1 ? -1 : level;
            if (priority < bestPriority) {
                best = index;
                bestPriority = priority;
            }
        }
        return best;
    }

    private boolean equipDiggingTool(MinecraftClient client, boolean returning) {
        var player = client.player;
        var inventory = player.getInventory();
        int best = diggingToolSlot(client, returning);
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
        origin = null;
        lastMovement = null;
        goals = List.of();
        excluded = null;
    }

    void reset() {
        stop();
        retryAt = heading = 0;
    }
}
