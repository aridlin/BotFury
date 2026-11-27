package com.botfury.mixin;

import com.botfury.BotUiField;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Suppress the two background fills only for fields that already drew their BotFury plate. */
@Mixin(TextFieldWidget.class)
abstract class BotUiFieldBackgroundMixin {
    @Redirect(method = "renderButton", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V", ordinal = 0))
    private void botfury$border(DrawContext draw, int x, int y, int right, int bottom, int color) {
        if (!((Object) this instanceof BotUiField)) draw.fill(x, y, right, bottom, color);
    }

    @Redirect(method = "renderButton", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V", ordinal = 1))
    private void botfury$background(DrawContext draw, int x, int y, int right, int bottom, int color) {
        if (!((Object) this instanceof BotUiField)) draw.fill(x, y, right, bottom, color);
    }
}
