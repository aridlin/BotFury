package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

final class CoordinatesScreen extends FleetPanel {
    private final Screen parent;
    private BotUiField x, y, z;
    private String error = "";

    CoordinatesScreen(Screen parent) {
        super(Text.literal("Baritone goto"));
        this.parent = parent;
    }

    @Override public boolean shouldPause() { return false; }

    @Override protected void initPanel() {
        int left = width / 2 - 150;
        var pos = client.player.getBlockPos();
        x = coordinateField(left, "X", pos.getX());
        y = coordinateField(left + 102, "Y", pos.getY());
        z = coordinateField(left + 204, "Z", pos.getZ());
        addDrawableChild(new BotUiButton("Go", width / 2 - 100, 125, 95, 22, button -> go(), true));
        addDrawableChild(new BotUiButton("Back", width / 2 + 5, 125, 95, 22, button -> close(), true));
    }

    private BotUiField coordinateField(int left, String axis, int value) {
        var field = new BotUiField(textRenderer, left, 80, 95, 22, Text.literal(axis));
        field.setText(Integer.toString(value));
        return addDrawableChild(field);
    }

    private void go() {
        try {
            int targetX = Integer.parseInt(x.getText());
            int targetY = Integer.parseInt(y.getText());
            int targetZ = Integer.parseInt(z.getText());
            if (Math.abs((long) targetX) > 30000000 || Math.abs((long) targetZ) > 30000000
                    || targetY < -128 || targetY > 512) throw new NumberFormatException();
            BotTracker.sendSelected("baritone:goto " + targetX + " " + targetY + " " + targetZ);
            client.setScreen(null);
        } catch (NumberFormatException error) {
            this.error = "Enter valid integer coordinates";
        }
    }

    @Override protected DitherPress.Surface frameSurface() { return DitherPress.Surface.DIALOG; }

    @Override protected void renderPanel(DrawContext draw, int mouseX, int mouseY, float delta) {
        draw.drawCenteredTextWithShadow(textRenderer, title, width / 2, 24, BotUiTheme.ACCENT);
        draw.drawCenteredTextWithShadow(textRenderer, "X                         Y                         Z", width / 2, 66, BotUiTheme.MUTED);
        draw.drawCenteredTextWithShadow(textRenderer, error.isEmpty() ? BotTracker.targetLabel() : error,
                width / 2, 165, BotUiTheme.ACCENT);
        super.renderPanel(draw, mouseX, mouseY, delta);
    }

    @Override protected boolean panelMouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { close(); return true; }
        return super.panelMouseClicked(x, y, button);
    }

    @Override public void close() { client.setScreen(parent); }
}
