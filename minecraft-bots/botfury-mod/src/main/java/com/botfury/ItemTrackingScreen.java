package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

final class ItemTrackingScreen extends FleetPanel {
    private final Screen parent;
    private static final int FIRST_ROW = 44;
    private static final int ROW_HEIGHT = 21;

    ItemTrackingScreen(Screen parent) { super(Text.literal("Track items")); this.parent = parent; }
    @Override public boolean shouldPause() { return false; }

    @Override protected void initPanel() {
        int x = width / 2 - 120;
        for (int index = 0; index < BotUiConfig.trackedItems.size(); index++) {
            String id = BotUiConfig.trackedItems.get(index);
            var remove = ButtonWidget.builder(Text.literal("Remove"), button -> {
                BotUiConfig.trackedItems.remove(id);
                BotUiConfig.save();
                clearAndInit();
            }).dimensions(x + 181, FIRST_ROW + index * ROW_HEIGHT, 59, 19).build();
            addDrawableChild(remove);
        }
        int row = FIRST_ROW + Math.max(1, BotUiConfig.trackedItems.size()) * ROW_HEIGHT + 8;
        var add = ButtonWidget.builder(Text.literal("Add item…"), button ->
                client.setScreen(new ItemPickerScreen(this, false, id -> {
                    if (BotUiConfig.addTracked(id)) BotUiConfig.save();
                    client.setScreen(this);
                }))).dimensions(x, row, 116, 20).build();
        add.active = BotUiConfig.trackedItems.size() < BotUiConfig.TRACK_LIMIT;
        addDrawableChild(add);
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> close())
                .dimensions(x + 124, row, 116, 20).build());
    }

    @Override protected void renderPanel(DrawContext draw, int x, int y, float delta) {
        draw.fill(0, 0, width, height, BotUiTheme.PANEL_TOP);
        draw.drawCenteredTextWithShadow(textRenderer, title, width / 2, 19, BotUiTheme.ACCENT);
        if (BotUiConfig.trackedItems.isEmpty()) {
            draw.drawCenteredTextWithShadow(textRenderer, "No items tracked", width / 2, FIRST_ROW + 5, BotUiTheme.MUTED);
        }
        for (int index = 0; index < BotUiConfig.trackedItems.size(); index++) {
            var item = Registries.ITEM.get(new Identifier(BotUiConfig.trackedItems.get(index)));
            int row = FIRST_ROW + index * ROW_HEIGHT;
            draw.drawItem(item.getDefaultStack(), width / 2 - 120, row + 1);
            draw.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(item.getName().getString(), 154),
                    width / 2 - 99, row + 5, BotUiTheme.TEXT);
        }
        super.renderPanel(draw, x, y, delta);
    }

    @Override protected boolean panelMouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { close(); return true; }
        return super.panelMouseClicked(x, y, button);
    }
    @Override public void close() { client.setScreen(parent); }
}
