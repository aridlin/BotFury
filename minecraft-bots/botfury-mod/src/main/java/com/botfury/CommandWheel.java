package com.botfury;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.*;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.Arrays;

final class CommandWheel extends Screen {
    private int page, quantity = 1, selected = -1;
    private boolean quantityChosen;
    private final float[] emphasis = new float[8];
    private long openedAt = Util.getMeasuringTimeMs();
    private long lastFrame = openedAt;
    private long lastHover;
    private int outputBatch = 10;
    private final net.minecraft.util.hit.HitResult aimedChest;
    private String item;
    private final String aimedFriend;
    private static final String[][] LABELS = {
            {"Saturn", "Lightning", "Scatter", "Gather", "Inventory", "Bots", "Baritone", "Stop"},
            {"Bring", "Collect", "Keep X", "Only X", "Drop X", "Output…", "Item…", "Back"},
            {"Add aimed", "Remove", "List friends", "HUD", "Markers", "", "", "Back"},
            {"Mine…", "Goto…", "Follow", "Pause", "Resume", "Stop path", "Friends", "Back"},
            {"Set output", "Cancel output", "Spread", "", "", "", "Item…", "Back"}};
    private static final Item[][] ICONS = {
            {Items.ENDER_PEARL, Items.TRIDENT, Items.FEATHER, Items.LEAD, Items.CHEST, Items.PLAYER_HEAD, Items.IRON_PICKAXE, Items.BARRIER},
            {Items.HOPPER, Items.CHEST_MINECART, Items.IRON_PICKAXE, Items.BUNDLE, Items.DROPPER, Items.CHEST, Items.BOOK, Items.ARROW},
            {Items.EMERALD, Items.REDSTONE, Items.BOOK, Items.COMPASS, Items.ENDER_EYE, Items.AIR, Items.AIR, Items.ARROW},
            {Items.DIAMOND_PICKAXE, Items.MAP, Items.LEAD, Items.CLOCK, Items.FEATHER, Items.BARRIER, Items.PLAYER_HEAD, Items.ARROW},
            {Items.CHEST, Items.BARRIER, Items.FEATHER, Items.AIR, Items.AIR, Items.AIR, Items.BOOK, Items.ARROW}};
    CommandWheel() {
        super(Text.literal("BotFury command wheel"));
        var client = net.minecraft.client.MinecraftClient.getInstance();
        item = ClientActions.taskItem(client);
        aimedChest = client.crosshairTarget;
        var target = FormationClient.visibleTarget(client);
        aimedFriend = target instanceof PlayerEntity player ? player.getGameProfile().getName() : "";
        BotUiSound.open();
    }
    @Override public boolean shouldPause() { return false; }
    private float radius() { return Math.min(112, Math.min(height * .32f, width * .37f)); }

    private int sectorAt(double x, double y) {
        double dx = x - width / 2, dy = y - height / 2;
        float radius = radius();
        if (Math.hypot(dx, dy) > radius * 1.21) return -1;
        int sector = WheelMath.segment(dx, dy, radius * .32);
        return sector < 0 || LABELS[page][sector].isEmpty() ? -1 : sector;
    }

    private void showPage(int next) {
        page = next;
        selected = -1;
        Arrays.fill(emphasis, 0);
        openedAt = Util.getMeasuringTimeMs();
    }

