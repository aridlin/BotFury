package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Locale;

final class BotHud {
    static void render(DrawContext draw, float delta) {
        var client = MinecraftClient.getInstance();
        if (!BotUiConfig.hud || client.player == null || client.currentScreen != null || client.options.hudHidden
                || Boolean.getBoolean("botfury.managedHttp")) return;

        var fleet = BotTracker.all();
        var nearby = fleet.stream().filter(bot -> bot.fresh() && bot.connected() && bot.sameWorld()).toList();
        var target = BotTracker.get(BotTracker.selected());
        var tracked = BotUiConfig.displayedItems();
        String formation = LocalFormation.mode();
        boolean lightning = formation.equals("strike");
        int accent = BotUiTheme.ACCENT;
        String mode = switch (formation) {
            case "orbit" -> "Saturn";
            case "strike" -> "Lightning";
            case "idle" -> "Ready";
            default -> formation.toUpperCase(Locale.ROOT);
        };

        var window = client.getWindow();
        float scale = FleetPanel.scaleForWindow(window);
        var matrices = draw.getMatrices();
        matrices.push();
        matrices.scale(scale, scale, 1);
        int x = 8, y = 8, w = Math.min(202, (int)(window.getScaledWidth() / scale) - 16);
        int h = 62 + (tracked.isEmpty() ? 0 : 12 + ((tracked.size() + 2) / 3) * 22);
        var font = client.textRenderer;
        draw.fillGradient(x + 3, y, x + w - 3, y + h, BotUiTheme.PANEL_BOTTOM, BotUiTheme.PANEL_TOP);
        draw.fill(x, y + 3, x + 3, y + h - 3, BotUiTheme.PANEL_BOTTOM);
        draw.fill(x + w - 3, y + 3, x + w, y + h - 3, BotUiTheme.PANEL_BOTTOM);
        BotGeometry.circle(draw, x + 15, y + 13, 5.5f, lightning ? accent : BotUiTheme.SIGNAL);
        BotGeometry.circle(draw, x + 15, y + 13, 2f, BotUiTheme.PANEL_TOP);
        draw.drawText(font, mode, x + 25, y + 9, accent, false);
        String scope = target == null ? "Fleet" : target.name();
        draw.drawText(font, scope, x + w - 9 - font.getWidth(scope), y + 9, BotUiTheme.TEXT, false);

        boolean warning = !BotEvents.lastLevel.equals("info") && System.currentTimeMillis() - BotEvents.lastAt < 6000;
        String status = LocalFormation.stage();
        String strikeTarget = LocalFormation.targetName();
        if (lightning && !strikeTarget.isEmpty()) status = strikeTarget + (status.isBlank() ? "" : " · " + status);
        if (!lightning && target != null && !target.summary().isBlank()) status = target.summary();
        if (warning) status = BotEvents.last;
        if (status.isBlank() && nearby.isEmpty()) status = "No bots in this dimension";
        if (!status.equalsIgnoreCase("idle")) {
            draw.drawText(font, font.trimToWidth(status, w - 18), x + 9, y + 23,
                    warning ? BotUiTheme.ACCENT : BotUiTheme.TEXT, false);
        }

        long alive = nearby.stream().filter(BotTracker.Bot::alive).count();
        long hurt = nearby.stream().filter(bot -> bot.alive() && bot.maxHealth() > 0 && bot.health() <= bot.maxHealth() * .4).count();
        String availability = alive + "/" + fleet.size() + " ready here";
        if (nearby.size() > alive) availability += "  ·  " + (nearby.size() - alive) + " down";
        if (hurt > 0) availability += "  ·  " + hurt + " low HP";
        String availabilityText = font.trimToWidth(availability, w - 18);
        draw.drawText(font, availabilityText, x + 9, y + 36,
                hurt > 0 ? BotUiTheme.ACCENT : BotUiTheme.MUTED, false);
        int pipSpace = Math.max(0, w - 18 - font.getWidth(availabilityText) - 4);
        int shown = Math.min(Math.min(6, fleet.size()), pipSpace / 6);
        for (int i = 0; i < shown; i++) {
            BotGeometry.circle(draw, x + w - 12 - (shown - 1 - i) * 6, y + 39, 1.6f,
                    i < Math.ceil(alive * shown / (double) fleet.size()) ? BotUiTheme.SIGNAL : BotUiTheme.EDGE);
        }

        if (!tracked.isEmpty()) {
            draw.drawText(font, "Fleet stock", x + 9, y + 49, BotUiTheme.MUTED, false);
            var countsFrom = fleet.stream().filter(bot -> bot.fresh() && bot.connected()).toList();
            int space = (w - 18) / Math.min(3, tracked.size());
            for (int i = 0; i < tracked.size(); i++) {
                var item = tracked.get(i);
                int cellX = x + 9 + i % 3 * space;
                int cellY = y + 59 + i / 3 * 22;
                int quantity = 0, known = 0;
                for (var bot : countsFrom) {
                    if (bot.items() == null) continue;
                    known++;
                    quantity += item.count(bot.items());
                }
                draw.drawItem(Registries.ITEM.get(new Identifier(item.icon())).getDefaultStack(), cellX, cellY);
                String count = known == 0 ? "—" : Integer.toString(quantity) + (known < countsFrom.size() ? "+" : "");
                draw.drawText(font, font.trimToWidth(count, Math.max(0, space - 20)), cellX + 18, cellY + 5, BotUiTheme.TEXT, false);
            }
        }

        String keys = FormationClient.wheel.getBoundKeyLocalizedText().getString() + "  wheel  ·  "
                + FormationClient.quick.getBoundKeyLocalizedText().getString() + "  "
                + (lightning ? (LocalFormation.willResumeSaturn() ? "return to Saturn" : "stop strike") : "strike / stop");
        draw.drawText(font, font.trimToWidth(keys, w - 18), x + 9, y + h - 13, BotUiTheme.MUTED, false);
        matrices.pop();
    }
}
