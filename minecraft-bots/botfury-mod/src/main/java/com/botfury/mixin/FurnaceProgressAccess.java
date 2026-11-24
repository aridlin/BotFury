package com.botfury.mixin;

import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.PropertyDelegate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractFurnaceScreenHandler.class)
public interface FurnaceProgressAccess {
    @Accessor("propertyDelegate") PropertyDelegate botfury$properties();

    default int botfury$burnTime() { return botfury$properties().get(0); }

    default int botfury$cookTime() { return botfury$properties().get(2); }
}
