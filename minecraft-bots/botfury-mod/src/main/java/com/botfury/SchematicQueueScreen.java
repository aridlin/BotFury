package com.botfury;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

final class SchematicQueueScreen extends Screen {
    private final BotRosterScreen parent;
    private List<String> files = List.of();
    private TextFieldWidget search, x, y, z;
    private String selected, feedback = "";
    private boolean fromChest;
    private int offset, left, panelWidth;
    private BotUiButton queue;

    SchematicQueueScreen(BotRosterScreen parent) {
        super(Text.literal("New schematic build"));
        this.parent = parent;
    }

    @Override public boolean shouldPause() { return false; }

    @Override protected void init() {
        panelWidth = Math.min(width - 30, 390);
        left = (width - panelWidth) / 2;
        var directory = FabricLoader.getInstance().getConfigDir().resolve("botfury-structures");
        if (Files.isDirectory(directory)) {
            try (var entries = Files.list(directory)) {
                files = entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                        .filter(name -> name.matches("[A-Za-z0-9_-]{1,64}\\.(nbt|schem)"))
                        .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            } catch (IOException failure) {
                feedback = "Could not read the structures folder.";
            }
        }
        search = field("Search schematics", left, 40, panelWidth, "");
        search.setChangedListener(value -> offset = 0);
        var origin = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        int column = (panelWidth - 12) / 3;
        int yPos = height - 113;
        x = field("X", left, yPos, column, Integer.toString(origin.getX()));
        y = field("Y", left + column + 6, yPos, column, Integer.toString(origin.getY()));
        z = field("Z", left + 2 * (column + 6), yPos, column, Integer.toString(origin.getZ()));
        button(supplyLabel(), left, height - 85, panelWidth, b -> {
            fromChest = !fromChest;
            b.setMessage(Text.literal(supplyLabel()));
        });
        button("Back", left, height - 31, 78, b -> close());
        queue = button("Queue build", left + panelWidth - 105, height - 31, 105, b -> submit());
        setInitialFocus(search);
    }

    private TextFieldWidget field(String label, int x, int y, int w, String text) {
        var field = new TextFieldWidget(textRenderer, x, y, w, 20, Text.literal(label));
        field.setMaxLength(64);
        field.setPlaceholder(Text.literal(label));
        field.setText(text);
        return addDrawableChild(field);
    }

    private BotUiButton button(String name, int x, int y, int w, BotUiButton.PressAction callback) {
        return addDrawableChild(new BotUiButton(name, x, y, w, 21, b -> {
            BotUiSound.confirm();
            callback.onPress(b);
        }));
    }

    private String supplyLabel() { return fromChest ? "Materials · Gather and use the aimed chest" : "Materials · Use workers' inventories"; }

    private List<String> matches() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        return files.stream().filter(file -> file.toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private int visibleRows() { return Math.max(1, (height - 202) / 18); }

    @Override public void tick() {
        search.tick();
        x.tick();
        y.tick();
        z.tick();
    }

    @Override public void render(DrawContext draw, int mouseX, int mouseY, float delta) {
        draw.fillGradient(0, 0, width, height, BotUiTheme.PANEL_TOP, BotUiTheme.PANEL_BOTTOM);
        draw.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, BotUiTheme.ACCENT);
        var files = matches();
        offset = Math.min(offset, Math.max(0, files.size() - visibleRows()));
        for (int i = 0; i < visibleRows() && i + offset < files.size(); i++) {
            String file = files.get(i + offset);
            int y = 67 + i * 18;
            boolean hovered = mouseX >= left && mouseX < left + panelWidth && mouseY >= y && mouseY < y + 17;
            draw.fill(left, y, left + panelWidth, y + 17, file.equals(selected) ? BotUiTheme.SELECTED : hovered ? BotUiTheme.CARD_HOVER : BotUiTheme.CARD);
            draw.drawText(textRenderer, textRenderer.trimToWidth(file, panelWidth - 12), left + 6, y + 4, BotUiTheme.TEXT, false);
        }
        if (files.isEmpty()) draw.drawText(textRenderer, this.files.isEmpty()
                ? "Add a .schem or .nbt file to config/botfury-structures." : "No matching schematics", left, 70, BotUiTheme.MUTED, false);
        draw.drawText(textRenderer, "Build origin · X, Y, Z", left, height - 126, BotUiTheme.MUTED, false);
        if (!feedback.isBlank()) draw.drawText(textRenderer, textRenderer.trimToWidth(feedback, panelWidth),
                left, height - 51, BotUiTheme.DANGER, false);
        queue.active = selected != null;
        super.render(draw, mouseX, mouseY, delta);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { close(); return true; }
        if (button == 0 && x >= left && x < left + panelWidth && y >= 67 && y < 67 + visibleRows() * 18) {
            var files = matches();
            int row = offset + (int)(y - 67) / 18;
            if (row < files.size()) {
                selected = files.get(row);
                setFocused(null);
                BotUiSound.hover();
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (y >= height - 126) return super.mouseScrolled(x, y, amount);
        offset = Math.max(0, Math.min(Math.max(0, matches().size() - visibleRows()), offset + (amount > 0 ? -1 : 1)));
        return true;
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (search.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            var files = matches();
            if (!files.isEmpty()) selected = files.get(0);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private void submit() {
        if (selected == null) return;
        BlockPos origin;
        try {
            origin = new ProjectBlock.Position(Integer.parseInt(x.getText().trim()),
                    Integer.parseInt(y.getText().trim()), Integer.parseInt(z.getText().trim())).checked();
        } catch (IllegalArgumentException failure) {
            feedback = "Enter valid X, Y, and Z coordinates.";
            return;
        }
        String id = StructureProject.submitProject(client, selected, origin, fromChest);
        if (id != null) parent.showProject(id);
        else feedback = BotEvents.last;
    }

    @Override public void close() { client.setScreen(parent); }
}
