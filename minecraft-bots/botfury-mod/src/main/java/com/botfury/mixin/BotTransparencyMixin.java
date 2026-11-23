package com.botfury.mixin;

import com.botfury.BotVisuals;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
abstract class BotTransparencyMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void botfury$hideVanillaLabel(LivingEntity entity, CallbackInfoReturnable<Boolean> callback) {
        if (BotVisuals.hideVanillaLabel(entity)) callback.setReturnValue(false);
    }

    @Redirect(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/VertexConsumerProvider;getBuffer(Lnet/minecraft/client/render/RenderLayer;)Lnet/minecraft/client/render/VertexConsumer;"))
    private VertexConsumer botfury$model(VertexConsumerProvider provider, RenderLayer layer,
                                        LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                                        VertexConsumerProvider original, int light) {
        return BotVisuals.isBot(entity) ? BotVisuals.buffer(provider, layer, entity) : provider.getBuffer(layer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/feature/FeatureRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/entity/Entity;FFFFFF)V"))
    private void botfury$features(FeatureRenderer feature, MatrixStack matrices, VertexConsumerProvider provider, int light,
                                  Entity entity, float limbAngle, float limbDistance, float tickDelta,
                                  float animation, float headYaw, float headPitch) {
        VertexConsumerProvider buffers = BotVisuals.isBot(entity) ? layer -> BotVisuals.buffer(provider, layer, entity) : provider;
        feature.render(matrices, buffers, light, entity, limbAngle, limbDistance, tickDelta, animation, headYaw, headPitch);
    }
}
