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
        var tracked = BotUiConfig.trackedItems;
        String formation = LocalFormation.mode();
        boolean lightning = formation.equals("strike");
        int accent = lightning ? 0xFFFFCB7F : 0xFF71E4CB;
        String mode = switch (formation) {
            case "orbit" -> "Saturn";
            case "strike" -> "Lightning";
            case "idle" -> "Ready";
            default -> formation.toUpperCase(Locale.ROOT);
        };

        int x = 10, y = 10, w = Math.min(232, client.getWindow().getScaledWidth() - 20);
        int h = 69 + ((tracked.size() + 2) / 3) * 22;
        var font = client.textRenderer;
        draw.fillGradient(x, y, x + w, y + h, 0xE9162138, 0xE9121A2B);
        draw.fill(x, y, x + w, y + 2, accent);
        draw.drawText(font, mode, x + 9, y + 9, accent, false);
        String scope = target == null ? "Fleet" : target.name();
        draw.drawText(font, scope, x + w - 9 - font.getWidth(scope), y + 9, 0xFFEAF2FA, false);

        boolean warning = !BotEvents.lastLevel.equals("info") && System.currentTimeMillis() - BotEvents.lastAt < 6000;
        String status = LocalFormation.stage();
        String strikeTarget = LocalFormation.targetName();
        if (lightning && !strikeTarget.isEmpty()) status = strikeTarget + (status.isBlank() ? "" : " · " + status);
        if (!lightning && target != null && !target.summary().isBlank()) status = target.summary();
        if (warning) status = BotEvents.last;
        if (status.isBlank()) status = nearby.isEmpty() ? "No bots in this dimension" : "Awaiting orders";
        draw.drawText(font, font.trimToWidth(status, w - 18), x + 9, y + 23,
                warning ? 0xFFFFCB7F : 0xFFD6E4F4, false);

        long alive = nearby.stream().filter(BotTracker.Bot::alive).count();
        long hurt = nearby.stream().filter(bot -> bot.alive() && bot.maxHealth() > 0 && bot.health() <= bot.maxHealth() * .4).count();
        String availability = alive + "/" + fleet.size() + " ready here";
        if (nearby.size() > alive) availability += "  ·  " + (nearby.size() - alive) + " down";
        if (hurt > 0) availability += "  ·  " + hurt + " low HP";
        draw.drawText(font, font.trimToWidth(availability, w - 18), x + 9, y + 36,
                hurt > 0 ? 0xFFFFCB7F : 0xFFABBDD4, false);
        draw.fill(x + 9, y + 48, x + w - 9, y + 50, 0xFF32445D);
        if (!fleet.isEmpty()) draw.fill(x + 9, y + 48, x + 9 + (int)((w - 18) * alive / fleet.size()), y + 50, accent);

        if (!tracked.isEmpty()) {
            var countsFrom = target == null ? nearby : nearby.stream().filter(bot -> bot.port() == target.port()).toList();
            int space = (w - 18) / Math.min(3, tracked.size());
            for (int i = 0; i < tracked.size(); i++) {
                String id = tracked.get(i);
                int cellX = x + 9 + i % 3 * space;
                int cellY = y + 54 + i / 3 * 22;
                int quantity = 0, known = 0;
                for (var bot : countsFrom) {
                    if (bot.items() == null) continue;
                    known++;
                    quantity += bot.items().getOrDefault(id, 0);
                }
                draw.drawItem(Registries.ITEM.get(new Identifier(id)).getDefaultStack(), cellX, cellY);
                String count = known == 0 ? "—" : Integer.toString(quantity) + (known < countsFrom.size() ? "+" : "");
                draw.drawText(font, font.trimToWidth(count, Math.max(0, space - 20)), cellX + 18, cellY + 5, 0xFFEAF2FA, false);
            }
        }

        String keys = FormationClient.wheel.getBoundKeyLocalizedText().getString() + "  wheel  ·  "
                + FormationClient.quick.getBoundKeyLocalizedText().getString() + "  "
                + (lightning ? (LocalFormation.willResumeSaturn() ? "return to Saturn" : "stop strike") : "strike / stop");
        draw.drawText(font, font.trimToWidth(keys, w - 18), x + 9, y + h - 13, 0xFF8FA8C1, false);
    }
}
