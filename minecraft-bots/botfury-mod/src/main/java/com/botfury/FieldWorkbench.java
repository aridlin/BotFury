package com.botfury;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/** Establishes a local crafting table without modifying a schematic or blocking a corridor. */
final class FieldWorkbench {
    enum State { WORKING, READY, UNAVAILABLE }

    private static final Identifier TABLE = Identifier.of("minecraft", "crafting_table");
    private final ProjectBlock station;
    private final long expires;
    private ProjectProduction preparation;
    private long nextPlacement;

    private FieldWorkbench(ProjectBlock station, long now) {
        this.station = station;
        expires = now + 12_000_000_000L;
    }

    ProjectBlock station() { return station; }

    static FieldWorkbench find(MinecraftClient client, long now) {
        if (!SubsurfaceAccess.hasFooting(client) || client.player.isTouchingWater()
                || client.player.currentScreenHandler != client.player.playerScreenHandler) return null;
        var feet = client.player.getBlockPos();
        ProjectBlock site = null;
        boolean canPrepare = hasTable(client) || plankCount(client) >= 8;
        for (var position : BlockPos.iterateOutwards(feet, 3, 2, 3)) {
            if (position.equals(feet) || position.equals(feet.up()) || position.equals(feet.down())
                    || !client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) continue;
            if (client.world.getBlockState(position).isOf(Blocks.CRAFTING_TABLE)
                    && ProjectProduction.workstationHit(client, position, client.player.getEyePos()) != null) {
                return new FieldWorkbench(new ProjectBlock(position.toImmutable(), feet, TABLE), now);
            }
            if (!canPrepare || site != null || !safeSite(client, position)) continue;
            var block = new ProjectBlock(position.toImmutable(), feet, TABLE);
            if (ProjectPlacement.supportHit(client, block) != null) site = block;
        }
        return site == null ? null : new FieldWorkbench(site, now);
    }

    State tick(MinecraftClient client, long now) {
        var position = station.target();
        if (client.world.getBlockState(position).isOf(Blocks.CRAFTING_TABLE)) {
            return ProjectProduction.workstationHit(client, position, client.player.getEyePos()) == null
                    ? State.UNAVAILABLE : State.READY;
        }
        if (now >= expires || !safeSite(client, position) || !SubsurfaceAccess.hasFooting(client)) return State.UNAVAILABLE;
        if (!hasTable(client)) {
            if (preparation == null) {
                preparation = new ProjectProduction(new ProjectProduction.Spec(null, ProjectQueue.Kind.CRAFT, TABLE, null, null, 1));
            }
            var result = preparation.tick(client, now);
            if (result != null && !result.success()) return State.UNAVAILABLE;
            return State.WORKING;
        }
        if (now < nextPlacement) return State.WORKING;
        nextPlacement = now + 750_000_000L;
        return ProjectPlacement.attempt(client, station) == null ? State.WORKING : State.UNAVAILABLE;
    }

    void close(MinecraftClient client) {
        if (preparation != null) preparation.close(client);
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
        int exits = 0;
        for (var side : Direction.Type.HORIZONTAL) {
            var neighbor = position.offset(side);
            if (world.isAir(neighbor) && world.isAir(neighbor.up())) exits++;
        }
        return exits != 2;
    }

    private static boolean hasTable(MinecraftClient client) {
        for (int slot = 0; slot < 36; slot++) {
            if (client.player.getInventory().getStack(slot).isOf(Items.CRAFTING_TABLE)) return true;
        }
        return false;
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
