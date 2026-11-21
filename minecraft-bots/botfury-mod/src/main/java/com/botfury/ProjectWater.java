package com.botfury;

import com.google.gson.Gson;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;

/** Vanilla bucket interactions for schematic water and its renewable supply. */
final class ProjectWater {
    static final int MAX_BUCKETS = 16;
    private static final Gson JSON = new Gson();

    private ProjectWater() {}

    static boolean water(BlockState state) { return state.isOf(Blocks.WATER); }
    static boolean source(BlockState state) { return water(state) && state.get(FluidBlock.LEVEL) == 0; }

    record Fill(ProjectBlock location, int count) {
        private record Count(Integer count) {}

        static Fill parse(String payload) {
            var location = ProjectBlock.parse(payload);
            var quantity = JSON.fromJson(payload, Count.class);
            if (!source(location.desired()) || quantity.count == null || quantity.count < 1 || quantity.count > MAX_BUCKETS) {
                throw new IllegalArgumentException("Provide a renewable water source and 1–" + MAX_BUCKETS + " buckets");
            }
            return new Fill(location, quantity.count);
        }

        ProjectQueue.Work reserve(ProjectQueue.Work work) {
            var basic = location.reserve(work);
            var locks = new HashSet<>(basic.locks());
            for (var direction : Direction.Type.HORIZONTAL) locks.add(ProjectBlock.key(location.target().offset(direction)));
            locks.add(ProjectBlock.key(location.target().down()));
            return new ProjectQueue.Work(work.id(), work.kind(), work.payload(), work.needs(), locks, work.sameWorkerAs());
        }

        String attempt(MinecraftClient client) {
            var player = client.player;
            if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
                return "Close the open container or clear the cursor";
            }
            var pos = location.target();
            if (!renewable(client.world, pos)) return null; // Wait for the source to regenerate.
            var point = Vec3d.ofCenter(pos);
            var eye = player.getEyePos();
            var hit = client.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.SOURCE_ONLY, player));
            if (eye.squaredDistanceTo(point) > 4.4 * 4.4 || hit.getType() != HitResult.Type.BLOCK || !pos.equals(hit.getBlockPos())) {
                return "No reachable face for the renewable water source";
            }
            int slot = 0;
            while (slot < 36 && !player.getInventory().getStack(slot).isOf(Items.BUCKET)) slot++;
            if (slot == 36) return "Missing empty buckets";
            if (slot >= 9) {
                client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot,
                        player.getInventory().selectedSlot, SlotActionType.SWAP, player);
                return null;
            }
            player.getInventory().selectedSlot = slot;
            return useBucket(client, point, pos, null);
        }
    }

    static boolean renewable(BlockView world, BlockPos pos) {
        if (!source(world.getBlockState(pos))) return false;
        var below = world.getBlockState(pos.down());
        if (!source(below) && !below.isFullCube(world, pos.down())) return false;
        int adjacent = 0;
        for (var direction : Direction.Type.HORIZONTAL) {
            if (source(world.getBlockState(pos.offset(direction)))) adjacent++;
        }
        return adjacent >= 2;
    }

    static String useBucket(MinecraftClient client, Vec3d point, BlockPos expected, Direction face) {
        var player = client.player;
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        player.lookAt(EntityAnchor.EYES, point);
        var eye = player.getEyePos();
        var end = eye.add(player.getRotationVec(1).multiply(5));
        var hit = client.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                face == null ? RaycastContext.FluidHandling.SOURCE_ONLY : RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !expected.equals(hit.getBlockPos())
                || face != null && hit.getSide() != face) return "Bucket aim does not match the approved face";
        // Let the normal movement update complete before using a bucket, particularly after centering in sneak.
        if (Math.abs(MathHelper.wrapDegrees(player.getYaw() - yaw)) > .05 || Math.abs(player.getPitch() - pitch) > .05) {
            return null;
        }
        var result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        if (result.shouldSwingHand()) player.swingHand(Hand.MAIN_HAND);
        return result.isAccepted() ? null : "Bucket interaction was rejected at " + expected.toShortString();
    }
}
