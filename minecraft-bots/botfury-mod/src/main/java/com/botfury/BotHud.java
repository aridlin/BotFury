package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

final class BotHud {
    static void render(DrawContext draw, float delta) {
        var client = MinecraftClient.getInstance();
        if (!BotUiConfig.hud || client.player == null || client.currentScreen != null || client.options.hudHidden
                || Boolean.getBoolean("botfury.managedHttp")) return;
        int x = 10, y = 10, w = 218, h = 72;
        int accent = LocalFormation.mode().equals("strike") ? 0xFFFFC35C : 0xFF5DE4D2;
        draw.fill(x, y, x + w, y + h, 0xD5111926);
        draw.fill(x, y, x + 3, y + h, accent);
        draw.fill(x + 3, y, x + w, y + 1, 0x554B647D);
        draw.drawText(client.textRenderer, "BOTFURY", x + 12, y + 9, accent, false);
        String mode = switch (LocalFormation.mode()) { case "orbit" -> "SATURN"; case "strike" -> "LIGHTNING"; case "idle" -> "READY"; default -> LocalFormation.mode().toUpperCase(java.util.Locale.ROOT); };
        draw.drawText(client.textRenderer, mode, x + w - 10 - client.textRenderer.getWidth(mode), y + 9, 0xFFEAF2FA, false);
        String status = LocalFormation.stage();
        if (!LocalFormation.targetName().isEmpty()) status = LocalFormation.targetName();
        boolean warning = !BotEvents.lastLevel.equals("info") && System.currentTimeMillis() - BotEvents.lastAt < 6000;
        if (warning) status = BotEvents.last;
        draw.drawText(client.textRenderer, client.textRenderer.trimToWidth(status, w - 24), x + 12, y + 26,
                warning ? 0xFFFFC35C : 0xFFC3D0DF, false);
        long online = BotTracker.all().stream().filter(bot -> bot.fresh() && bot.connected()).count();
        String fleet = online + " bots online";
        if (LocalFormation.incompatible() > 0) fleet += "  ·  update " + LocalFormation.incompatible();
        draw.drawText(client.textRenderer, fleet, x + 12, y + 40, 0xFF91A4BC, false);
        String keys = FormationClient.wheel.getBoundKeyLocalizedText().getString() + "  wheel     "
                + FormationClient.quick.getBoundKeyLocalizedText().getString() + "  "
                + (LocalFormation.mode().equals("strike") ? (LocalFormation.willResumeSaturn() ? "return to Saturn" : "stop strike") : "strike / stop");
        draw.drawText(client.textRenderer, client.textRenderer.trimToWidth(keys, w - 24), x + 12, y + 56, 0xFF7F93AD, false);
        draw.fill(x + w - 54, y + 44, x + w - 12, y + 47, 0xFF29384D);
        draw.fill(x + w - 54, y + 44, x + w - 54 + (int)(42 * client.player.getAttackCooldownProgress(delta)), y + 47, accent);
    }
}