    @Override public void render(DrawContext draw, int mouseX, int mouseY, float delta) {
        int cx = width / 2, cy = height / 2;
        float baseRadius = radius();
        long now = Util.getMeasuringTimeMs();
        float speed = (float)(1 - Math.exp(-Math.max(1, now - lastFrame) / 57.0));
        lastFrame = now;
        int hovered = sectorAt(mouseX, mouseY);
        if (hovered != selected && hovered >= 0 && now - lastHover >= 90) {
            BotUiSound.hover();
            lastHover = now;
        }
        selected = hovered;

        draw.fillGradient(0, 0, width, height, 0xC20A101E, 0xA8091020);
        String heading = switch (page) {
            case 1 -> "Inventory";
            case 2 -> "Friends";
            case 3 -> "Baritone";
            case 4 -> "Output chest";
            default -> "BotFury";
        };
        draw.drawCenteredTextWithShadow(textRenderer, heading, cx, Math.max(6, (int)(cy - baseRadius - 23)), 0xFFF1F6FF);

        for (int index = 0; index < 8; ++index) {
            boolean enabled = !LABELS[page][index].isEmpty();
            float hover = emphasis[index] += ((selected == index ? 1 : 0) - emphasis[index]) * speed;
            double time = Math.max(0, now - openedAt - (index % 4) * 12);
            float spring = (float)(1 - .28 * Math.exp(-time / 76) * Math.cos(time / 36));
            float radius = baseRadius * spring;
            double angle = -Math.PI / 2 + index * Math.PI / 4;
            float dx = (float)Math.cos(angle), dy = (float)Math.sin(angle);
            float shift = hover * 3;
            float x = cx + dx * shift, y = cy + dy * shift;
            float edge = radius + hover * 6;
            int outer = enabled ? mix(0xF5303858, 0xFF5BE4C9, hover * .77f) : 0x6F263149;
            int inner = enabled ? mix(0xFA141C33, 0xFB1B5657, hover) : 0x70131B2B;
            wedge(draw, x, y, radius * .36f, edge, index, inner, outer);
            if (hover > .03f) {
                wedge(draw, x, y, edge - 2.7f, edge, index,
                        (Math.round(hover * 190) << 24) | 0x66E5CE,
                        (Math.round(hover * 240) << 24) | 0xD8FFEF);
            }
            if (!enabled) continue;
            int ix = Math.round(x + dx * radius * .71f), iy = Math.round(y + dy * radius * .71f);
            draw.drawItem(ICONS[page][index].getDefaultStack(), ix - 8, iy - 12);
            String label = page == 1 && index == 4 && !quantityChosen ? "Drop all" : LABELS[page][index];
            draw.drawCenteredTextWithShadow(textRenderer, label, ix, iy + 8, mix(0xFFCCD8ED, 0xFFFFFFFF, hover));
        }

        String center = switch (page) {
            case 1 -> selected == 4 && !quantityChosen ? "All" : "×" + quantity;
            case 4 -> "×" + outputBatch;
            default -> BotTracker.selected() < 0 ? "Fleet" : "1 bot";
        };
        draw.drawCenteredTextWithShadow(textRenderer, center, cx, cy - 4, 0xFFC5FAEF);
        String hint;
        if (page == 1 || page == 4) hint = net.minecraft.registry.Registries.ITEM.get(new net.minecraft.util.Identifier(item))
                .getName().getString() + "  ·  Scroll to set count";
        else if (page == 2) hint = aimedFriend.isEmpty() ? "Aim at a player before opening" : "Aimed at " + aimedFriend;
        else hint = "Target  " + BotTracker.targetLabel();
        if (page == 1 && selected == 3) hint = "Drops other main-inventory items";
        draw.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(hint, width - 24),
                cx, (int)(cy + baseRadius + 8), page == 1 && selected == 3 ? 0xFFFFCD90 : 0xFFB9C8DC);
    }

    private static int mix(int from, int to, float amount) {
        int color = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int start = (from >>> shift) & 255, end = (to >>> shift) & 255;
            color |= Math.round(start + (end - start) * amount) << shift;
        }
        return color;
    }

    private static void wedge(DrawContext draw, float cx, float cy, float inner, float outer,
                              int sector, int innerColor, int outerColor) {
        draw.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        var matrix = draw.getMatrices().peek().getPositionMatrix();
        var buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        double start = -Math.PI / 2 + sector * Math.PI / 4 - Math.PI / 8 + .034;
        for (int i = 0; i <= 24; ++i) {
            double t = i / 24.0;
            double angle = start + (Math.PI / 4 - .068) * t;
            float x = (float)Math.cos(angle), y = (float)Math.sin(angle);
            float rounding = (float)Math.pow(Math.abs(2 * t - 1), 9);
            float inset = (outer - inner) * .46f * rounding;
            buffer.vertex(matrix, cx + x * (outer - inset), cy + y * (outer - inset), 0).color(outerColor).next();
            buffer.vertex(matrix, cx + x * (inner + inset), cy + y * (inner + inset), 0).color(innerColor).next();
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.disableBlend();
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { BotUiSound.back(); close(); return true; }
        if (button == 1) { back(); return true; }
        if (button != 0) return false;
        selected = sectorAt(x, y);
        if (selected < 0) {
            if (Math.hypot(x - width / 2, y - height / 2) < radius() * .32) back();
            return true;
        }
        BotUiSound.confirm();
        if (page == 0 && selected == 4) { showPage(1); return true; }
        if (page == 0 && selected == 5) { client.setScreen(new BotRosterScreen(this)); return true; }
        if (page == 0 && selected == 6) { showPage(3); return true; }
        if (page == 1 && selected == 5) { showPage(4); return true; }
        if ((page == 1 || page == 4) && selected == 6) { client.setScreen(new ItemPickerScreen(this,false,id->{item=id;BotUiConfig.item=id;BotUiConfig.save();client.setScreen(this);}));return true; }
        if (page == 3 && selected == 0) {client.setScreen(new MiningScreen(this));return true;}
        if (page == 3 && selected == 1) {client.setScreen(new CoordinatesScreen(this));return true;}
        if (page == 3 && selected == 6) {showPage(2);return true;}
        if (page != 0 && selected == 7) { showPage(page == 4 ? 1 : page == 2 ? 3 : 0); return true; }
        int action = selected, currentPage = page;
        close();
        if (currentPage == 0) ClientActions.action(client, new String[]{"saturn", "lightning", "scatter", "gather", "", "", "hud", "stop"}[action]);
        else if (currentPage == 1) {
            ClientActions.inventory(client, new String[]{"deliver", "collect", "keep", "only", "drop", "spread"}[action], item, action==4&&!quantityChosen?2304:quantity);
        } else if (currentPage == 4) {
            if (action == 0) OutputOrder.submit(client, new net.minecraft.util.Identifier(item), outputBatch, aimedChest);
            else if (action == 1) OutputOrder.cancel(client, aimedChest);
            else if (action == 2) ClientActions.inventory(client, "spread", item, 1);
        } else if(currentPage==3) {
            if(action==2)BotTracker.sendSelected("baritone:follow player "+client.player.getGameProfile().getName());
            else if(action==3)BotTracker.sendSelected("baritone:pause");
            else if(action==4)BotTracker.sendSelected("baritone:resume");
            else if(action==5)BotTracker.manageSelected("hold");
        } else {
            if(action==3){BotUiConfig.toggle();return true;}
            if(action==4){BotUiConfig.toggleEsp();return true;}
            if (action == 2) ClientActions.message(client, "Friends: " + String.join(", ", BotFriends.names()));
            else if (aimedFriend.isEmpty()) ClientActions.message(client, "Aim at a player before opening Friends.");
            else ClientActions.friend(client, action == 0 ? "add" : "remove", aimedFriend);
        }
        return true;
    }
    private void back() {
        BotUiSound.back();
        if (page == 0) close();
        else showPage(page == 4 ? 1 : page == 2 ? 3 : 0);
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (page == 1) { quantityChosen=true; quantity = Math.max(1, Math.min(2304, quantity + (amount > 0 ? 1 : -1) * (hasShiftDown() ? 16 : 1))); }
        if (page == 4) outputBatch = Math.max(1, Math.min(64,
                outputBatch + (amount > 0 ? 1 : -1) * (hasShiftDown() ? 8 : 1)));
        return true;
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (FormationClient.wheel.matchesKey(keyCode, scanCode)) { BotUiSound.back(); close(); return true; }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && page != 0) { back(); return true; }
        if (FormationClient.quick.matchesKey(keyCode, scanCode)) { close(); FormationClient.quick(client); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
