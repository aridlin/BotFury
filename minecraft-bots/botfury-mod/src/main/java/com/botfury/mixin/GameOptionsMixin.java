package com.botfury.mixin;

import com.botfury.MiningViewDistance;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
    @ModifyArg(method = "sendClientSettings", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/packet/c2s/play/ClientSettingsC2SPacket;<init>"
                    + "(Ljava/lang/String;ILnet/minecraft/client/option/ChatVisibility;ZILnet/minecraft/util/Arm;ZZ)V"), index = 1)
    private int botfury$searchViewDistance(int configured) {
        return MiningViewDistance.requestedDistance(configured);
    }
}
