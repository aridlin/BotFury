package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class SquadScreen extends FleetPanel {
    private static final int ROWS = 7;
    private final Screen parent;
    private final LinkedHashSet<String> members = new LinkedHashSet<>();
    private String editing;
    private String name = "";
    private String feedback = "";
    private int squadOffset;
    private int botOffset;

    SquadScreen(Screen parent) {
        super(Text.literal("Squads"));
        this.parent = parent;
        editing = BotTracker.selectedSquad();
        if (editing != null) {
            name = editing;
            members.addAll(BotUiConfig.squads.getOrDefault(editing, List.of()));
        }
    }

    @Override public boolean shouldPause() { return false; }

    @Override protected void initPanel() {
        var squads = new ArrayList<>(BotUiConfig.squads.keySet());
        var bots = new LinkedHashSet<String>();
        BotTracker.all().forEach(bot -> bots.add(bot.name()));
        bots.addAll(members);
        var roster = new ArrayList<>(bots);
        roster.sort(String.CASE_INSENSITIVE_ORDER);
        squadOffset = rows(squads, squadOffset, 16, 160, squad -> {
            editing = name = squad;
            members.clear();
            members.addAll(BotUiConfig.squads.get(squad));
            botOffset = 0;
            feedback = "";
            rebuild();
        }, true);
        botOffset = rows(roster, botOffset, 190, 260, bot -> {
            if (!members.remove(bot)) members.add(bot);
            rebuild();
        }, false);

        button("New squad", 16, 226, 160, () -> {
            editing = null;
            name = feedback = "";
            members.clear();
            botOffset = 0;
            rebuild();
        });
        var field = addDrawableChild(new TextFieldWidget(textRenderer, 190, 226, 260, 20, Text.literal("Squad name")));
        field.setMaxLength(24);
        field.setPlaceholder(Text.literal("Squad name"));
        field.setText(name);
        field.setChangedListener(value -> name = value);
        button("Back", 16, 265, 76, this::close);
        var remove = button("Delete squad", 99, 265, 77, this::remove);
        remove.active = editing != null;
        button("Save & use squad", 190, 265, 260, this::save).emphasize();
        BotTracker.refresh();
    }

    private int rows(List<String> values, int offset, int x, int w, java.util.function.Consumer<String> use,
                     boolean squadList) {
        int start = Math.min(offset, Math.max(0, values.size() - ROWS));
        for (int i = start; i < Math.min(values.size(), start + ROWS); i++) {
            var value = values.get(i);
            boolean marked = squadList ? value.equals(editing) : members.contains(value);
            var row = button((!squadList && marked ? "✓  " : "") + value, x, 45 + (i - start) * 22, w, () -> use.accept(value));
            if (marked) row.emphasize();
        }
        if (values.size() > ROWS) {
            button("↑", x, 202, 29, () -> shift(squadList, -ROWS)).active = start > 0;
            button("↓", x + w - 29, 202, 29, () -> shift(squadList, ROWS)).active = start + ROWS < values.size();
        }
        return start;
    }

    private void shift(boolean squads, int by) {
        if (squads) squadOffset = Math.max(0, squadOffset + by);
        else botOffset = Math.max(0, botOffset + by);
        rebuild();
    }

    private BotUiButton button(String text, int x, int y, int w, Runnable action) {
        return addDrawableChild(new BotUiButton(text, x, y, w, 20, ignored -> {
            BotUiSound.confirm();
            action.run();
        }));
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    private void save() {
        var label = name.strip();
        if (!BotUiConfig.validSquadName(label) || members.isEmpty()) {
            feedback = "Name the squad and select at least one bot.";
            return;
        }
        boolean duplicate = BotUiConfig.squads.keySet().stream()
                .anyMatch(key -> !key.equals(editing) && key.toLowerCase(Locale.ROOT).equals(label.toLowerCase(Locale.ROOT)));
        if (duplicate) {
            feedback = "Select the existing squad or choose a different name.";
            return;
        }
        if (editing != null && !editing.equals(label)) BotUiConfig.squads.remove(editing);
        BotUiConfig.squads.put(label, List.copyOf(members));
        BotUiConfig.save();
        BotTracker.selectSquad(label);
        close();
    }

    private void remove() {
        if (editing == null) return;
        BotUiConfig.squads.remove(editing);
        if (editing.equals(BotTracker.selectedSquad())) BotTracker.selectSquad(null);
        BotUiConfig.save();
        editing = null;
        name = feedback = "";
        members.clear();
        rebuild();
    }

    @Override protected void renderPanel(DrawContext draw, int mouseX, int mouseY, float delta) {
        draw.fillGradient(0, 0, width, height, BotUiTheme.PANEL_TOP, BotUiTheme.PANEL_BOTTOM);
        draw.drawText(textRenderer, "Squads", 16, 22, BotUiTheme.ACCENT, false);
        draw.drawText(textRenderer, "Members · " + members.size(), 190, 22, BotUiTheme.TEXT, false);
        if (!feedback.isBlank()) draw.drawText(textRenderer, feedback, 190, 251, BotUiTheme.DANGER, false);
        super.renderPanel(draw, mouseX, mouseY, delta);
    }

    @Override public void close() { client.setScreen(parent); }
}
