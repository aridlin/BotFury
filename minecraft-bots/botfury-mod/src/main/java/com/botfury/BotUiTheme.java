package com.botfury;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

final class BotUiTheme {
    static final int ACCENT = DitherPress.PAPER;
    static final int ACCENT_RGB = ACCENT & 0xFFFFFF;
    static final int TEXT = DitherPress.PAPER;
    static final int MUTED = 0xFFD8C8AD;
    static final int DANGER = 0xFFF48361;
    static final int PANEL_TOP = DitherPress.INK;
    static final int EDGE = DitherPress.RUST;
    static final int SIGNAL = DitherPress.ORANGE;

    static void panel(DrawContext draw, int x, int y, int right, int bottom) {
        DitherPress.draw(draw, DitherPress.Surface.PANEL, x, y, right, bottom);
    }

    static void row(DrawContext draw, int x, int y, int right, int bottom, boolean selected, boolean hovered) {
        var treatment = selected ? DitherPress.Surface.SELECTED : hovered ? DitherPress.Surface.HOVERED : DitherPress.Surface.CARD;
        DitherPress.draw(draw, treatment, x, y, right, bottom);
    }

    static void label(DrawContext draw, int x, int y, int right, int bottom) {
        DitherPress.draw(draw, DitherPress.Surface.LABEL, x, y, right, bottom);
    }

    static void tooltip(DrawContext draw, TextRenderer text, Text name, int mouseX, int mouseY, int width, int height) {
        var lines = text.wrapLines(name, width - 20);
        int w = lines.stream().mapToInt(text::getWidth).max().orElse(0) + 12;
        int h = lines.size() * 10 + 8;
        int x = Math.max(4, Math.min(width - w - 4, mouseX + 10));
        int y = Math.max(4, Math.min(height - h - 4, mouseY + 10));
        label(draw, x, y, x + w, y + h);
        for (var line : lines) {
            draw.drawText(text, line, x + 5, y + 4, TEXT, false);
            y += 10;
        }
    }

    private BotUiTheme() {}
}
