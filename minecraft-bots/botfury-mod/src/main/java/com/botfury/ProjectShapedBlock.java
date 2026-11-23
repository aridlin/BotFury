package com.botfury;

import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.Direction;

import java.util.List;

/** Placement rules for shaped blocks whose state depends on the face or player direction. */
final class ProjectShapedBlock {
    private ProjectShapedBlock() {}

    static boolean recognized(BlockState state) {
        return state.getBlock() instanceof SlabBlock || state.getBlock() instanceof LadderBlock
                || state.getBlock() instanceof TrapdoorBlock || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof AbstractFurnaceBlock || state.isOf(Blocks.TORCH);
    }

    static boolean supported(BlockState state) {
        if (state.getBlock() instanceof SlabBlock) {
            return !state.get(SlabBlock.WATERLOGGED) && state.get(SlabBlock.TYPE) != SlabType.DOUBLE;
        }
        if (state.getBlock() instanceof LadderBlock) return !state.get(LadderBlock.WATERLOGGED);
        if (state.getBlock() instanceof TrapdoorBlock) {
            return !state.isOf(Blocks.IRON_TRAPDOOR) && !state.get(TrapdoorBlock.WATERLOGGED)
                    && !state.get(TrapdoorBlock.POWERED);
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            return !state.get(FenceGateBlock.POWERED) && !state.get(FenceGateBlock.IN_WALL);
        }
        if (state.getBlock() instanceof AbstractFurnaceBlock) return !state.get(AbstractFurnaceBlock.LIT);
        return state.isOf(Blocks.TORCH);
    }

    static List<Direction> faces(BlockState state) {
        if (state.getBlock() instanceof LadderBlock) return List.of(state.get(LadderBlock.FACING));
        if (state.getBlock() instanceof TrapdoorBlock) return List.of(state.get(TrapdoorBlock.FACING),
                state.get(TrapdoorBlock.HALF) == BlockHalf.TOP ? Direction.DOWN : Direction.UP);
        if (state.getBlock() instanceof SlabBlock) {
            return List.of(state.get(SlabBlock.TYPE) == SlabType.TOP ? Direction.DOWN : Direction.UP,
                    Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);
        }
        return List.of(Direction.UP);
    }

    static Direction playerFacing(BlockState state) {
        if (state.getBlock() instanceof LadderBlock) return state.get(LadderBlock.FACING).getOpposite();
        if (state.getBlock() instanceof TrapdoorBlock) return state.get(TrapdoorBlock.FACING).getOpposite();
        if (state.getBlock() instanceof FenceGateBlock) return state.get(FenceGateBlock.FACING);
        if (state.getBlock() instanceof AbstractFurnaceBlock) return state.get(AbstractFurnaceBlock.FACING).getOpposite();
        return null;
    }

    static double clickHeight(BlockState state) {
        if (state.getBlock() instanceof TrapdoorBlock) return state.get(TrapdoorBlock.HALF) == BlockHalf.TOP ? .75 : .25;
        if (state.getBlock() instanceof SlabBlock) return state.get(SlabBlock.TYPE) == SlabType.TOP ? .75 : .25;
        return .5;
    }

    static boolean canOpen(BlockState observed, BlockState desired) {
        if (desired.getBlock() instanceof TrapdoorBlock && supported(desired)) {
            return desired.get(TrapdoorBlock.OPEN) && observed.equals(desired.with(TrapdoorBlock.OPEN, false));
        }
        if (desired.getBlock() instanceof FenceGateBlock && supported(desired)) {
            return desired.get(FenceGateBlock.OPEN) && observed.equals(desired.with(FenceGateBlock.OPEN, false));
        }
        return false;
    }
}
