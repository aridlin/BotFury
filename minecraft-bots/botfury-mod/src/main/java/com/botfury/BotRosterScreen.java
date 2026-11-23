package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class BotRosterScreen extends Screen {
    private enum Tab { FLEET, PROJECTS, CHESTS, ACTIVITY }
    private enum GuideStep {
        PICK(Tab.FLEET, "Choose who gets orders",
                "Select All bots or a bot by name. Use the search box to narrow the roster. Outside the guide, the selection also applies to the pie menu."),
        HOLD(null, "Pause work",
                "Try Practice Hold / Stop. The real button stops the selected bots and cancels their current Baritone movement. Recall instead sends them back to you."),
        RESUME(null, "Hand control back",
                "Try Practice Resume. The real button clears a manual hold so those bots can take work again. If a bot is offline, check its status and use Join my world."),
        BUILDS(Tab.PROJECTS, "Queue a build and follow its progress",
                "Open Projects. New build opens the schematic picker. Assign workers enrolls the selected bots. Select a project to see its completed jobs, current workers, and any failures."),
        STOCK(Tab.CHESTS, "Check shared supplies",
                "Open Chests. Select a chest by its coordinates to see the last counted items and when a worker checked them. Check the age before relying on the count."),
        LOG(Tab.ACTIVITY, "Find out why work stopped",
                "Open Activity. Use its filter to find an error and select the row to read it in full. The command field can send Baritone commands like #stop to your selected bots."),
        DONE(null, "Ready to manage the fleet",
                "Use Bots on the pie menu to return here. Run /bf tutorial to practice again.");

        final Tab target;
        final String heading, detail;
        GuideStep(Tab target, String heading, String detail) {
            this.target = target;
            this.heading = heading;
            this.detail = detail;
        }
    }
    private record Row(String key, String title, String subtitle, int color) {}
    private static final int ACCENT = BotUiTheme.ACCENT, TEXT = BotUiTheme.TEXT, MUTED = BotUiTheme.MUTED, RED = BotUiTheme.DANGER;
    private static final int MARGIN = 14, ROW_TOP = 69, ROW_HEIGHT = 30;
    private final Screen parent;
    private final Map<Tab, String> selection = new EnumMap<>(Tab.class);
    private Tab tab = Tab.FLEET;
    private ProjectBroker.Overview overview = new ProjectBroker.Overview(List.of(), Map.of());
    private CompletableFuture<ProjectBroker.Overview> loading;
    private String world, error = "", filter = "";
    private TextFieldWidget search;
    private TextFieldWidget command;
    private int offset, detailOffset, detailLimit;
    private GuideStep guide;

    BotRosterScreen(Screen parent) {
        this(parent, false);
    }

    BotRosterScreen(Screen parent, boolean replayGuide) {
        super(Text.literal("BotFury"));
        this.parent = parent;
        if (replayGuide || !BotUiConfig.fleetGuideSeen) guide = GuideStep.PICK;
        selection.put(Tab.FLEET, Integer.toString(BotTracker.selected()));
    }

    @Override public boolean shouldPause() { return false; }

    @Override protected void init() {
        if (guide != null) BotUiConfig.markFleetGuideSeen();
        int cell = (width - 2 * MARGIN - 12) / 4;
        var tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            var value = tabs[i];
            String label = switch (value) {
                case FLEET -> "Fleet";
                case PROJECTS -> "Projects";
                case CHESTS -> "Chests";
                case ACTIVITY -> "Activity";
            };
            button(label, MARGIN + i * (cell + 4), 14, cell, () -> changeTab(value)).highlight(tab == value);
        }

        search = new TextFieldWidget(textRenderer, MARGIN, 42, listWidth(), 20, Text.literal("Filter"));
        search.setPlaceholder(Text.literal(switch (tab) {
            case FLEET -> "Search bot names";
            case PROJECTS -> "Find a project";
            case CHESTS -> "Find chest coordinates";
            case ACTIVITY -> "Filter messages";
        }));
        search.setText(filter);
        search.setChangedListener(value -> { filter = value; offset = 0; });
        addDrawableChild(search);

        int y = height - 30;
        button("Back", width - MARGIN - 82, y, 82, this::close);
        command = null;
        if (guide != null) {
            button(guide == GuideStep.DONE ? "Done" : "Skip guide", MARGIN, y, 93, this::endGuide);
            if (tab == Tab.FLEET && (guide == GuideStep.HOLD || guide == GuideStep.RESUME)) {
                String action = guide == GuideStep.HOLD ? "Practice Hold / Stop" : "Practice Resume";
                guideButton(action, y);
            } else if (guide != GuideStep.PICK && guide.target == tab) {
                guideButton("Continue", y);
            }
            BotTracker.refresh();
            return;
        }
        switch (tab) {
            case FLEET -> {
                controls(0, "Recall", () -> BotTracker.manageSelected("recall"),
                        "Hold / Stop", () -> BotTracker.manageSelected("hold"));
                controls(1, "Resume", () -> BotTracker.manageSelected("resume"), "Join my world", this::joinBots);
                controls(2, "Track items…", () -> client.setScreen(new ItemTrackingScreen(this)),
                        "Toggle rendering", () -> BotTracker.targets().forEach(bot -> BotTracker.command(bot.port(), "toggle_render")));
                button("Defense " + (BotUiConfig.defense ? "on" : "off"), MARGIN, y, 93, () -> {
                    BotUiConfig.defense = !BotUiConfig.defense;
                    BotUiConfig.save();
                    BotTracker.sendDefenses();
                    rebuild();
                });
                button("Markers " + (BotUiConfig.esp ? "on" : "off"), MARGIN + 98, y, 82, () -> {
                    BotUiConfig.toggleEsp();
                    rebuild();
                });
            }
            case PROJECTS -> controls(2, "New build…", () -> client.setScreen(new SchematicQueueScreen(this)),
                    "Assign workers", this::assignWorkers);
            case ACTIVITY -> {
                command = addDrawableChild(new TextFieldWidget(textRenderer, MARGIN, y, width - 2 * MARGIN - 144,
                        21, Text.literal("Baritone command")));
                command.setPlaceholder(Text.literal("Baritone command…"));
                command.setMaxLength(160);
                button("Send", width - MARGIN - 138, y, 51, this::sendCommand);
            }
            case CHESTS -> {}
        }
        BotTracker.refresh();
    }

    private BotUiButton button(String label, int x, int y, int w, Runnable action) {
        return addDrawableChild(new BotUiButton(label, x, y, w, 21, ignored -> {
            BotUiSound.confirm();
            action.run();
        }));
    }

    private void controls(int row, String first, Runnable firstAction, String second, Runnable secondAction) {
        int w = (width - detailLeft() - MARGIN - 5) / 2;
        int y = height - 113 + row * 25;
        button(first, detailLeft(), y, w, firstAction);
        button(second, detailLeft() + w + 5, y, w, secondAction);
    }

    private void guideButton(String label, int y) {
        int x = MARGIN + 98;
        button(label, x, y, width - x - MARGIN - 87, this::advanceGuide).highlight(true);
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    private void changeTab(Tab next) {
        if (guide != null && guide != GuideStep.DONE && guide.target != next) return;
        tab = next;
        filter = "";
        offset = detailOffset = 0;
        rebuild();
    }

    private void advanceGuide() {
        guide = GuideStep.values()[guide.ordinal() + 1];
        rebuild();
    }

    private void endGuide() {
        guide = null;
        selection.put(Tab.FLEET, Integer.toString(BotTracker.selected()));
        rebuild();
    }

    private void sendCommand() {
        String value = command.getText().strip();
        if (value.startsWith("#")) value = value.substring(1).strip();
        if (value.isEmpty()) return;
        int sent = BotTracker.sendSelected("baritone:" + value);
        if (sent > 0) {
            BotEvents.add("info", "command", BotTracker.targetLabel() + " · " + value);
            command.setText("");
        }
    }

    private void joinBots() {
        var here = LocalFormation.here();
        if (here == null || client.getCurrentServerEntry() == null) {
            BotEvents.add("warn", "fleet", "Join a multiplayer server before connecting bots");
            return;
        }
        var bots = BotTracker.targets().stream().filter(bot -> bot.fresh() && !bot.connected()).toList();
        if (bots.isEmpty()) BotEvents.add("info", "fleet", "No selected bots are waiting at the title screen");
        for (var bot : bots) BotTracker.command(bot.port(), "connect:" + client.getCurrentServerEntry().address);
    }

    private void assignWorkers() {
        int port = BotFuryMod.controlPort();
        if (LocalFormation.here() == null || port < 1024) return;
        int count = BotTracker.sendSelected("project:worker " + port);
        if (count > 0) BotEvents.add("info", "projects", "Requested " + count + " project workers");
    }

    void showProject(String id) {
        selection.put(Tab.PROJECTS, id);
        offset = detailOffset = 0;
        nextRead = 0;
        client.setScreen(this);
    }

    @Override public void tick() {
        search.tick();
        if (command != null) command.tick();
        if (tab != Tab.PROJECTS && tab != Tab.CHESTS) return;
        var here = LocalFormation.here();
        if (here == null) {
            error = "Join the project world to view it.";
            overview = new ProjectBroker.Overview(List.of(), Map.of());
            world = null;
            loading = null;
            return;
        }
        String nowWorld = here.server() + "|" + here.dimension();
        if (!nowWorld.equals(world)) {
            world = nowWorld;
            overview = new ProjectBroker.Overview(List.of(), Map.of());
            loading = null;
            nextRead = 0;
        }
        if (loading != null && loading.isDone()) {
            try {
                overview = loading.join();
                error = "";
            } catch (CompletionException failure) {
                error = "Could not read the project journal.";
            }
            loading = null;
        }
        long now = System.currentTimeMillis();
        if (loading != null || now < nextRead) return;
        nextRead = now + 1250;
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                return ProjectBroker.overview(nowWorld);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }
    private long nextRead;

    private List<Row> rows() {
        var rows = new ArrayList<Row>();
        switch (tab) {
            case FLEET -> {
                var bots = BotTracker.all();
                long connected = bots.stream().filter(bot -> bot.fresh() && bot.connected()).count();
                rows.add(new Row("-1", "All bots", connected + " in game / " + bots.size() + " tracked", ACCENT));
                for (var bot : bots) rows.add(new Row(Integer.toString(bot.port()), bot.name(), bot.summary(),
                        bot.fresh() ? bot.alive() ? ACCENT : RED : MUTED));
            }
            case PROJECTS -> {
                var projects = new ArrayList<>(overview.projects());
                Collections.reverse(projects);
                for (var project : projects) {
                    long done = completed(project);
                    String progress = done + " / " + project.jobs().size() + " jobs complete";
                    if (project.repeat() != null) progress = project.repeat().completed() + " batches complete";
                    boolean failed = project.jobs().stream().anyMatch(job -> job.state() == ProjectQueue.State.FAILED);
                    rows.add(new Row(project.id(), project.id(), failed ? "Needs attention · " + progress : progress,
                            failed ? RED : ACCENT));
                }
            }
            case CHESTS -> overview.stock().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    rows.add(new Row(entry.getKey(), location(entry.getKey()), entry.getValue().report().items().size() + " item types", ACCENT)));
            case ACTIVITY -> BotEvents.recent().forEach(event -> rows.add(new Row(Long.toString(event.id()),
                    event.source() + " · " + event.time().substring(11, 19) + " UTC", event.message(),
                    event.level().equals("info") ? ACCENT : RED)));
        }
        String query = filter.strip().toLowerCase(Locale.ROOT);
        return rows.stream().filter(row -> (row.title + " " + row.subtitle).toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private void select(Row row) {
        selection.put(tab, row.key);
        detailOffset = 0;
        if (tab == Tab.FLEET) {
            if (guide == null) BotTracker.select(Integer.parseInt(row.key));
            if (guide == GuideStep.PICK) advanceGuide();
        }
        BotUiSound.hover();
    }

    private int listWidth() { return Math.max(80, (width - 2 * MARGIN - 12) * 45 / 100); }
    private int detailLeft() { return MARGIN + listWidth() + 12; }
    private int visibleRows() { return Math.max(1, (height - ROW_TOP - 36) / ROW_HEIGHT); }

    @Override public void render(DrawContext draw, int mouseX, int mouseY, float delta) {
        draw.fillGradient(0, 0, width, height, BotUiTheme.PANEL_TOP, BotUiTheme.PANEL_BOTTOM);
        var rows = rows();
        offset = Math.min(offset, Math.max(0, rows.size() - visibleRows()));
        String active = selection.get(tab);
        var selected = rows.stream().filter(row -> row.key.equals(active)).findFirst().orElse(null);
        for (int i = 0; i < visibleRows() && i + offset < rows.size(); i++) {
            var row = rows.get(offset + i);
            int y = ROW_TOP + i * ROW_HEIGHT;
            boolean pointed = mouseX >= MARGIN && mouseX < MARGIN + listWidth() && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            int fill = row.equals(selected) ? BotUiTheme.SELECTED : pointed ? BotUiTheme.CARD_HOVER : BotUiTheme.CARD;
            draw.fill(MARGIN, y, MARGIN + listWidth(), y + ROW_HEIGHT - 2, fill);
            line(draw, row.title, MARGIN + 7, y + 5, listWidth() - 14, row.color);
            line(draw, row.subtitle, MARGIN + 7, y + 17, listWidth() - 14, MUTED);
        }
        if (rows.isEmpty()) line(draw, error.isEmpty() ? emptyLabel() : error, MARGIN + 3, ROW_TOP + 5, listWidth() - 6,
                error.isEmpty() ? MUTED : RED);
        if (guide == null) drawDetails(draw, selected, 49);
        else {
            int nextY = drawGuide(draw) + 10;
            if (tab != Tab.FLEET && selected != null && nextY + 35 < height - 34) {
                drawDetails(draw, selected, nextY);
            }
        }
        if (guide == null && (tab == Tab.FLEET || tab == Tab.PROJECTS)) {
            line(draw, BotEvents.last, MARGIN + 2, height - (tab == Tab.FLEET ? 42 : 23),
                    tab == Tab.FLEET ? listWidth() : width - 2 * MARGIN - 90, BotEvents.lastLevel.equals("info") ? MUTED : RED);
        }
        super.render(draw, mouseX, mouseY, delta);
    }

    private int drawGuide(DrawContext draw) {
        int cell = (width - 2 * MARGIN - 12) / 4;
        if (guide == GuideStep.PICK && tab == Tab.FLEET && filter.isBlank()) {
            draw.drawBorder(MARGIN - 2, ROW_TOP - 2, listWidth() + 4, ROW_HEIGHT + 2, ACCENT);
        } else if (guide.target != null && tab != guide.target) {
            int x = MARGIN + guide.target.ordinal() * (cell + 4);
            draw.drawBorder(x - 2, 12, cell + 4, 25, ACCENT);
        }
        int x = detailLeft(), right = width - MARGIN;
        int y = 49;
        int textWidth = Math.max(1, right - x - 16);
        var heading = textRenderer.wrapLines(Text.literal(guide.heading), textWidth);
        var detail = textRenderer.wrapLines(Text.literal(guide.detail), textWidth);
        int bottom = Math.min(height - 35, y + 46 + 11 * (heading.size() + detail.size()));
        draw.fill(x, y, right, bottom, BotUiTheme.CARD);
        line(draw, guide == GuideStep.DONE ? "Fleet guide complete"
                        : "Fleet guide · " + (guide.ordinal() + 1) + " / " + GuideStep.DONE.ordinal(),
                x + 8, y + 8, right - x - 16, ACCENT);
        draw.enableScissor(x, y + 22, right, bottom);
        int textY = y + 26;
        if (guide == GuideStep.HOLD || guide == GuideStep.RESUME) {
            String target = selection.get(Tab.FLEET);
            var bot = BotTracker.get(Integer.parseInt(target));
            line(draw, "Practice target · " + (bot == null ? "All bots" : bot.name()),
                    x + 8, textY, textWidth, ACCENT);
            textY += 14;
        }
        for (var row : heading) {
            draw.drawText(textRenderer, row, x + 8, textY, TEXT, false);
            textY += 11;
        }
        textY += 4;
        for (var row : detail) {
            draw.drawText(textRenderer, row, x + 8, textY, MUTED, false);
            textY += 11;
        }
        draw.disableScissor();
        return bottom;
    }

    private String emptyLabel() {
        if (!filter.isBlank()) return "No matches";
        return switch (tab) {
            case FLEET -> "Waiting for bots";
            case PROJECTS -> "No projects in this world";
            case CHESTS -> "No chest stock recorded yet";
            case ACTIVITY -> "No recent activity";
        };
    }

    private void drawDetails(DrawContext draw, Row selected, int y) {
        int x = detailLeft();
        int detailBottom = height - (guide != null ? 34 : tab == Tab.FLEET ? 120 : tab == Tab.PROJECTS ? 70 : 34);
        int w = width - x - MARGIN;
        var lines = new ArrayList<String>();
        String heading = selected == null ? switch (tab) {
            case FLEET -> BotTracker.targetLabel();
            case PROJECTS -> "Select a project";
            case CHESTS -> "Select a chest";
            case ACTIVITY -> "Select a message";
        } : selected.title;
        if (tab == Tab.FLEET) {
            var bot = BotTracker.get(BotTracker.selected());
            if (bot != null) {
                lines.add(bot.summary());
                if (bot.connected()) {
                    lines.add(Math.round(bot.health()) + " / " + Math.round(bot.maxHealth()) + " HP");
                    lines.add(Math.round(bot.x()) + ", " + Math.round(bot.y()) + ", " + Math.round(bot.z()) + " · " + bot.dimension().replace("minecraft:", ""));
                }
                lines.add("Control · " + LocalFormation.overrideLabel(bot.port()));
                if (bot.items() != null && !bot.items().isEmpty()) {
                    lines.add("Inventory");
                    appendItems(lines, bot.items());
                }
            } else lines.add("Actions apply to the fleet. Select a bot to control it individually.");
        } else if (selected != null) {
            switch (tab) {
                case PROJECTS -> projectDetails(lines, selected.key);
                case CHESTS -> chestDetails(lines, selected.key);
                case ACTIVITY -> lines.add(selected.subtitle);
                default -> {}
            }
        }
        line(draw, heading, x, y, w, ACCENT);
        y += 17;
        var wrapped = new ArrayList<net.minecraft.text.OrderedText>();
        for (String detail : lines) {
            wrapped.addAll(textRenderer.wrapLines(Text.literal(detail), Math.max(1, w)));
        }
        int shown = Math.max(1, (detailBottom - y) / 14);
        detailLimit = Math.max(0, wrapped.size() - shown);
        detailOffset = Math.min(detailOffset, detailLimit);
        for (int i = 0; i < shown && i + detailOffset < wrapped.size(); i++) {
            draw.drawText(textRenderer, wrapped.get(i + detailOffset), x, y + i * 14, TEXT, false);
        }
    }

    private static long completed(ProjectQueue.Project project) {
        return project.jobs().stream().filter(job -> job.state() == ProjectQueue.State.DONE).count();
    }

    private void projectDetails(List<String> lines, String id) {
        var project = overview.projects().stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
        if (project == null) return;
        lines.add(completed(project) + " / " + project.jobs().size() + " jobs complete");
        if (project.repeat() != null) lines.add(project.repeat().cancelled() ? "Output order cancelled"
                : project.repeat().completed() + " batches deposited");
        if (project.supplyChest() != null) lines.add("Chest · " + location(project.supplyChest()));
        project.jobs().stream().filter(job -> job.state() == ProjectQueue.State.FAILED).limit(3)
                .forEach(job -> lines.add(job.work().id() + " · " + job.evidence()));
        project.jobs().stream().filter(job -> job.state() == ProjectQueue.State.LEASED).limit(5)
                .forEach(job -> lines.add(job.worker() + " · " + job.work().id()));
    }

    private void chestDetails(List<String> lines, String key) {
        var observation = overview.stock().get(key);
        if (observation == null) return;
        long age = Math.max(0, (System.currentTimeMillis() - observation.observedAt()) / 1000);
        lines.add("Last checked " + (age < 60 ? age + "s" : age / 60 + "m") + " ago");
        if (observation.report().items().isEmpty()) lines.add("Empty");
        appendItems(lines, observation.report().items());
    }

    private static void appendItems(List<String> lines, Map<String, Integer> items) {
        items.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var id = Identifier.tryParse(entry.getKey());
            String name = id != null && Registries.ITEM.containsId(id)
                    ? Registries.ITEM.get(id).getName().getString() : entry.getKey();
            lines.add(entry.getValue() + " × " + name);
        });
    }

    private static String location(String key) { return key.replace("block:", "").replace(",", ", "); }

    private void line(DrawContext draw, String text, int x, int y, int room, int color) {
        draw.drawText(textRenderer, textRenderer.trimToWidth(text, Math.max(1, room)), x, y, color, false);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { close(); return true; }
        if (button == 0 && x >= MARGIN && x < MARGIN + listWidth() && y >= ROW_TOP) {
            int row = (int)(y - ROW_TOP) / ROW_HEIGHT;
            var rows = rows();
            if (row < visibleRows() && row + offset < rows.size()) {
                select(rows.get(row + offset));
                setFocused(null);
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (x >= detailLeft()) {
            detailOffset = Math.max(0, Math.min(detailLimit, detailOffset + (amount > 0 ? -2 : 2)));
            return true;
        }
        offset = Math.max(0, Math.min(Math.max(0, rows().size() - visibleRows()), offset + (amount > 0 ? -1 : 1)));
        return true;
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (command != null && command.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            sendCommand();
            return true;
        }
        if (!search.isFocused() && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            var rows = rows();
            if (rows.isEmpty()) return true;
            int index = -1;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).key.equals(selection.get(tab))) index = i;
            index = Math.max(0, Math.min(rows.size() - 1, index + (key == GLFW.GLFW_KEY_UP ? -1 : 1)));
            select(rows.get(index));
            if (index < offset) offset = index;
            if (index >= offset + visibleRows()) offset = index - visibleRows() + 1;
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override public void close() { client.setScreen(parent); }
}
