package com.botfury.mixin;

import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.render.RenderLayer$MultiPhase")
public interface RenderLayerAccess {
    @Accessor("phases") RenderLayer.MultiPhaseParameters botfury$phases();
}
