package com.botfury;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeMatcher;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Establishes a local production station outside protected project areas. */
final class FieldWorkstation {
    enum State { WORKING, READY, UNAVAILABLE }

    private static final Identifier TABLE = Identifier.of("minecraft", "crafting_table");
    private static final Identifier FURNACE = Identifier.of("minecraft", "furnace");
    private static final int WORKBENCH_COST = 4;
    private static final int PLANK_BUDGET = 8;
    private final ProjectBlock station;
    private final long expires;
    private final FieldWorkstation workbench;
    private final BlockState recess;
    private ProjectProduction preparation;
    private ProjectProduction plankPreparation;
    private long nextPlacement;
    private boolean openingRecess;
    private List<BlockPos> approach = List.of();
    private BaritoneSettings walkSettings;
    private int heldBeforePlacement;

    private FieldWorkstation(ProjectBlock station, long now, ProjectProduction.Spec plankRecipe,
                             FieldWorkstation workbench, BlockState recess) {
        this.station = station;
        this.workbench = workbench;
        this.recess = recess;
        long preparationTime = workbench == null ? 20_000_000_000L : workbench.expires - now + 15_000_000_000L;
        expires = now + preparationTime + (recess == null ? 0 : 20_000_000_000L);
        if (plankRecipe != null) plankPreparation = new ProjectProduction(plankRecipe);
    }

    ProjectBlock station() { return station; }

    boolean placedByWorker(MinecraftClient client) {
        return heldBeforePlacement > stationItemCount(client, station.block())
                && station.matches(client.world.getBlockState(station.target()));
    }

    ProjectBlock placedWorkbench(MinecraftClient client) {
        if (workbench != null) return workbench.placedWorkbench(client);
        return station.block().equals(TABLE) && placedByWorker(client) ? station : null;
    }

    static FieldWorkstation find(MinecraftClient client, long now, ProjectQueue.Kind kind, Set<BlockPos> rejected) {
        var local = find(client, now, kind == ProjectQueue.Kind.SMELT ? FURNACE : TABLE, null, PLANK_BUDGET, null);
        if (local != null || kind != ProjectQueue.Kind.CRAFT || !SubsurfaceAccess.hasFooting(client)
                || client.player.isTouchingWater() || client.player.currentScreenHandler != client.player.playerScreenHandler) return local;
        return findWalkableWorkbench(client, now, rejected);
    }

