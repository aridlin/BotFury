package com.botfury;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Keep vanilla typing, caret and selection geometry for BotFury's text fields. */
public final class BotUiField extends TextFieldWidget {
    BotUiField(TextRenderer font, int x, int y, int width, int height, Text label) {
        super(font, x, y, width, height, label);
        setEditableColor(BotUiTheme.TEXT & 0xffffff);
        setUneditableColor(BotUiTheme.MUTED & 0xffffff);
    }

    @Override public void renderButton(DrawContext draw, int mouseX, int mouseY, float delta) {
        if (!isVisible()) return;
        var surface = isFocused() ? DitherPress.Surface.FIELD_HOT : DitherPress.Surface.FIELD;
        DitherPress.draw(draw, surface, getX() - 1, getY() - 1, getX() + getWidth() + 1, getY() + getHeight() + 1);
        super.renderButton(draw, mouseX, mouseY, delta);
    }
}
