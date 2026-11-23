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
    private final float[] startEmphasis = new float[8];
    private final long[] emphasisChangedAt = new long[8];
    private long lastHover;
    private long lastFrame = Util.getMeasuringTimeMs();
    private double meshTime;
    private final WheelSoftBody[] bodies = new WheelSoftBody[8];
    private boolean gesture;
    private long pressedAt;
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
    private static final String[][] DESCRIPTIONS = {
            {"Orbit you in Saturn formation.", "Strike the target you aimed at.", "Spread the bots out around you.",
                    "Call the bots back to you.", "Bring, collect, keep, drop, or share items.", "View the fleet and select one bot.",
                    "Open mining and movement controls.", "Stop the bots' current movement and tasks."},
            {"Drop the chosen item toward you.", "Pick up the chosen item from the ground.", "Keep the chosen count and share any excess.",
                    "Keep only the chosen item and drop other items.", "Drop the chosen item. Scroll to set a count.",
                    "Set up automatic delivery to the chest you aimed at.", "Choose the item for this action.", "Return to the main wheel."},
            {"Add the player you aimed at to the friends list.", "Remove the player you aimed at from the friends list.",
                    "Show your friends list in chat.", "Show or hide the fleet HUD.", "Show or hide bot markers.", "", "", "Return to Baritone."},
            {"Choose blocks or ores for coordinated mining.", "Send the bots to specific coordinates.", "Have the bots follow you.",
                    "Pause Baritone and the current bot tasks.", "Let Baritone and the bot tasks resume.",
                    "Cancel the current path and hold position.", "Manage friends, the HUD, and bot markers.", "Return to the main wheel."},
            {"Have the bots gather and deposit batches in this chest.", "Cancel the order for the chest you aimed at.",
                    "Move the bots outward so they leave room around you.", "", "", "", "Choose which item the chest should receive.", "Return to Inventory."}};
    private static final Item[][] ICONS = {
            {Items.ENDER_PEARL, Items.TRIDENT, Items.FEATHER, Items.LEAD, Items.CHEST, Items.PLAYER_HEAD, Items.IRON_PICKAXE, Items.BARRIER},
            {Items.HOPPER, Items.CHEST_MINECART, Items.IRON_PICKAXE, Items.BUNDLE, Items.DROPPER, Items.CHEST, Items.BOOK, Items.ARROW},
            {Items.EMERALD, Items.REDSTONE, Items.BOOK, Items.COMPASS, Items.ENDER_EYE, Items.AIR, Items.AIR, Items.ARROW},
            {Items.DIAMOND_PICKAXE, Items.MAP, Items.LEAD, Items.CLOCK, Items.FEATHER, Items.BARRIER, Items.PLAYER_HEAD, Items.ARROW},
            {Items.CHEST, Items.BARRIER, Items.FEATHER, Items.AIR, Items.AIR, Items.AIR, Items.BOOK, Items.ARROW}};
    CommandWheel(boolean gesture) {
        super(Text.literal("BotFury command wheel"));
        var client = net.minecraft.client.MinecraftClient.getInstance();
        item = ClientActions.taskItem(client);
        aimedChest = client.crosshairTarget;
        var target = FormationClient.visibleTarget(client);
        aimedFriend = target instanceof PlayerEntity player ? player.getGameProfile().getName() : "";
        for (int i = 0; i < bodies.length; i++) bodies[i] = new WheelSoftBody();
        resetMesh();
        this.gesture = gesture;
        if (gesture) pressedAt = Util.getMeasuringTimeMs();
        BotUiSound.open();
    }
    @Override public boolean shouldPause() { return false; }
    private float radius() { return Math.min(108, Math.min(height * .31f, width * .37f)); }

    private int sectorAt(double x, double y) {
        double dx = x - width / 2, dy = y - height / 2;
        float radius = radius();
        int sector = WheelMath.segment(dx, dy, radius * .32);
        return sector < 0 || LABELS[page][sector].isEmpty() ? -1 : sector;
    }

    private void showPage(int next) {
        page = next;
        selected = -1;
        Arrays.fill(emphasis, 0);
        Arrays.fill(startEmphasis, 0);
        resetMesh();
    }

    private void resetMesh() {
        for (int i = 0; i < bodies.length; i++) bodies[i].reset(i % 4);
        meshTime = 0;
        lastFrame = Util.getMeasuringTimeMs();
    }

    @Override public void render(DrawContext draw, int mouseX, int mouseY, float delta) {
        int cx = width / 2, cy = height / 2;
        float baseRadius = radius();
        long now = Util.getMeasuringTimeMs();
        double elapsed = Math.max(0, now - lastFrame) / 1000.0;
        lastFrame = now;
        double cursorX = mouseX - cx, cursorY = mouseY - cy;
        float reach = (float)Math.min(1, Math.max(0, (Math.hypot(cursorX, cursorY) / baseRadius - .32) / .82));
        int hovered = sectorAt(mouseX, mouseY);
        if (hovered != selected) {
            retarget(selected, now);
            retarget(hovered, now);
            if (hovered >= 0 && now - lastHover >= 90) {
                BotUiSound.hover();
                lastHover = now;
            }
            selected = hovered;
        }
        float turn = 0;
        if (hovered >= 0) {
            double direction = -Math.PI / 2 + hovered * Math.PI / 4;
            double along = cursorX * Math.cos(direction) + cursorY * Math.sin(direction);
            double across = -cursorX * Math.sin(direction) + cursorY * Math.cos(direction);
            turn = (float)(Math.atan2(across, along) / (Math.PI / 8));
        }
        advanceMesh(elapsed, .23f + reach * .77f, turn);

        draw.fillGradient(0, 0, width, height, 0xC20A101E, 0xA8091020);
        String heading = switch (page) {
            case 1 -> "Inventory";
            case 2 -> "Friends";
            case 3 -> "Baritone";
            case 4 -> "Output chest";
            default -> "BotFury";
        };
        draw.drawCenteredTextWithShadow(textRenderer, heading, cx, Math.max(6, (int)(cy - baseRadius - 31)), 0xFFF1F6FF);

        for (int index = 0; index < 8; ++index) {
            boolean enabled = !LABELS[page][index].isEmpty();
            double hoverTime = Math.max(0, now - emphasisChangedAt[index]);
            float target = selected == index ? 1 : 0;
            float hover = target + (startEmphasis[index] - target) * spring(hoverTime, 112, 38);
            emphasis[index] = hover;
            float highlight = Math.max(0, Math.min(1, hover));
            double direction = -Math.PI / 2 + index * Math.PI / 4;
            var mesh = bodies[index];
            int body = enabled ? mix(0xE51B2A3E, 0xE92A685F, highlight) : 0x70202B3B;
            wedge(draw, cx, cy, baseRadius, index, body, mesh, false);
            if (highlight > .03f) {
                int stroke = (Math.round(highlight * 255) << 24) | 0x5DE4D2;
                wedge(draw, cx, cy, baseRadius, index, stroke, mesh, true);
            }
            if (!enabled) continue;
            int middle = WheelSoftBody.POINTS / 2;
            double angle = direction + .5f * (mesh.angle(WheelSoftBody.INNER, middle) + mesh.angle(WheelSoftBody.OUTER, middle));
            float labelRadius = baseRadius * (.5f * (mesh.radius(WheelSoftBody.INNER, middle)
                    + mesh.radius(WheelSoftBody.OUTER, middle)) + .02f);
            int ix = cx + (int)Math.round(Math.cos(angle) * labelRadius);
            int iy = cy + (int)Math.round(Math.sin(angle) * labelRadius);
            draw.drawItem(ICONS[page][index].getDefaultStack(), ix - 8, iy - 12);
            String label = page == 1 && index == 4 && !quantityChosen ? "Drop all" : LABELS[page][index];
            draw.drawCenteredTextWithShadow(textRenderer, label, ix, iy + 8, mix(0xFFBDCCDC, 0xFFFFFFFF, highlight));
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
        String description = selected < 0
                ? gesture ? "Release over a wedge to select. Release in the center to cancel." : "Point at a wedge and click to select."
                : DESCRIPTIONS[page][selected];
        int textWidth = Math.max(1, width - 24);
        var lines = textRenderer.wrapLines(Text.literal(description), textWidth);
        hint = textRenderer.trimToWidth(hint, textWidth);
        int panelWidth = textRenderer.getWidth(hint);
        for (var line : lines) panelWidth = Math.max(panelWidth, textRenderer.getWidth(line));
        int y = height - 17 - lines.size() * 10;
        draw.fill(cx - panelWidth / 2 - 6, y - 4, cx + (panelWidth + 1) / 2 + 6, height - 3, 0xF20B1322);
        for (var line : lines) {
            draw.drawCenteredTextWithShadow(textRenderer, line, cx, y, 0xFFF1F6FF);
            y += 10;
        }
        draw.drawCenteredTextWithShadow(textRenderer, hint, cx, y + 2, 0xFFB9C8DC);
    }

    private void advanceMesh(double elapsed, float pull, float turn) {
        meshTime += Math.min(elapsed, .05);
        while (meshTime >= WheelSoftBody.STEP) {
            for (int i = 0; i < bodies.length; i++) {
                bodies[i].advance(WheelSoftBody.STEP, i == selected ? pull : 0, i == selected ? turn : 0);
            }
            for (int pass = 0; pass < 3; pass++) {
                for (int i = 0; i < bodies.length; i++) {
                    WheelSoftBody.collide(bodies[i], bodies[(i + 1) % bodies.length]);
                }
            }
            meshTime -= WheelSoftBody.STEP;
        }
    }

    private void retarget(int index, long now) {
        if (index < 0) return;
        startEmphasis[index] = emphasis[index];
        emphasisChangedAt[index] = now;
    }

    private static float spring(double elapsed, double damping, double period) {
        double phase = elapsed / period;
        return (float)(Math.exp(-elapsed / damping)
                * (Math.cos(phase) + period / damping * Math.sin(phase)));
    }

    private static int mix(int from, int to, float amount) {
        int color = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int start = (from >>> shift) & 255, end = (to >>> shift) & 255;
            color |= Math.round(start + (end - start) * amount) << shift;
        }
        return color;
    }

    private static void wedge(DrawContext draw, float cx, float cy, float baseRadius,
                              int sector, int color, WheelSoftBody mesh, boolean rim) {
        draw.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        var matrix = draw.getMatrices().peek().getPositionMatrix();
        var buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        double start = -Math.PI / 2 + sector * Math.PI / 4 - Math.PI / 8 + .015;
        for (int point = 0; point < WheelSoftBody.POINTS; point++) {
            double angle = start + (Math.PI / 4 - .03) * point / (WheelSoftBody.POINTS - 1);
            for (int ring = WheelSoftBody.OUTER; ring >= WheelSoftBody.INNER; ring--) {
                int shape = rim ? WheelSoftBody.OUTER : ring;
                double bent = angle + mesh.angle(shape, point);
                float radius = baseRadius * mesh.radius(shape, point) - (rim && ring == WheelSoftBody.INNER ? 2 : 0);
                buffer.vertex(matrix, cx + (float)Math.cos(bent) * radius,
                        cy + (float)Math.sin(bent) * radius, 0).color(color).next();
            }
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.disableBlend();
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { beginGesture(); return true; }
        if (button == 1) { gesture = false; back(); return true; }
        if (button != 0) return false;
        gesture = false;
        int action = sectorAt(x, y);
        if (action < 0) {
            if (Math.hypot(x - width / 2, y - height / 2) < radius() * .32) back();
        } else choose(action);
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (!FormationClient.wheel.matchesMouse(button)) return super.mouseReleased(x, y, button);
        finishGesture(x, y);
        return true;
    }

    private void beginGesture() {
        if (gesture) return;
        gesture = true;
        pressedAt = Util.getMeasuringTimeMs();
    }

    private void finishGesture(double x, double y) {
        if (!gesture) return;
        gesture = false;
        int action = sectorAt(x, y);
        if (action >= 0) choose(action);
        else if (Util.getMeasuringTimeMs() - pressedAt >= 200) {
            BotUiSound.back();
            close();
        }
    }

    private void choose(int action) {
        BotUiSound.confirm();
        if (page == 0 && action == 4) { showPage(1); return; }
        if (page == 0 && action == 5) { client.setScreen(new BotRosterScreen(this)); return; }
        if (page == 0 && action == 6) { showPage(3); return; }
        if (page == 1 && action == 5) { showPage(4); return; }
        if ((page == 1 || page == 4) && action == 6) { client.setScreen(new ItemPickerScreen(this,false,id->{item=id;BotUiConfig.item=id;BotUiConfig.save();client.setScreen(this);}));return; }
        if (page == 3 && action == 0) {client.setScreen(new MiningScreen(this));return;}
        if (page == 3 && action == 1) {client.setScreen(new CoordinatesScreen(this));return;}
        if (page == 3 && action == 6) {showPage(2);return;}
        if (page != 0 && action == 7) { showPage(page == 4 ? 1 : page == 2 ? 3 : 0); return; }
        int currentPage = page;
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
            if(action==3){BotUiConfig.toggle();return;}
            if(action==4){BotUiConfig.toggleEsp();return;}
            if (action == 2) ClientActions.message(client, "Friends: " + String.join(", ", BotFriends.names()));
            else if (aimedFriend.isEmpty()) ClientActions.message(client, "Aim at a player before opening Friends.");
            else ClientActions.friend(client, action == 0 ? "add" : "remove", aimedFriend);
        }
    }
    private void back() {
        gesture = false;
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
        if (FormationClient.wheel.matchesKey(keyCode, scanCode)) { beginGesture(); return true; }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && page != 0) { back(); return true; }
        if (FormationClient.quick.matchesKey(keyCode, scanCode)) { close(); FormationClient.quick(client); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (!FormationClient.wheel.matchesKey(keyCode, scanCode)) return super.keyReleased(keyCode, scanCode, modifiers);
        var window = client.getWindow();
        finishGesture(client.mouse.getX() * width / window.getWidth(),
                client.mouse.getY() * height / window.getHeight());
        return true;
    }
}
