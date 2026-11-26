package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.PickaxeItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.MultifaceGrowthBlock;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.hit.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/** Makes short, reversible Baritone approaches when a cave does not reach the ore layer. */
final class SubsurfaceAccess {
    // Increment when a new return method can recover a previously unreachable route.
    static final int RETURN_ROUTE_REVISION = 10;
    private record Passage(BlockPos from, BlockPos to) {
        boolean climbing() { return to.getY() > from.getY(); }
        int clearance() { return 2 + Math.max(0, from.getY() - to.getY()); }

        List<BlockPos> blocksToClear() {
            var blocks = new ArrayList<BlockPos>(3);
            if (climbing()) blocks.add(from.up(2));
            for (int dy = clearance() - 1; dy >= 0; dy--) blocks.add(to.up(dy));
            return blocks;
        }
    }

    private static final Direction[] HEADINGS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int REACH = 12;
    private static final int HARVEST_RESERVE = 32;
    private static final int ASCENT_CHECKPOINT = 12;
    private static final int DESCENT_RECOVERY_TICKS = 240;
    private static final int RETURN_RECOVERY_TICKS = 400;
    private static final int[] ROUTE_DISTANCES = {5, 3, 7};
    private static final int[] ROUTE_OFFSETS = {0, -2, 2};
    private BlockPos origin;
    private List<BlockPos> goals = List.of();
    private ProjectGather.Area excluded;
    private int started, movedAt, retryAt, heading;
    private Vec3d lastMovement;
    private BaritoneSettings settings;
    private int ascentGoal = Integer.MIN_VALUE, lastAscentProbe = Integer.MIN_VALUE;
    private boolean escapingExploration, handDigging;
    private Passage passage;
    private int passageDeadline;
    private BlockPos stalledAt;
    private int routeFailures;
    private int lowestProspectingY = Integer.MAX_VALUE;
    private int nextDescentRecovery;
    private boolean localDescent, localAscent;
    private int highestReturnY = Integer.MIN_VALUE, nextReturnRecovery;
    private final java.util.LinkedHashSet<Passage> attemptedPassages = new java.util.LinkedHashSet<>();

    boolean active() { return settings != null; }
    boolean openingReturnStep() { return passage != null && passage.climbing(); }
    boolean followingNaturalAscent() { return ascentGoal != Integer.MIN_VALUE; }
    boolean continuingLocalDescent() { return localDescent; }
    boolean continuingLocalAscent() { return localAscent; }
    boolean walkingLocalAscent(MinecraftClient client) {
        return localAscent && active() && !goals.contains(client.player.getBlockPos());
    }

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

