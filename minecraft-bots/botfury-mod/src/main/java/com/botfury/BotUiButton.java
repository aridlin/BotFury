package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.text.Text;

/** A BotFury control with vanilla focus, activation and narration. */
final class BotUiButton extends ButtonWidget {
    private boolean highlighted;

    BotUiButton(String label, int x, int y, int width, int height, PressAction action) {
        super(x, y, width, height, Text.literal(label), action, DEFAULT_NARRATION_SUPPLIER);
    }

    void highlight(boolean selected) {
        highlighted = selected;
        active = !selected;
    }

    @Override protected void renderButton(DrawContext draw, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), right = x + getWidth(), bottom = y + getHeight();
        boolean focus = active && (isHovered() || isFocused());
        int fill = highlighted ? BotUiTheme.SELECTED : focus ? BotUiTheme.CARD_HOVER : active ? BotUiTheme.CARD : 0xFF2B1A22;
        int edge = highlighted || focus ? BotUiTheme.ACCENT : BotUiTheme.EDGE;
        draw.fill(x, y + 1, right, bottom - 1, fill);
        draw.fill(x + 1, y, right - 1, y + 1, edge);
        draw.fill(x + 1, bottom - 1, right - 1, bottom, 0xFF50313B);
        var font = MinecraftClient.getInstance().textRenderer;
        draw.drawCenteredTextWithShadow(font, font.trimToWidth(getMessage().getString(), getWidth() - 5),
                x + getWidth() / 2, y + (getHeight() - 8) / 2,
                active || highlighted ? BotUiTheme.TEXT : 0xFFAF8C90);
    }

    @Override public void playDownSound(SoundManager sounds) {}
}
