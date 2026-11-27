package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.text.Text;

/** A BotFury control with vanilla focus, activation and narration. */
final class BotUiButton extends ButtonWidget {
    private boolean highlighted;
    private final boolean vanillaSound;

    BotUiButton(String label, int x, int y, int width, int height, PressAction action) {
        this(label, x, y, width, height, action, false);
    }

    BotUiButton(String label, int x, int y, int width, int height, PressAction action, boolean vanillaSound) {
        super(x, y, width, height, Text.literal(label), action, DEFAULT_NARRATION_SUPPLIER);
        this.vanillaSound = vanillaSound;
    }

    void highlight(boolean selected) {
        highlighted = selected;
        active = !selected;
    }

    void emphasize() {
        highlighted = true;
    }

    @Override protected void renderButton(DrawContext draw, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), right = x + getWidth(), bottom = y + getHeight();
        boolean focus = active && (isHovered() || isFocused());
        var face = highlighted || focus ? DitherPress.Surface.BUTTON_HOT : DitherPress.Surface.BUTTON;
        DitherPress.draw(draw, face, x, y, right, bottom);
        var font = MinecraftClient.getInstance().textRenderer;
        draw.drawCenteredTextWithShadow(font, font.trimToWidth(getMessage().getString(), getWidth() - 5),
                x + getWidth() / 2, y + (getHeight() - 8) / 2,
                active || highlighted ? BotUiTheme.TEXT : 0xFFAD9B83);
    }

    @Override public void playDownSound(SoundManager sounds) {
        if (vanillaSound) super.playDownSound(sounds);
    }
}
