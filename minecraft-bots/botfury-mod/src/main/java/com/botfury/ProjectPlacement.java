package com.botfury;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;

/** Places a simple block through the same interaction as a player using a block item. */
final class ProjectPlacement {
    private ProjectPlacement() {}

    static boolean supported(BlockState state) {
        return !state.isAir() && !state.hasBlockEntity()
                && (state.getBlock().getStateManager().getStates().size() == 1
                || state.getBlock() instanceof PillarBlock && state.getProperties().size() == 1 && state.contains(PillarBlock.AXIS))
                && state.getBlock().asItem() instanceof BlockItem item && item.getBlock() == state.getBlock();
    }

    static List<Direction> faces(BlockState state) {
        if (!state.contains(PillarBlock.AXIS)) {
            if (state.getBlock() instanceof FallingBlock) return List.of(Direction.UP);
            return List.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);
        }
        return switch (state.get(PillarBlock.AXIS)) {
            case X -> List.of(Direction.EAST, Direction.WEST);
            case Z -> List.of(Direction.NORTH, Direction.SOUTH);
            case Y -> List.of(Direction.UP);
        };
    }

    static Vec3d facePoint(BlockPos support, Direction face) {
        return Vec3d.ofCenter(support).add(Vec3d.of(face.getVector()).multiply(.499));
    }

    /** Null means a placement packet was accepted for processing, or an inventory swap is pending. */
    static String attempt(MinecraftClient client, ProjectBlock block) {
        var player = client.player;
        var target = block.target();
        var desired = block.desired();
        var item = desired.getBlock().asItem();
        if (!supported(desired) || !faces(desired).contains(block.face()) || !desired.isFullCube(client.world, target)) {
            return "This block needs placement behavior the project worker does not support";
        }
        if (!client.world.getBlockState(target).isAir()) return "Build target is occupied";
        if (player.currentScreenHandler != player.playerScreenHandler
                || !player.currentScreenHandler.getCursorStack().isEmpty()) return "Close the open container or clear the cursor";
        var support = block.support();
        Vec3d eye = player.getEyePos();
        Vec3d point = facePoint(support, block.face());
        var hit = client.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        double reach = client.interactionManager.getReachDistance() - .1;
        var supportState = client.world.getBlockState(support);
        if (supportState.hasBlockEntity() || !supportState.isFullCube(client.world, support)
                || eye.squaredDistanceTo(point) > reach * reach || hit.getType() != HitResult.Type.BLOCK
                || !support.equals(hit.getBlockPos()) || hit.getSide() != block.face()) {
            return "No reachable solid support face for " + target.toShortString();
        }

        int slot = 0;
        while (slot < 36 && !player.getInventory().getStack(slot).isOf(item)) slot++;
        if (slot == 36) return "Missing material " + Registries.ITEM.getId(item);
        if (slot >= 9) {
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot,
                    player.getInventory().selectedSlot, SlotActionType.SWAP, player);
            return null;
        }
        player.getInventory().selectedSlot = slot;
        player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, hit.getPos());
        var result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        if (!result.isAccepted()) return "Placement was rejected at " + target.toShortString();
        player.swingHand(Hand.MAIN_HAND);
        return null;
    }
}
