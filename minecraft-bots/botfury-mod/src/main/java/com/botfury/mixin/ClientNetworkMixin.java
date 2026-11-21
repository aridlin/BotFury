package com.botfury.mixin;

import com.botfury.BotFuryMod;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientNetworkMixin {
    @Inject(method = "onPlayerPositionLook", at = @At("TAIL"))
    private void botfury$correction(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        BotFuryMod.onPositionCorrection();
    }
}
