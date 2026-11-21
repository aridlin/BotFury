package com.botfury.mixin;
import com.botfury.BotFuryMod;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class BotPushMixin {
    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void botfury$avoidFleetPush(Entity other, CallbackInfo ci) {
        if (BotFuryMod.cancelFleetPush((Entity)(Object)this, other)) ci.cancel();
    }
}
