package com.botfury.mixin;

import com.botfury.BuildProtection;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.Items;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
abstract class ProtectedBlockMixin {
    @Shadow public abstract void cancelBlockBreaking();

    @Inject(method = {"attackBlock", "updateBlockBreakingProgress"}, at = @At("HEAD"), cancellable = true)
    private void botfury$preventProtectedBreaking(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> result) {
        if (BuildProtection.blocksWorkerInteraction(pos)) {
            cancelBlockBreaking();
            result.setReturnValue(false);
        }
    }

    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void botfury$preventProtectedChanges(ClientPlayerEntity player, Hand hand, BlockHitResult hit,
                                                  CallbackInfoReturnable<ActionResult> result) {
        if (!Boolean.getBoolean("botfury.managedHttp")) return;
        var block = player.getWorld().getBlockState(hit.getBlockPos()).getBlock();
        if ((block instanceof DoorBlock || block instanceof TrapdoorBlock || block instanceof FenceGateBlock)
                && BuildProtection.blocksWorkerInteraction(hit.getBlockPos())) {
            result.setReturnValue(ActionResult.FAIL);
            return;
        }
        var stack = player.getStackInHand(hand);
        if (stack.getItem() instanceof BlockItem && BuildProtection.blocksWorkerInteraction(
                new ItemPlacementContext(player, hand, stack, hit).getBlockPos())) result.setReturnValue(ActionResult.FAIL);
    }

    @Inject(method = "interactItem", at = @At("HEAD"), cancellable = true)
    private void botfury$preventProtectedBucketUse(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> result) {
        if (!Boolean.getBoolean("botfury.managedHttp")) return;
        var item = player.getStackInHand(hand).getItem();
        if (!(item instanceof BucketItem)) return;
        var eye = player.getEyePos();
        var hit = player.getWorld().raycast(new RaycastContext(eye, eye.add(player.getRotationVec(1).multiply(5)),
                RaycastContext.ShapeType.OUTLINE, item == Items.BUCKET
                        ? RaycastContext.FluidHandling.SOURCE_ONLY : RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && (BuildProtection.blocksWorkerInteraction(hit.getBlockPos())
                || item != Items.BUCKET && BuildProtection.blocksWorkerInteraction(hit.getBlockPos().offset(hit.getSide())))) {
            result.setReturnValue(ActionResult.FAIL);
        }
    }
}
