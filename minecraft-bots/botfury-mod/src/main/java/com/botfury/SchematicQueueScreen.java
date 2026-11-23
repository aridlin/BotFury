package com.botfury;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import java.util.HashMap;

final class SchematicQueueScreen extends FleetPanel {
    private final BotRosterScreen parent;
    private List<String> files = List.of();
    private TextFieldWidget search, x, y, z;
    private String selected, feedback = "";
    private boolean fromChest, previewEnabled = true;
    private Map<BlockPos, BlockState> blueprint = Map.of();
    private BotUiButton preview, substitute;
    private final Map<Block, BlockState> replacements = new HashMap<>();
    private int offset, left, panelWidth;
    private BotUiButton queue;

    SchematicQueueScreen(BotRosterScreen parent) {
        super(Text.literal("New schematic build"));
        this.parent = parent;
    }

    @Override public boolean shouldPause() { return false; }

    @Override protected void initPanel() {
        panelWidth = Math.min(width - 30, 390);
        left = (width - panelWidth) / 2;
        var directory = FabricLoader.getInstance().getConfigDir().resolve("botfury-structures");
        if (Files.isDirectory(directory)) {
            try (var entries = Files.list(directory)) {
                files = entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                        .filter(StructureProject::supportedFilename)
                        .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            } catch (IOException failure) {
                feedback = "Could not read the structures folder.";
            }
        }
        search = field("Search schematics", left, 40, panelWidth, search == null ? "" : search.getText());
        search.setChangedListener(value -> offset = 0);
        var origin = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        int column = (panelWidth - 12) / 3;
        int yPos = height - 113;
        x = field("X", left, yPos, column, x == null ? Integer.toString(origin.getX()) : x.getText());
        y = field("Y", left + column + 6, yPos, column, y == null ? Integer.toString(origin.getY()) : y.getText());
        z = field("Z", left + 2 * (column + 6), yPos, column, z == null ? Integer.toString(origin.getZ()) : z.getText());
        for (var coordinate : List.of(x, y, z)) coordinate.setChangedListener(ignored -> updatePreview());
        button(supplyLabel(), left, height - 85, panelWidth, b -> {
            fromChest = !fromChest;
            b.setMessage(Text.literal(supplyLabel()));
        });
        substitute = button(substitutionLabel(), left, height - 59, panelWidth, b -> {
            var sources = blueprint.entrySet().stream().filter(entry -> StructureProject.canSubstitute(entry.getValue(), client.world, entry.getKey()))
                    .map(entry -> entry.getValue().getBlock()).distinct()
                    .sorted(java.util.Comparator.comparing(block -> block.getName().getString())).toList();
            if (!sources.isEmpty()) client.setScreen(new SchematicSubstitutionScreen(this, sources, replacements));
        });
        button("Back", left, height - 31, 78, b -> close());
        preview = button(previewLabel(), left + 88, height - 31, 114, b -> {
            previewEnabled = !previewEnabled;
            if (previewEnabled) updatePreview();
            else WorldOverlays.clear();
            b.setMessage(Text.literal(previewLabel()));
        });
        queue = button("Queue build", left + panelWidth - 105, height - 31, 105, b -> submit());
        setInitialFocus(search);
        updatePreview();
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

    @Override protected void renderPanel(DrawContext draw, int mouseX, int mouseY, float delta) {
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
                ? "Add a .schem, .nbt, or .schematic file to config/botfury-structures." : "No matching schematics", left, 70, BotUiTheme.MUTED, false);
        draw.drawText(textRenderer, "Build origin · X, Y, Z", left, height - 126, BotUiTheme.MUTED, false);
        if (!feedback.isBlank()) draw.drawText(textRenderer, textRenderer.trimToWidth(feedback, panelWidth),
                left, height - 69, BotUiTheme.DANGER, false);
        queue.active = selected != null;
        preview.active = selected != null && !blueprint.isEmpty();
        substitute.active = preview.active;
        super.renderPanel(draw, mouseX, mouseY, delta);
    }

    @Override protected boolean panelMouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { close(); return true; }
        if (button == 0 && x >= left && x < left + panelWidth && y >= 67 && y < 67 + visibleRows() * 18) {
            var files = matches();
            int row = offset + (int)(y - 67) / 18;
            if (row < files.size()) {
                choose(files.get(row));
                setFocused(null);
                BotUiSound.hover();
                return true;
            }
        }
        return super.panelMouseClicked(x, y, button);
    }

    @Override protected boolean panelMouseScrolled(double x, double y, double amount) {
        if (y >= height - 126) return super.panelMouseScrolled(x, y, amount);
        offset = Math.max(0, Math.min(Math.max(0, matches().size() - visibleRows()), offset + (amount > 0 ? -1 : 1)));
        return true;
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (search.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            var files = matches();
            if (!files.isEmpty()) choose(files.get(0));
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private String substitutionLabel() {
        return replacements.isEmpty() ? "Substitute blocks…" : "Substitute blocks…  " + replacements.size() + " selected";
    }

    private String previewLabel() { return previewEnabled ? "Hide ghost" : "Show ghost"; }

    private void choose(String file) {
        if (!file.equals(selected)) replacements.clear();
        selected = file;
        substitute.setMessage(Text.literal(substitutionLabel()));
        feedback = "";
        try {
            blueprint = StructureProject.readBlueprint(file, BlockPos.ORIGIN);
        } catch (IOException | IllegalArgumentException failure) {
            feedback = failure.getMessage() == null ? "Could not read the schematic." : failure.getMessage();
            blueprint = Map.of();
        }
        updatePreview();
    }

    private BlockPos origin() {
        return new ProjectBlock.Position(Integer.parseInt(x.getText().trim()),
                Integer.parseInt(y.getText().trim()), Integer.parseInt(z.getText().trim())).checked();
    }

    private void updatePreview() {
        if (!previewEnabled) return;
        try {
            if (blueprint.isEmpty() || client.world == null) WorldOverlays.clear();
            else WorldOverlays.preview(client.world, blueprint, StructureProject.substitute(blueprint, replacements), origin());
        } catch (IllegalArgumentException invalidCoordinates) {
            WorldOverlays.clear();
        }
    }

    private void submit() {
        if (selected == null) return;
        BlockPos origin;
        try {
            origin = origin();
        } catch (IllegalArgumentException failure) {
            feedback = "Enter valid X, Y, and Z coordinates.";
            return;
        }
        String id = StructureProject.submitProject(client, selected, origin, fromChest, replacements);
        if (id != null) parent.showProject(id);
        else feedback = BotEvents.last;
    }

    @Override public void close() { client.setScreen(parent); }
}