    private static FieldWorkstation findWalkableWorkbench(MinecraftClient client, long now, Set<BlockPos> rejected) {
        var world = client.world;
        var player = client.player;
        var feet = player.getBlockPos();
        for (var position : BlockPos.iterateOutwards(feet, 12, 4, 12)) {
            if (rejected.contains(position) || !world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)
                    || !world.getBlockState(position).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) continue;
            var stands = new ArrayList<BlockPos>();
            for (var side : Direction.Type.HORIZONTAL) {
                for (var stand : List.of(position.offset(side), position.offset(side).down(), position.offset(side).up())) {
                    var floor = stand.down();
                    var point = Vec3d.ofBottomCenter(stand);
                    var body = player.getBoundingBox().offset(point.subtract(player.getPos()));
                    var eye = point.add(0, player.getEyeY() - player.getY(), 0);
                    if (!world.isChunkLoaded(stand.getX() >> 4, stand.getZ() >> 4)
                            || !world.getWorldBorder().contains(stand) || !world.getFluidState(stand).isEmpty()
                            || !world.getFluidState(stand.up()).isEmpty()
                            || !ProjectPlacement.flushFloor(world.getBlockState(floor), world, floor)
                            || !world.isSpaceEmpty(player, body)
                            || ProjectProduction.workstationHit(client, position, eye) == null) continue;
                    stands.add(stand.toImmutable());
                }
            }
            if (stands.isEmpty()) continue;
            var bench = new FieldWorkstation(new ProjectBlock(position.toImmutable(), stands.get(0), TABLE), now, null, null, null);
            bench.approach = List.copyOf(stands);
            return bench;
        }
        return null;
    }

    static FieldWorkstation findForMiningTool(MinecraftClient client, long now, ProjectGather.Area excluded) {
        return find(client, now, TABLE, null, WORKBENCH_COST, excluded);
    }

    private static FieldWorkstation find(MinecraftClient client, long now, Identifier wanted, BlockPos reserved,
                                         int plankBudget, ProjectGather.Area excluded) {
        if (!SubsurfaceAccess.hasFooting(client) || client.player.isTouchingWater()
                || client.player.currentScreenHandler != client.player.playerScreenHandler) return null;
        var feet = client.player.getBlockPos();
        ProjectBlock site = null;
        ProjectBlock recessSite = null;
        boolean held = hasStationItem(client, wanted);
        boolean table = wanted.equals(TABLE);
        int planks = table && !held ? plankCount(client) : 0;
        var plankRecipe = table && !held && planks < plankBudget ? plankRecipe(client, plankBudget - planks) : null;
        boolean canPrepare = held || (table ? planks >= plankBudget || plankRecipe != null
                : ProjectProduction.canCraft(client, FURNACE));
        for (var position : BlockPos.iterateOutwards(feet, 3, 2, 3)) {
            if (position.equals(feet) || position.equals(feet.up()) || position.equals(feet.down())
                    || position.equals(reserved)
                    || !client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) continue;
            if (client.world.getBlockState(position).isOf(Registries.BLOCK.get(wanted))
                    && ProjectProduction.workstationHit(client, position, client.player.getEyePos()) != null) {
                return new FieldWorkstation(new ProjectBlock(position.toImmutable(), feet, wanted), now, null, null, null);
            }
            if (!canPrepare || site != null || excluded != null
                    && (excluded.contains(position) || excluded.contains(position.down()))) continue;
            if (safeSite(client, position)) {
                var block = new ProjectBlock(position.toImmutable(), feet, wanted);
                if (ProjectPlacement.supportHit(client, block) != null) site = block;
            } else if (recessSite == null && position.getY() == feet.getY() && safeRecess(client, position)
                    && ProjectWorker.excavationHit(client, position) != null) {
                recessSite = new ProjectBlock(position.toImmutable(), feet, wanted);
            }
        }
        BlockState recess = null;
        if (site == null && recessSite != null) {
            site = recessSite;
            recess = client.world.getBlockState(site.target());
        }
        if (site == null) return null;
        var workbench = table || held ? null : find(client, now, TABLE, site.target(), PLANK_BUDGET, excluded);
        if (!table && !held && workbench == null) return null;
        return new FieldWorkstation(site, now, plankRecipe, workbench, recess);
    }

    State tick(MinecraftClient client, long now) {
        var position = station.target();
        if (!approach.isEmpty()) {
            if (now >= expires || !station.matches(client.world.getBlockState(position))) return State.UNAVAILABLE;
            if (ProjectProduction.workstationHit(client, position, client.player.getEyePos()) != null) return State.READY;
            if (walkSettings != null) return State.WORKING;
            try {
                walkSettings = new BaritoneSettings(false);
            } catch (ReflectiveOperationException | LinkageError error) {
                return State.UNAVAILABLE;
            }
            return BaritoneClient.goToAny(approach) ? State.WORKING : State.UNAVAILABLE;
        }
        if (station.matches(client.world.getBlockState(position))) {
            return ProjectProduction.workstationHit(client, position, client.player.getEyePos()) == null
                    ? State.UNAVAILABLE : State.READY;
        }
        if (now >= expires || !SubsurfaceAccess.hasFooting(client)) return State.UNAVAILABLE;
        if (recess != null && client.world.getBlockState(position).equals(recess)) return openRecess(client, position);
        stopOpening(client);
        if (!safeSite(client, position)) return State.UNAVAILABLE;
        if (!hasStationItem(client, station.block())) {
            if (plankPreparation != null) {
                var result = plankPreparation.tick(client, now);
                if (result == null) return State.WORKING;
                if (!result.success()) return State.UNAVAILABLE;
                plankPreparation.close(client);
                plankPreparation = null;
                return State.WORKING;
            }
            if (preparation == null) {
                if (station.block().equals(FURNACE)) {
                    if (workbench == null) return State.UNAVAILABLE;
                    var state = workbench.tick(client, now);
                    if (state != State.READY) return state;
                    workbench.close(client);
                }
                var craftAt = workbench == null ? null : workbench.station();
                preparation = new ProjectProduction(new ProjectProduction.Spec(craftAt, ProjectQueue.Kind.CRAFT,
                        station.block(), null, null, 1));
            }
            var result = preparation.tick(client, now);
            if (result != null && !result.success()) return State.UNAVAILABLE;
            return State.WORKING;
        }
        if (preparation != null) {
            preparation.close(client);
            preparation = null;
            return State.WORKING;
        }
        if (now < nextPlacement) return State.WORKING;
        nextPlacement = now + 750_000_000L;
        heldBeforePlacement = stationItemCount(client, station.block());
        return ProjectPlacement.attempt(client, station) == null ? State.WORKING : State.UNAVAILABLE;
    }

    boolean walkingToExistingWorkbench() { return !approach.isEmpty(); }

    void close(MinecraftClient client) {
        if (walkSettings != null) {
            BaritoneClient.execute("stop");
            walkSettings.close();
            walkSettings = null;
        }
        stopOpening(client);
        if (workbench != null) workbench.close(client);
        if (plankPreparation != null) plankPreparation.close(client);
        if (preparation != null) preparation.close(client);
    }

    private State openRecess(MinecraftClient client, BlockPos position) {
        if (!safeRecess(client, position) || client.player.currentScreenHandler != client.player.playerScreenHandler) {
            return State.UNAVAILABLE;
        }
        var ray = ProjectWorker.excavationHit(client, position);
        if (ray == null) return State.UNAVAILABLE;
        if (!openingRecess) BotEvents.add("info", "projects", "Opening a workstation recess at " + position.toShortString());
        openingRecess = true;
        client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, ray.getPos());
        if (client.interactionManager.updateBlockBreakingProgress(position, ray.getSide())) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
        return State.WORKING;
    }

    private void stopOpening(MinecraftClient client) {
        if (!openingRecess) return;
        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        openingRecess = false;
    }

    static ProjectProduction.Spec plankRecipe(MinecraftClient client, int needed) {
        var inventory = client.player.getInventory();
        var matcher = new RecipeMatcher();
        inventory.populateRecipeFinder(matcher);
        for (var recipe : client.world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            var output = recipe.getOutput(client.world.getRegistryManager());
            if (!output.isIn(ItemTags.PLANKS) || !recipe.fits(2, 2) || !matcher.match(recipe, null)) continue;
            int held = 0;
            for (int slot = 0; slot < 36; slot++) {
                var stack = inventory.getStack(slot);
                if (ItemStack.canCombine(stack, output)) held += stack.getCount();
            }
            return new ProjectProduction.Spec(null, ProjectQueue.Kind.CRAFT, recipe.getId(), null, null, held + needed);
        }
        return null;
    }

    private static boolean safeSite(MinecraftClient client, BlockPos position) {
        var world = client.world;
        var floor = position.down();
        if (!world.isInBuildLimit(position) || !world.getWorldBorder().contains(position)
                || !world.isAir(position) || !world.isSpaceEmpty(client.player, new Box(position))
                || client.player.getBoundingBox().intersects(new Box(position))
                || BuildProtection.blocked(position) || BuildProtection.blocked(floor)
                || !ProjectPlacement.flushFloor(world.getBlockState(floor), world, floor)
                || !world.getOtherEntities(client.player, new Box(position), entity -> entity.isAlive()).isEmpty()) return false;
        return horizontalExits(client, position) != 2;
    }

    private static boolean safeRecess(MinecraftClient client, BlockPos position) {
        var world = client.world;
        var state = world.getBlockState(position);
        var floor = position.down();
        float hardness = state.getHardness(world, position);
        if (!world.isInBuildLimit(position.up()) || !world.getWorldBorder().contains(position)
                || !BaritoneSettings.recoverableTerrain(state.getBlock()) || state.hasBlockEntity()
                || !state.getFluidState().isEmpty()
                || hardness < 0 || hardness > 3 || BuildProtection.blocked(position) || BuildProtection.blocked(floor)
                || !ProjectPlacement.flushFloor(world.getBlockState(floor), world, floor)
                || world.getBlockState(position.up()).getBlock() instanceof FallingBlock
                || horizontalExits(client, position) != 1
                || !world.getOtherEntities(client.player, new Box(position).stretch(0, 1, 0),
                    entity -> entity.isAlive()).isEmpty()) return false;
        for (var side : Direction.values()) {
            var neighbor = position.offset(side);
            if (!world.isChunkLoaded(neighbor.getX() >> 4, neighbor.getZ() >> 4)
                    || !world.getFluidState(neighbor).isEmpty()) return false;
        }
        return true;
    }

    private static int horizontalExits(MinecraftClient client, BlockPos position) {
        int exits = 0;
        for (var side : Direction.Type.HORIZONTAL) {
            var neighbor = position.offset(side);
            if (client.world.isAir(neighbor) && client.world.isAir(neighbor.up())) exits++;
        }
        return exits;
    }

    private static boolean hasStationItem(MinecraftClient client, Identifier station) {
        return stationItemCount(client, station) > 0;
    }

    private static int stationItemCount(MinecraftClient client, Identifier station) {
        int count = 0;
        var item = Registries.ITEM.get(station);
        for (int slot = 0; slot < 36; slot++) {
            var stack = client.player.getInventory().getStack(slot);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private static int plankCount(MinecraftClient client) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            var stack = client.player.getInventory().getStack(slot);
            if (stack.isIn(ItemTags.PLANKS)) count += stack.getCount();
        }
        return count;
    }
}