    static boolean hasBasicRoutePickaxe(MinecraftClient client) {
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            if (stack.getItem() instanceof PickaxeItem pickaxe && pickaxe.getMaterial().getMiningLevel() < 2
                    && stack.getMaxDamage() - stack.getDamage() >= 12) return true;
        }
        return false;
    }

    static boolean hasFooting(MinecraftClient client) {
        var player = client.player;
        if (player.isOnGround()) return true;
        if (player.isTouchingWater() || player.isClimbing() || Math.abs(player.getVelocity().y) > .12) return false;
        // A stationary client can briefly clear its ground flag while standing on a block edge.
        return client.world.getBlockCollisions(player, player.getBoundingBox().offset(0, -.08, 0)).iterator().hasNext();
    }

    boolean advance(MinecraftClient client, int age, int depth, ProjectGather.Area area) {
        return advance(client, age, depth, area, null, false, true);
    }

    boolean advanceToward(MinecraftClient client, int age, BlockPos destination, ProjectGather.Area area,
                          boolean allowNaturalAscent) {
        return advance(client, age, destination.getY(), area, destination, allowNaturalAscent, true);
    }

    boolean walkTowardSurface(MinecraftClient client, int age, BlockPos destination) {
        return advance(client, age, destination.getY(), null, destination, true, false);
    }

    static boolean localApproachInRange(BlockPos feet, BlockPos destination, boolean prospecting) {
        int drop = feet.getY() - destination.getY();
        return destination.isWithinDistance(feet, 9) || prospecting && drop > 4 && drop <= 16
                && destination.withY(feet.getY()).isWithinDistance(feet, 9);
    }

    boolean openLocalApproach(MinecraftClient client, int age, BlockPos destination, ProjectGather.Area area,
                              boolean prospecting) {
        var feet = client.player.getBlockPos();
        if (!hasFooting(client) || !validDestination(client, destination, area)
                || (area != null && (area.contains(feet) || area.contains(feet.down())))) return false;
        if (prospecting && openTowardMiningStand(client, age, feet, destination, area)) return true;
        return destination.isWithinDistance(feet, 9) && start(client, feet, List.of(destination), area, age, false);
    }

    boolean openMiningPickupStep(MinecraftClient client, int age, BlockPos destination, ProjectGather.Area area) {
        var feet = client.player.getBlockPos();
        return destination.isWithinDistance(feet, 5) && !destination.equals(feet)
                && hasFooting(client) && validDestination(client, destination, area)
                && openTowardMiningStand(client, age, feet, destination, area);
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

    private boolean advance(MinecraftClient client, int age, int height, ProjectGather.Area area,
                            BlockPos destinationHint, boolean allowNaturalAscent, boolean allowPassage) {
        var position = client.player.getBlockPos();
        boolean descending = destinationHint == null && position.getY() > height;
        if (descending && position.getY() < lowestProspectingY && hasFooting(client)) {
            lowestProspectingY = position.getY();
            nextDescentRecovery = age + DESCENT_RECOVERY_TICKS;
        } else if (destinationHint == null && !descending) {
            lowestProspectingY = Integer.MAX_VALUE;
        }
        boolean ascending = destinationHint != null && allowNaturalAscent && position.getY() < height;
        if (ascending && position.getY() > highestReturnY && hasFooting(client)) {
            highestReturnY = position.getY();
            nextReturnRecovery = age + RETURN_RECOVERY_TICKS;
        }
        // Lateral motion through a cave does not make a return trip any closer to its surface elevation.
        boolean stalledAscent = ascending && highestReturnY != Integer.MIN_VALUE && age >= nextReturnRecovery;
        if (active()) {
            var feet = client.player.getPos();
            if (feet.squaredDistanceTo(lastMovement) >= .36) {
                lastMovement = feet;
                movedAt = age;
            }
            boolean natural = followingNaturalAscent();
            boolean arrived = natural ? position.getY() >= ascentGoal : goals.contains(position);
            if (!arrived && !stalledAscent && age - started < (natural ? 1000 : 600) && age - movedAt < 240
                    && (age - started < 60 || BaritoneClient.pursuing(goals.get(0)))) return true;
            if (!arrived && !natural) {
                heading++;
                if (position.equals(stalledAt)) routeFailures++;
                else {
                    stalledAt = position;
                    routeFailures = 1;
                }
            } else if (arrived) {
                stalledAt = null;
                routeFailures = 0;
            }
            if (arrived && destinationHint != null) heading = 0;
            if (!arrived) localDescent = localAscent = false;
            stopRoute();
            if (natural) {
                if (!ascending || arrived && allowPassage) return false;
                localAscent = !arrived && allowPassage;
            }
        }
        if (age < retryAt) return true;
        if (!hasFooting(client)) return false;
        if (allowNaturalAscent && !stalledAscent
                && tryNaturalAscent(client, age, height, allowPassage ? ASCENT_CHECKPOINT : 1)) return true;
        if (!allowPassage) return false;
        if (area != null && (area.contains(position) || area.contains(position.down()))) return false;

        int segmentY = position.getY() + Math.max(-4, Math.min(4, height - position.getY()));
        if (destinationHint != null) {
            int preferred = headingToward(position, destinationHint);
            if (ascending && (localAscent || stalledAscent)) {
                if (stalledAscent) nextReturnRecovery = age + RETURN_RECOVERY_TICKS;
                if (openPassage(client, age, position, area, preferred, 1)) {
                    localAscent = true;
                    if (stalledAscent) BotEvents.add("debug", "movement", "Changing to local stairs after no return elevation gain");
                    return true;
                }
                localAscent = false;
            }
            var exits = returnExits(client, position, destinationHint, area, segmentY);
            if (ascending && !stalledAscent && (exits.isEmpty() || routeFailures >= 2 && position.equals(stalledAt))
                    && openPassage(client, age, position, area, preferred, 1)) {
                localAscent = true;
                return true;
            }
            if (!exits.isEmpty() && start(client, position, exits, area, age, true)) return true;
            return false;
        }
        int initial = Math.floorMod(client.player.getUuid().hashCode(), HEADINGS.length);
        int preferredHeading = initial + heading / 2;
        if (descending && localDescent
                && openPassage(client, age, position, area, preferredHeading, -1)) return true;
        localDescent = false;
        // A cave can bring a path back at a different X/Z without reaching a lower layer.
        boolean stalledDescent = descending && lowestProspectingY != Integer.MAX_VALUE && age >= nextDescentRecovery;
        if (stalledDescent || routeFailures >= 2 && position.equals(stalledAt)) {
            boolean openedDescent = descending && openPassage(client, age, position, area, preferredHeading, -1);
            if (openedDescent) {
                localDescent = true;
                BotEvents.add("debug", "mining", "Continuing local descent toward Y " + height);
            }
            if (openedDescent || openPassage(client, age, position, area, preferredHeading, 0)) {
                if (stalledDescent) {
                    nextDescentRecovery = age + DESCENT_RECOVERY_TICKS;
                    BotEvents.add("debug", "mining", "Recovering descent after no progress below Y " + lowestProspectingY);
                }
                return true;
            }
        }
        // Shorter, shallower alternatives can get around a wall without returning up the cave.
        int delta = height - position.getY();
        int fallbackY = position.getY() + (delta == 0 ? 1 : Math.abs(delta) <= 2 ? 0 : Integer.signum(delta) * 2);
        if (delta < -2) {
            // Offer one depth at a time so an easy shallow exit cannot outrank a reachable descent.
            int preferredY = routeFailures % 2 == 0 ? segmentY : fallbackY;
            int secondaryY = preferredY == segmentY ? fallbackY : segmentY;
            var exits = descentExits(client, position, area, initial + heading / 2, preferredY);
            if (exits.isEmpty() && secondaryY != preferredY) {
                exits = descentExits(client, position, area, initial + heading / 2, secondaryY);
            }
            if (!exits.isEmpty() && start(client, position, exits, area, age, false)) {
                BotEvents.add("debug", "mining", "Planning a descent to Y " + exits.get(0).getY()
                        + " with " + exits.size() + " candidate stands");
                return true;
            }
            return false;
        }
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

    boolean tryNaturalAscent(MinecraftClient client, int age, int destinationY) {
        return tryNaturalAscent(client, age, destinationY, ASCENT_CHECKPOINT);
    }

    boolean tryNaturalAscent(MinecraftClient client, int age, int destinationY, int minimumGain) {
        var position = client.player.getBlockPos();
        int y = position.getY();
        if (active() || age < retryAt || !hasFooting(client) || destinationY - y < minimumGain
                || y < lastAscentProbe + ASCENT_CHECKPOINT - 2) return false;
        lastAscentProbe = y;
        int height = Math.min(destinationY, y + ASCENT_CHECKPOINT);
        try {
            settings = new BaritoneSettings(false, true);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
        origin = position;
        goals = List.of(position.withY(height));
        started = movedAt = age;
        lastMovement = client.player.getPos();
        ascentGoal = height;
        if (!BaritoneClient.goToAltitude(height)) {
            stop();
            return false;
        }
        localAscent = false;
        BotEvents.add("info", "movement", "Following a natural ascent toward Y " + height);
        return true;
    }

    private boolean openTowardMiningStand(MinecraftClient client, int age, BlockPos from, BlockPos stand,
                                          ProjectGather.Area area) {
        var candidates = new ArrayList<Passage>();
        double distance = stand.getSquaredDistance(from);
        int rise = Integer.signum(stand.getY() - from.getY());
        for (var heading : HEADINGS) {
            var step = from.offset(heading);
            if (step.getSquaredDistance(stand) < distance) candidates.add(new Passage(from, step));
            if (rise != 0 && step.up(rise).getSquaredDistance(stand) < distance) {
                candidates.add(new Passage(from, step.up(rise)));
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(step -> step.to().getSquaredDistance(stand)));
        for (var step : candidates) {
            if (!beginPassage(client, age, step, area)) continue;
            BotEvents.add("debug", "mining", "Protected step toward mining stand " + stand.toShortString()
                    + " via " + step.to().toShortString());
            return true;
        }
        return false;
    }

    private boolean openPassage(MinecraftClient client, int age, BlockPos from, ProjectGather.Area area,
                                int heading, int... heights) {
        for (int delta : heights) {
            for (int turn = 0; turn < HEADINGS.length; turn++) {
                var cell = from.offset(HEADINGS[Math.floorMod(heading + turn, HEADINGS.length)]).up(delta);
                if (beginPassage(client, age, new Passage(from, cell), area)) return true;
            }
        }
        return false;
    }

    private boolean beginPassage(MinecraftClient client, int age, Passage candidate, ProjectGather.Area area) {
        if (client.currentScreen != null || attemptedPassages.contains(candidate)
                || attemptedPassages.contains(new Passage(candidate.to(), candidate.from()))
                || !safePassage(client, candidate, area)) return false;
        boolean clear = passageOpen(client, candidate);
        if (!clear) {
            if (candidate.climbing() && diggingToolSlot(client, true) < 0
                    && candidate.blocksToClear().stream().anyMatch(pos -> {
                        var state = client.world.getBlockState(pos);
                        return !passageClear(client, pos, state) && !handClearance(state);
                    })) return false;
            var hit = passageHit(client, candidate, area);
            if (hit == null || !preparePassageHit(client, hit, candidate.climbing())) return false;
        }
        if (attemptedPassages.size() >= 24) attemptedPassages.remove(attemptedPassages.iterator().next());
        attemptedPassages.add(candidate);
        if (clear) return start(client, candidate.from(), List.of(candidate.to()), area, age, candidate.climbing());
        passage = candidate;
        passageDeadline = age + 220;
        String detail = candidate.climbing() ? "Opening a protected return step at "
                : candidate.to().getY() == candidate.from().getY() ? "Opening a short prospecting passage at "
                : "Opening a safe prospecting step at ";
        BotEvents.add("info", "mining", detail + candidate.to().toShortString());
        return true;
    }

    private static boolean passageOpen(MinecraftClient client, Passage passage) {
        return passage.blocksToClear().stream().allMatch(pos -> passageClear(client, pos, client.world.getBlockState(pos)));
    }

    private static BlockHitResult passageHit(MinecraftClient client, Passage passage,
                                                                     ProjectGather.Area area) {
        for (var pos : passage.blocksToClear()) {
            if (passageClear(client, pos, client.world.getBlockState(pos))) continue;
            var hit = ProjectWorker.excavationHit(client, pos);
            if (hit != null || !passage.climbing()) return hit;
            // Collisionless wall growth can still intercept the outline ray used to mine a block.
            hit = ProjectWorker.excavationHit(client, pos, cover -> attachedCover(client, cover, pos, area));
            if (hit != null) return hit;
        }
        return null;
    }

    private static boolean attachedCover(MinecraftClient client, BlockPos cover, BlockPos target,
                                         ProjectGather.Area area) {
        var state = client.world.getBlockState(cover);
        if (!(state.getBlock() instanceof MultifaceGrowthBlock) || !passageClear(client, cover, state)
                || !safeClearance(client, cover, area, true)) return false;
        for (var side : Direction.values()) {
            if (cover.offset(side).equals(target) && state.get(MultifaceGrowthBlock.getProperty(side))) return true;
        }
        return false;
    }

    boolean continuePassage(MinecraftClient client, int age, ProjectGather.Area area) {
        var opening = passage;
        if (opening == null) return false;
        var position = client.player.getBlockPos();
        if (age >= passageDeadline || !hasFooting(client) || client.currentScreen != null
                || !position.equals(opening.from()) || !safePassage(client, opening, area)) {
            stopPassage();
            localDescent = localAscent = false;
            return false;
        }
        if (passageOpen(client, opening)) {
            stopPassage();
            if (start(client, position, List.of(opening.to()), area, age, opening.climbing())) return true;
            localDescent = localAscent = false;
            return false;
        }
        var ray = passageHit(client, opening, area);
        if (ray == null || !preparePassageHit(client, ray, opening.climbing())) {
            stopPassage();
            localDescent = localAscent = false;
            return false;
        }
        client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, ray.getPos());
        if (client.interactionManager.updateBlockBreakingProgress(ray.getBlockPos(), ray.getSide())) {
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
        return passage.blocksToClear().stream().allMatch(block -> safeClearance(client, block, area, passage.climbing()));
    }

    private static boolean safeClearance(MinecraftClient client, BlockPos pos, ProjectGather.Area area, boolean returning) {
        var world = client.world;
        if (!world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)
                || world.getBlockState(pos.up()).getBlock() instanceof FallingBlock
                || BuildProtection.blocked(pos) || area != null && area.contains(pos)
                || world.getPlayers().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                && player.getBlockPos().down().equals(pos))) return false;
        var state = world.getBlockState(pos);
        if (state.isOf(Blocks.DRIPSTONE_BLOCK) && world.getBlockState(pos.down()).isOf(Blocks.POINTED_DRIPSTONE)) {
            return false;
        }
        if (!state.getFluidState().isEmpty()
                || !passageClear(client, pos, state)
                    && (state.hasBlockEntity() || !BaritoneSettings.prospectingTerrain(state.getBlock())
                        && !(returning && handClearance(state)))) return false;
        for (var side : Direction.values()) {
            var neighbor = pos.offset(side);
            if (!world.isChunkLoaded(neighbor.getX() >> 4, neighbor.getZ() >> 4)
                    || !world.getFluidState(neighbor).isEmpty()) return false;
        }
        return true;
    }

    static boolean canClearOreCover(MinecraftClient client, BlockPos pos, ProjectGather.Area area) {
        return pos.getY() >= client.player.getBlockY()
                && BaritoneSettings.prospectingTerrain(client.world.getBlockState(pos).getBlock())
                && safeClearance(client, pos, area, false);
    }

    private boolean preparePassageHit(MinecraftClient client, BlockHitResult hit, boolean returning) {
        return returning && handClearance(client.world.getBlockState(hit.getBlockPos()))
                || equipDiggingTool(client, returning);
    }

    private static boolean handClearance(BlockState state) {
        return !state.isToolRequired() && (state.isOf(Blocks.SCULK)
                || state.getBlock() instanceof MultifaceGrowthBlock
                || BaritoneSettings.handDiggableTerrain(state.getBlock()));
    }

    private static boolean passageClear(MinecraftClient client, BlockPos pos, BlockState state) {
        return state.isAir() || state.getBlock() instanceof MultifaceGrowthBlock
                && state.getCollisionShape(client.world, pos).isEmpty();
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

    private static List<BlockPos> descentExits(MinecraftClient client, BlockPos position, ProjectGather.Area area,
                                               int heading, int exitY) {
        var exits = new java.util.LinkedHashSet<BlockPos>();
        for (int turn = 0; turn < HEADINGS.length; turn++) {
            var direction = HEADINGS[Math.floorMod(heading + turn, HEADINGS.length)];
            exits.addAll(corridorExits(client, position, direction, area, exitY));
        }
        return List.copyOf(exits);
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
        if (followingNaturalAscent() || client.player == null) return true;
        var held = client.player.getMainHandStack();
        if (held.getItem() instanceof PickaxeItem pickaxe && pickaxe.getMaterial().getMiningLevel() >= 2
                && held.getMaxDamage() - held.getDamage() <= HARVEST_RESERVE) return true;
        if (client.world == null || !pos.isWithinDistance(origin, escapingExploration ? 6 : REACH)
                || excluded != null && excluded.contains(pos)) return true;
        var terrain = client.world.getBlockState(pos);
        if (terrain.isOf(Blocks.DRIPSTONE_BLOCK)) {
            if (escapingExploration || !safeClearance(client, pos, excluded, false)) return true;
        } else if (!BaritoneSettings.recoverableTerrain(terrain.getBlock())) return true;
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
            int reserve = level >= 2 ? HARVEST_RESERVE : 0;
            if (stack.getMaxDamage() - stack.getDamage() <= reserve) continue;
            int priority = returning ? level : level == 1 ? -1 : level;
            if (priority < bestPriority) {
                best = index;
                bestPriority = priority;
            }
        }
        return best;
    }

    static boolean equipDiggingTool(MinecraftClient client, boolean returning) {
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
        localDescent = localAscent = false;
        stopRoute();
    }

    void pauseReturnForToolPreparation() {
        boolean continuing = localAscent;
        if (continuing && origin != null && goals.size() == 1) {
            // The next step may already be clear. Let the worker reuse it after changing tools.
            attemptedPassages.remove(new Passage(origin, goals.get(0)));
        }
        stop();
        localAscent = continuing;
    }

    private void stopRoute() {
        if (settings == null) return;
        BaritoneClient.execute("stop");
        settings.close();
        settings = null;
        ascentGoal = Integer.MIN_VALUE;
        escapingExploration = handDigging = false;
        origin = null;
        lastMovement = null;
        goals = List.of();
        excluded = null;
    }

    void reset() {
        stop();
        lastAscentProbe = Integer.MIN_VALUE;
        retryAt = heading = 0;
        stalledAt = null;
        routeFailures = 0;
        lowestProspectingY = Integer.MAX_VALUE;
        highestReturnY = Integer.MIN_VALUE;
        nextDescentRecovery = nextReturnRecovery = 0;
        attemptedPassages.clear();
    }
}
