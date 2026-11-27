package com.botfury;

import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

final class BotInventoryPreview extends FleetPanel {
    private static final int MARGIN = 12;
    private static final int HEADER = 34;
    private static final int GAP = 6;
    private static final int SLOT = 23;
    private int scroll;
    private int scrollLimit;

    BotInventoryPreview() { super(Text.literal("Bot inventories")); }

    @Override protected void initPanel() {}
    @Override protected boolean framed() { return false; }
    @Override public boolean shouldPause() { return false; }

    @Override public void tick() {
        if (client.player == null || !client.isWindowFocused() || !hasShiftDown()) close();
    }

    @Override public boolean keyReleased(int key, int scanCode, int modifiers) {
        if (FormationClient.quick.matchesKey(key, scanCode)) {
            close();
            return true;
        }
        return super.keyReleased(key, scanCode, modifiers);
    }

    @Override protected boolean panelMouseReleased(double x, double y, int button) {
        if (FormationClient.quick.matchesMouse(button)) {
            close();
            return true;
        }
        return super.panelMouseReleased(x, y, button);
    }

    @Override protected boolean panelMouseScrolled(double x, double y, double amount) {
        scroll = MathHelper.clamp(scroll - (int) Math.signum(amount) * 38, 0, scrollLimit);
        return true;
    }

    @Override protected void renderPanel(DrawContext draw, int mouseX, int mouseY, float delta) {
        var bots = BotTracker.all();
        int cardWidth = (width - 2 * MARGIN - 2 * GAP) / 2;
        int columns = (cardWidth - 12) / SLOT;
        var inventories = bots.stream().map(BotInventoryPreview::items).toList();
        int total = 0;
        for (int i = 0; i < inventories.size(); i += 2) total += rowHeight(inventories, i, columns) + GAP;
        total = Math.max(0, total - GAP);
        int visible = MathHelper.clamp(total, 18, height - HEADER - MARGIN);
        int panelHeight = HEADER + visible + MARGIN;
        int panelTop = (height - panelHeight) / 2;
        int listTop = panelTop + HEADER;
        int bottom = listTop + visible;
        draw.fillGradient(0, panelTop, width, panelTop + panelHeight, BotUiTheme.PANEL_TOP, BotUiTheme.PANEL_BOTTOM);
        draw.drawText(textRenderer, getTitle(), MARGIN, panelTop + 13, BotUiTheme.ACCENT, false);
        String count = bots.size() + (bots.size() == 1 ? " bot" : " bots");
        draw.drawText(textRenderer, count, width - MARGIN - textRenderer.getWidth(count), panelTop + 13, BotUiTheme.MUTED, false);
        if (bots.isEmpty()) {
            draw.drawText(textRenderer, "No tracked bots", MARGIN, listTop, BotUiTheme.MUTED, false);
            return;
        }

        scrollLimit = Math.max(0, total - visible);
        scroll = Math.min(scroll, scrollLimit);
        int y = listTop - scroll;
        Text tooltip = null;
        clipPanel(draw, MARGIN, listTop, width - MARGIN, bottom);
        for (int i = 0; i < bots.size() && y < bottom; i += 2) {
            int rowHeight = rowHeight(inventories, i, columns);
            if (y + rowHeight > listTop) {
                for (int j = i; j < Math.min(bots.size(), i + 2); j++) {
                    int x = MARGIN + (j - i) * (cardWidth + GAP);
                    var hovered = renderCard(draw, bots.get(j), inventories.get(j), x, y, cardWidth, rowHeight,
                            columns, mouseX, mouseY >= listTop && mouseY < bottom ? mouseY : -1);
                    if (hovered != null) tooltip = hovered;
                }
            }
            y += rowHeight + GAP;
        }
        draw.disableScissor();
        if (scrollLimit > 0) {
            int thumb = Math.max(14, visible * visible / total);
            int top = listTop + (visible - thumb) * scroll / scrollLimit;
            draw.fill(width - MARGIN - 2, listTop, width - MARGIN, bottom, BotUiTheme.EDGE);
            draw.fill(width - MARGIN - 2, top, width - MARGIN, top + thumb, BotUiTheme.ACCENT);
        }
        if (tooltip != null) draw.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
    }

    private Text renderCard(DrawContext draw, BotTracker.Bot bot, List<Map.Entry<String, Integer>> items,
                            int x, int y, int w, int h, int columns, int mouseX, int mouseY) {
        draw.fill(x, y, x + w, y + h, BotUiTheme.CARD);
        draw.drawText(textRenderer, textRenderer.trimToWidth(bot.name(), w - 16), x + 8, y + 6, BotUiTheme.TEXT, false);
        if (items.isEmpty()) {
            String status = !bot.fresh() ? "Offline" : !bot.connected() ? "At title screen"
                    : bot.items() == null ? "Inventory unavailable" : "Empty";
            draw.drawText(textRenderer, status, x + 8, y + 21, BotUiTheme.MUTED, false);
            return null;
        }
        Text tooltip = null;
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            int left = x + 6 + i % columns * SLOT;
            int top = y + 18 + i / columns * SLOT;
            draw.fill(left, top, left + SLOT - 2, top + SLOT - 2, BotUiTheme.PANEL_BOTTOM);
            ItemStack stack = Registries.ITEM.get(new Identifier(item.getKey())).getDefaultStack();
            draw.drawItem(stack, left + 2, top + 1);
            if (item.getValue() > 1) {
                String amount = item.getValue().toString();
                draw.drawText(textRenderer, amount, left + SLOT - 3 - textRenderer.getWidth(amount),
                        top + 12, BotUiTheme.TEXT, true);
            }
            if (mouseX >= left && mouseX < left + SLOT - 2 && mouseY >= top && mouseY < top + SLOT - 2) {
                tooltip = stack.getName().copy().append(" ×" + item.getValue());
            }
        }
        return tooltip;
    }

    private static int rowHeight(List<List<Map.Entry<String, Integer>>> inventories, int i, int columns) {
        int count = inventories.get(i).size();
        if (i + 1 < inventories.size()) count = Math.max(count, inventories.get(i + 1).size());
        return 20 + Math.max(1, (count + columns - 1) / columns) * SLOT;
    }

    private static List<Map.Entry<String, Integer>> items(BotTracker.Bot bot) {
        if (!bot.fresh() || !bot.connected() || bot.items() == null) return List.of();
        return bot.items().entrySet().stream().filter(entry -> {
            var id = Identifier.tryParse(entry.getKey());
            return id != null && Registries.ITEM.containsId(id);
        }).sorted(Map.Entry.comparingByKey()).toList();
    }
}
