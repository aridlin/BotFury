package com.botfury;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;

final class SchematicSubstitutionScreen extends FleetPanel {
    private static final List<Block> MATERIALS = List.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.DIRT,
            Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.CALCITE, Blocks.COBBLED_DEEPSLATE);
    private final SchematicQueueScreen parent;
    private final List<Block> sources;
    private final Map<Block, BlockState> replacements;
    private Block source;
    private int offset;

    SchematicSubstitutionScreen(SchematicQueueScreen parent, List<Block> sources, Map<Block, BlockState> replacements) {
        super(Text.literal("Substitute blocks"));
        this.parent = parent;
        this.sources = sources;
        this.replacements = replacements;
        source = sources.get(0);
    }

    @Override public boolean shouldPause() { return false; }

    @Override protected void initPanel() {
        int left = 15, right = width / 2 + 8, column = width / 2 - 24;
        for (int i = 0; i < 9 && offset + i < sources.size(); i++) {
            var block = sources.get(offset + i);
            int row = i;
            var replacement = replacements.get(block);
            String name = block.getName().getString();
            if (replacement != null) name += " → " + replacement.getBlock().getName().getString();
            addDrawableChild(new BotUiButton(name, left, 51 + row * 23, column, 20, button -> {
                source = block;
                rebuild();
            })).highlight(block == source);
        }
        for (int i = 0; i < MATERIALS.size(); i++) {
            var material = MATERIALS.get(i);
            int x = right + i % 2 * (column / 2), y = 51 + i / 2 * 24;
            addDrawableChild(new BotUiButton(material.getName().getString(), x, y, column / 2 - 3, 20, button -> {
                if (source == material) replacements.remove(source);
                else replacements.put(source, material.getDefaultState());
                BotUiSound.confirm();
                rebuild();
            })).highlight(replacements.containsKey(source) && replacements.get(source).isOf(material));
        }
        addDrawableChild(new BotUiButton("Use original", right, 179, column - 3, 20, button -> {
            replacements.remove(source);
            rebuild();
        })).active = replacements.containsKey(source);
        addDrawableChild(new BotUiButton("Done", right + column - 84, height - 30, 81, 21, button -> close()));
    }

    private void rebuild() { clearChildren(); initPanel(); }

    @Override protected void renderPanel(DrawContext draw, int x, int y, float delta) {
        draw.fillGradient(0, 0, width, height, BotUiTheme.PANEL_TOP, BotUiTheme.PANEL_BOTTOM);
        draw.drawCenteredTextWithShadow(textRenderer, title, width / 2, 17, BotUiTheme.ACCENT);
        draw.drawText(textRenderer, "Original blocks", 15, 37, BotUiTheme.MUTED, false);
        draw.drawText(textRenderer, "Replace " + source.getName().getString() + " with", width / 2 + 8, 37, BotUiTheme.MUTED, false);
        super.renderPanel(draw, x, y, delta);
    }

    @Override protected boolean panelMouseScrolled(double x, double y, double amount) {
        if (x >= width / 2 || y < 51) return false;
        offset = Math.max(0, Math.min(Math.max(0, sources.size() - 9), offset + (amount < 0 ? 2 : -2)));
        rebuild();
        return true;
    }

    @Override public void close() { client.setScreen(parent); }
}
