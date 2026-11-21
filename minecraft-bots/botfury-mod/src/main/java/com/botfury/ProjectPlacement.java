package com.botfury;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

import java.util.List;

/** Places a simple block through the same interaction as a player using a block item. */
final class ProjectPlacement {
    private ProjectPlacement() {}

    static boolean supported(BlockState state) {
        if (state.isOf(Blocks.CHEST)) return !state.get(ChestBlock.WATERLOGGED);
        if (state.isOf(Blocks.HOPPER)) return state.get(HopperBlock.ENABLED);
        return !state.isAir() && !state.hasBlockEntity()
                && (state.getBlock().getStateManager().getStates().size() == 1
                || state.getBlock() instanceof PillarBlock && state.getProperties().size() == 1 && state.contains(PillarBlock.AXIS))
                && state.getBlock().asItem() instanceof BlockItem item && item.getBlock() == state.getBlock();
    }

    static boolean container(BlockState state) {
        return state.isOf(Blocks.CHEST) || state.isOf(Blocks.HOPPER);
    }

    static BlockPos chestPartner(BlockPos pos, BlockState state) {
        return state.isOf(Blocks.CHEST) && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE
                ? pos.offset(ChestBlock.getFacing(state)) : null;
    }

    static BlockState singleChest(BlockState state) {
        return state.with(ChestBlock.CHEST_TYPE, ChestType.SINGLE);
    }

    static boolean support(BlockState state, BlockState desired, BlockView world, BlockPos pos) {
        if (desired.isOf(Blocks.CHEST) && desired.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            return state.equals(singleChest(desired));
        }
        return desired.isOf(Blocks.HOPPER) && container(state)
                || !state.hasBlockEntity() && state.isFullCube(world, pos);
    }

    static List<Direction> faces(BlockState state) {
        if (state.isOf(Blocks.CHEST)) return List.of(state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE
                ? Direction.UP : ChestBlock.getFacing(state).getOpposite());
        if (state.isOf(Blocks.HOPPER)) return List.of(state.get(HopperBlock.FACING).getOpposite());
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

    static Vec3d facePoint(BlockPos support, Direction face, BlockState state) {
        if (state.isOf(Blocks.CHEST)) {
            if (face == Direction.UP) return Vec3d.ofCenter(support).add(0, .374, 0);
            return Vec3d.ofCenter(support).add(Vec3d.of(face.getVector()).multiply(.436));
        }
        if (state.isOf(Blocks.HOPPER)) {
            if (face == Direction.UP) return Vec3d.ofCenter(support).add(.375, .499, 0);
            return Vec3d.ofCenter(support).add(Vec3d.of(face.getVector()).multiply(.499)).add(0, .3125, 0);
        }
        return Vec3d.ofCenter(support).add(Vec3d.of(face.getVector()).multiply(.499));
    }

    /** Null means a placement packet was accepted for processing, or an inventory swap is pending. */
    static String attempt(MinecraftClient client, ProjectBlock block) {
        var player = client.player;
        var target = block.target();
        var desired = block.desired();
        var item = desired.getBlock().asItem();
        if (!supported(desired) || !faces(desired).contains(block.face())
                || !container(desired) && !desired.isFullCube(client.world, target)) {
            return "This block needs placement behavior the project worker does not support";
        }
        if (!client.world.getBlockState(target).isAir()) return "Build target is occupied";
        if (player.currentScreenHandler != player.playerScreenHandler
                || !player.currentScreenHandler.getCursorStack().isEmpty()) return "Close the open container or clear the cursor";
        var support = block.support();
        Vec3d eye = player.getEyePos();
        var supportState = client.world.getBlockState(support);
        Vec3d point = facePoint(support, block.face(), supportState);
        var hit = client.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        double reach = client.interactionManager.getReachDistance() - .1;
        if (!support(supportState, desired, client.world, support)
                || eye.squaredDistanceTo(point) > reach * reach || hit.getType() != HitResult.Type.BLOCK
                || !support.equals(hit.getBlockPos()) || hit.getSide() != block.face()) {
            return "No reachable solid support face for " + target.toShortString();
        }

        boolean container = container(desired);
        int slot = 0;
        for (; slot < 36; slot++) {
            var stack = player.getInventory().getStack(slot);
            if (stack.isOf(item) && (!container || !stack.hasNbt())) break;
        }
        if (slot == 36) return "Missing " + (container ? "unmodified " : "") + "material " + Registries.ITEM.getId(item);
        if (slot >= 9) {
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot,
                    player.getInventory().selectedSlot, SlotActionType.SWAP, player);
            return null;
        }
        player.getInventory().selectedSlot = slot;
        player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, hit.getPos());
        boolean sneak = desired.isOf(Blocks.CHEST) || container(supportState);
        if (desired.isOf(Blocks.CHEST)) {
            player.setYaw(desired.get(ChestBlock.FACING).getOpposite().asRotation());
            player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                    player.getYaw(), player.getPitch(), player.isOnGround()));
        }
        boolean startSneak = sneak && !player.isSneaking();
        if (startSneak) {
            player.input.sneaking = true;
            player.networkHandler.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
        }
        ActionResult result;
        try {
            result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        } finally {
            if (startSneak) {
                player.input.sneaking = false;
                player.networkHandler.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
            }
        }
        if (!result.isAccepted()) return "Placement was rejected at " + target.toShortString();
        player.swingHand(Hand.MAIN_HAND);
        return null;
    }
}
