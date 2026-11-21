package com.botfury;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.*;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

final class CommandWheel extends Screen {
    private int page, quantity = 1, selected = -1;
    private boolean quantityChosen;
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
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void render(DrawContext draw, int mouseX, int mouseY, float delta) {
        int cx = width / 2, cy = height / 2;
        float radius = Math.min(108, height * 0.31f);
        selected = WheelMath.segment(mouseX - cx, mouseY - cy, radius * 0.28);
        if (Math.hypot(mouseX - cx, mouseY - cy) > radius * 1.65) selected = -1;
        draw.fill(0, 0, width, height, 0xA209101B);
        draw.drawCenteredTextWithShadow(textRenderer, "BOTFURY", cx, Math.max(8, (int)(cy - radius - 34)), 0xFF5DE4D2);
        String title = page == 0 ? "COMMAND WHEEL" : page == 1 ? "INVENTORY" : page == 2 ? "FRIENDS" : page == 3 ? "BARITONE" : "OUTPUT CHEST";
        draw.drawCenteredTextWithShadow(textRenderer, title, cx, (int)(cy - radius - 20), 0xFFADBCD0);
        for (int index = 0; index < 8; ++index) {
            boolean enabled = !LABELS[page][index].isEmpty();
            boolean hover = selected == index && enabled;
            wedge(draw, cx, cy, radius * 0.32f, radius, index,
                    hover ? 0xE92A685F : enabled ? 0xE51B2A3E : 0x70202B3B);
            if (hover) wedge(draw, cx, cy, radius - 2, radius, index, 0xFF5DE4D2);
            if (!enabled) continue;
            double angle = -Math.PI / 2 + index * Math.PI / 4;
            int ix = (int)(cx + Math.cos(angle) * radius * 0.68), iy = (int)(cy + Math.sin(angle) * radius * 0.68);
            draw.drawItem(ICONS[page][index].getDefaultStack(), ix - 8, iy - 12);
            draw.drawCenteredTextWithShadow(textRenderer, (page==1&&index==4&&!quantityChosen?"Drop all":LABELS[page][index]), ix, iy + 8, hover ? 0xFFFFFFFF : 0xFFBDCCDC);
        }
        draw.drawCenteredTextWithShadow(textRenderer, page == 1 ? (selected==4&&!quantityChosen?"All":"×"+quantity) : page == 4 ? "×" + outputBatch : "BF", cx, cy - 5, 0xFFEEF9FF);
        String hint;
        if (page == 1 || page == 4) hint = net.minecraft.registry.Registries.ITEM.get(new net.minecraft.util.Identifier(item))
                .getName().getString() + "  ·  scroll for quantity";
        else if (page == 2) hint = aimedFriend.isEmpty() ? "Aim at a player before opening" : "Aimed: " + aimedFriend;
        else hint = "Tasks: "+BotTracker.targetLabel()+" · /bf help";
        draw.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(hint, width - 24), cx, (int)(cy + radius + 17), 0xFF9BAEC4);
        if (page == 1 && selected == 3)
            draw.drawCenteredTextWithShadow(textRenderer, "Drops other main-inventory items", cx, (int)(cy + radius + 30), 0xFFFFC35C);
    }
    private static void wedge(DrawContext draw, float cx, float cy, float inner, float outer, int sector, int color) {
        draw.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        var matrix = draw.getMatrices().peek().getPositionMatrix();
        var buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        double start = -Math.PI / 2 + sector * Math.PI / 4 - Math.PI / 8 + 0.015;
        double end = start + Math.PI / 4 - 0.03;
        for (int i = 0; i <= 16; ++i) {
            double angle = start + (end - start) * i / 16;
            for (float radius : new float[]{outer, inner}) buffer.vertex(matrix,
                    cx + (float)Math.cos(angle) * radius, cy + (float)Math.sin(angle) * radius, 0)
                    .color((color >> 16) & 255, (color >> 8) & 255, color & 255, (color >>> 24) & 255).next();
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.disableBlend();
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { close(); return true; }
        if (button == 1) { if (page != 0) page = 0; else close(); return true; }
        if (button != 0) return false;
        if (selected < 0) { if (page != 0) page = 0; else close(); return true; }
        if (LABELS[page][selected].isEmpty()) return true;
        if (page == 0 && selected == 4) { page = 1; return true; }
        if (page == 0 && selected == 5) { client.setScreen(new BotRosterScreen(this)); return true; }
        if (page == 0 && selected == 6) { page = 3; return true; }
        if (page == 1 && selected == 5) { page = 4; return true; }
        if ((page == 1 || page == 4) && selected == 6) { client.setScreen(new ItemPickerScreen(this,false,id->{item=id;BotUiConfig.item=id;BotUiConfig.save();client.setScreen(this);}));return true; }
        if (page == 3 && selected == 0) {client.setScreen(new MiningScreen(this));return true;}
        if (page == 3 && selected == 1) {client.setScreen(new CoordinatesScreen(this));return true;}
        if (page == 3 && selected == 6) {page=2;return true;}
        if (page != 0 && selected == 7) { page = page == 4 ? 1 : 0; return true; }
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
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (page == 1) { quantityChosen=true; quantity = Math.max(1, Math.min(2304, quantity + (amount > 0 ? 1 : -1) * (hasShiftDown() ? 16 : 1))); }
        if (page == 4) outputBatch = Math.max(1, Math.min(64,
                outputBatch + (amount > 0 ? 1 : -1) * (hasShiftDown() ? 8 : 1)));
        return true;
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (FormationClient.wheel.matchesKey(keyCode, scanCode)) { close(); return true; }
        if (FormationClient.quick.matchesKey(keyCode, scanCode)) { close(); FormationClient.quick(client); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
