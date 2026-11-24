package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.PickaxeItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/** Makes short, reversible Baritone approaches when a cave does not reach the ore layer. */
final class SubsurfaceAccess {
    private record Passage(BlockPos from, BlockPos to) {
        int clearance() { return 2 + from.getY() - to.getY(); }
    }

    private static final Direction[] HEADINGS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int REACH = 12;
    private static final int HARVEST_RESERVE = 20;
    private static final int[] ROUTE_DISTANCES = {5, 3, 7};
    private static final int[] ROUTE_OFFSETS = {0, -2, 2};
    private BlockPos origin;
    private List<BlockPos> goals = List.of();
    private ProjectGather.Area excluded;
    private int started, movedAt, retryAt, heading;
    private Vec3d lastMovement;
    private BaritoneSettings settings;
    private boolean escapingExploration, handDigging;
    private Passage passage;
    private int passageDeadline;
    private BlockPos stalledAt;
    private int routeFailures;
    private final java.util.LinkedHashSet<Passage> attemptedPassages = new java.util.LinkedHashSet<>();

    boolean active() { return settings != null; }

    static boolean deepResource(String resource) {
        return Integer.valueOf(-54).equals(prospectingAltitude(resource));
    }

    static Integer prospectingAltitude(String resource) {
        if (resource.startsWith("block:minecraft:") && resource.endsWith("_ore")) {
            resource = resource.substring("block:minecraft:".length(), resource.length() - "_ore".length());
            if (resource.startsWith("deepslate_")) resource = resource.substring("deepslate_".length());
        }
        return switch (resource) {
            case "diamond", "redstone" -> -54;
            case "gold" -> -16;
            case "lapis" -> 0;
            case "iron" -> 16;
            case "copper" -> 48;
            default -> null;
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

    boolean openLocalApproach(MinecraftClient client, int age, BlockPos destination, ProjectGather.Area area) {
        var feet = client.player.getBlockPos();
        if (!hasFooting(client) || !destination.isWithinDistance(feet, 9)
                || !validDestination(client, destination, area)
                || (area != null && (area.contains(feet) || area.contains(feet.down())))) return false;
        return start(client, feet, List.of(destination), area, age, false);
    }

    boolean continueLocalApproach(MinecraftClient client, int age) {
        if (!active()) return false;
        var goal = goals.get(0);
        if (!client.player.getBlockPos().equals(goal) && age - started < 160
                && (age - started < 55 || BaritoneClient.pursuing(goal))) return true;
        stop();
        return false;
    }

    boolean escapeExploration(MinecraftClient client, int age, ProjectGather.Area area) {
        if (!hasFooting(client)) return false;
        var player = client.player;
        var feet = player.getBlockPos();
        var world = client.world;
        var exits = new ArrayList<BlockPos>();
        for (var exit : BlockPos.iterateOutwards(feet, 5, 2, 5)) {
            int distance = Math.abs(exit.getX() - feet.getX()) + Math.abs(exit.getZ() - feet.getZ());
            if (distance < 4 || distance > 5 || !validDestination(client, exit, area)) continue;
            var floor = world.getBlockState(exit.down());
            if (!ProjectPlacement.flushFloor(floor, world, exit.down()) || floor.isOf(Blocks.MAGMA_BLOCK)
                    || !world.getFluidState(exit).isEmpty() || !world.getFluidState(exit.up()).isEmpty()) continue;
            var box = player.getBoundingBox().offset(Vec3d.ofBottomCenter(exit).subtract(player.getPos()));
            if (world.isSpaceEmpty(player, box)) exits.add(exit.toImmutable());
        }
        return !exits.isEmpty() && start(client, feet, exits, area, age, false, true);
    }

    boolean continueExplorationExit(MinecraftClient client, int age) {
        if (!escapingExploration) return false;
        var feet = client.player.getBlockPos();
        if (!goals.contains(feet) && age - started < 240
                && (age - started < 40 || BaritoneClient.pursuing(goals.get(0)))) return true;
        stop();
        return false;
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
            if (!arrived) {
                heading++;
                if (position.equals(stalledAt)) routeFailures++;
                else {
                    stalledAt = position;
                    routeFailures = 1;
                }
            } else {
                stalledAt = null;
                routeFailures = 0;
            }
            if (arrived && destinationHint != null) heading = 0;
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
        if (routeFailures >= 2 && position.equals(stalledAt)
                && openPassage(client, age, position, area, initial + heading / 2, height < position.getY())) return true;
        // Shorter, shallower alternatives can get around a wall without returning up the cave.
        int delta = height - position.getY();
        int fallbackY = position.getY() + (delta == 0 ? 1 : Math.abs(delta) <= 2 ? 0 : Integer.signum(delta) * 2);
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

    private boolean openPassage(MinecraftClient client, int age, BlockPos from, ProjectGather.Area area,
                                int heading, boolean descend) {
        if (client.currentScreen != null) return false;
        for (int drop = descend ? 1 : 0; drop >= 0; drop--) {
            for (int turn = 0; turn < HEADINGS.length; turn++) {
                var cell = from.offset(HEADINGS[Math.floorMod(heading + turn, HEADINGS.length)]).down(drop);
                var candidate = new Passage(from, cell);
                if (attemptedPassages.contains(candidate) || attemptedPassages.contains(new Passage(cell, from))
                        || !safePassage(client, candidate, area)) continue;
                var first = passageBlock(client, candidate);
                if (first != null && (ProjectWorker.excavationHit(client, first) == null
                        || !equipDiggingTool(client, false))) continue;
                if (attemptedPassages.size() >= 24) attemptedPassages.remove(attemptedPassages.iterator().next());
                attemptedPassages.add(candidate);
                if (first == null) {
                    if (start(client, from, List.of(cell), area, age, false)) return true;
                    continue;
                }
                passage = candidate;
                passageDeadline = age + 220;
                BotEvents.add("info", "mining", (drop == 0 ? "Opening a short prospecting passage at "
                        : "Opening a safe prospecting step at ") + cell.toShortString());
                return true;
            }
        }
        return false;
    }

    private static BlockPos passageBlock(MinecraftClient client, Passage passage) {
        for (int dy = passage.clearance() - 1; dy >= 0; dy--) {
            var pos = passage.to().up(dy);
            if (!client.world.isAir(pos)) return pos;
        }
        return null;
    }

    boolean continuePassage(MinecraftClient client, int age, ProjectGather.Area area) {
        var opening = passage;
        if (opening == null) return false;
        var position = client.player.getBlockPos();
        if (age >= passageDeadline || !hasFooting(client) || client.currentScreen != null
                || !position.equals(opening.from()) || !safePassage(client, opening, area)) {
            stopPassage();
            return false;
        }
        var next = passageBlock(client, opening);
        if (next == null) {
            stopPassage();
            return start(client, position, List.of(opening.to()), area, age, false);
        }
        var ray = ProjectWorker.excavationHit(client, next);
        if (ray == null || !equipDiggingTool(client, false)) {
            stopPassage();
            return false;
        }
        client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, ray.getPos());
        if (client.interactionManager.updateBlockBreakingProgress(next, ray.getSide())) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
        return true;
    }

    private static boolean safePassage(MinecraftClient client, Passage passage, ProjectGather.Area area) {
        var world = client.world;
        var cell = passage.to();
        var ceiling = cell.up(passage.clearance());
        if (!validDestination(client, cell, area) || !world.isInBuildLimit(ceiling)
                || BuildProtection.blocked(cell.down()) || area != null && area.contains(cell.down())
                || !ProjectPlacement.flushFloor(world.getBlockState(cell.down()), world, cell.down())
                || world.getBlockState(ceiling).getBlock() instanceof FallingBlock
                || !world.getOtherEntities(client.player, new Box(cell).stretch(0, passage.clearance(), 0),
                    entity -> entity instanceof LivingEntity && entity.isAlive()).isEmpty()) {
            return false;
        }
        for (int dy = 0; dy < passage.clearance(); dy++) {
            var block = cell.up(dy);
            if (BuildProtection.blocked(block) || area != null && area.contains(block)
                    || world.getPlayers().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                    && player.getBlockPos().down().equals(block))) return false;
            var state = world.getBlockState(block);
            if (!state.getFluidState().isEmpty()
                    || !state.isAir() && (state.hasBlockEntity() || !BaritoneSettings.recoverableTerrain(state.getBlock()))) return false;
            for (var side : Direction.values()) {
                var neighbor = block.offset(side);
                if (!world.isChunkLoaded(neighbor.getX() >> 4, neighbor.getZ() >> 4)
                        || !world.getFluidState(neighbor).isEmpty()) return false;
            }
        }
        return true;
    }

    private void stopPassage() {
        if (passage == null) return;
        var client = MinecraftClient.getInstance();
        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        passage = null;
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
        return start(client, position, destinations, area, age, returning, false);
    }

    private boolean start(MinecraftClient client, BlockPos position, List<BlockPos> destinations,
                          ProjectGather.Area area, int age, boolean returning, boolean explorationExit) {
        boolean equipped = equipDiggingTool(client, returning);
        if (!equipped && !returning && !explorationExit) return false;
        try {
            settings = explorationExit ? BaritoneSettings.explorationExit(equipped) : BaritoneSettings.prospecting(equipped);
        } catch (ReflectiveOperationException | LinkageError error) {
            return false;
        }
        escapingExploration = explorationExit;
        handDigging = explorationExit && !equipped;
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
        if (client.world == null || !pos.isWithinDistance(origin, escapingExploration ? 6 : REACH)
                || excluded != null && excluded.contains(pos)
                || !BaritoneSettings.recoverableTerrain(client.world.getBlockState(pos).getBlock())) return true;
        if (escapingExploration) {
            var world = client.world;
            var block = world.getBlockState(pos).getBlock();
            if (handDigging && !BaritoneSettings.handDiggableTerrain(block) || world.getBlockState(pos.up()).getBlock() instanceof FallingBlock) {
                return true;
            }
            for (var side : Direction.values()) {
                var neighbor = pos.offset(side);
                if (!world.isChunkLoaded(neighbor.getX() >> 4, neighbor.getZ() >> 4)
                        || !world.getFluidState(neighbor).isEmpty()) return true;
            }
        }
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
        stopPassage();
        if (settings == null) return;
        BaritoneClient.execute("stop");
        settings.close();
        settings = null;
        escapingExploration = handDigging = false;
        origin = null;
        lastMovement = null;
        goals = List.of();
        excluded = null;
    }

    void reset() {
        stop();
        retryAt = heading = 0;
        stalledAt = null;
        routeFailures = 0;
        attemptedPassages.clear();
    }
}
