package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class ItemPickerScreen extends FleetPanel {
    private record Entry(Item item, String id, String name, String words) {}
    private enum Category { All, Recent, Tools, Wood, Stone, Ores, Food, Blocks }

    private static final int ROW_HEIGHT = 28;
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private final Screen parent;
    private final Consumer<String> selected;
    private final boolean blocksOnly;
    private final List<Entry> all;
    private List<Entry> filtered = List.of();
    private TextFieldWidget search;
    private Category category = Category.All;
    private String query = "";
    private int firstRow, cursor, left, panelWidth, columns, listTop;

    ItemPickerScreen(Screen parent, boolean blocksOnly, Consumer<String> selected) {
        super(Text.literal(blocksOnly ? "Choose mining block" : "Choose item"));
        this.parent = parent;
        this.blocksOnly = blocksOnly;
        this.selected = selected;
        all = Registries.ITEM.getIds().stream().map(id -> {
            var item = Registries.ITEM.get(id);
            String name = item.getName().getString();
            String words = (id.toString() + " " + id.getPath().replace('_', ' ') + " " + name).toLowerCase(Locale.ROOT);
            return new Entry(item, id.toString(), name, words);
        }).filter(entry -> entry.item != Items.AIR && (!blocksOnly || entry.item instanceof BlockItem))
                .sorted(Comparator.comparing(Entry::name).thenComparing(Entry::id)).toList();
    }

    @Override protected void initPanel() {
        panelWidth = Math.min(540, width - 24);
        left = (width - panelWidth) / 2;
        columns = panelWidth >= 340 ? 2 : 1;
        int tabColumns = panelWidth >= 304 ? 8 : 4;
        listTop = 71 + ((Category.values().length + tabColumns - 1) / tabColumns) * 22;
        int searchWidth = panelWidth - (blocksOnly ? 0 : 76);
        search = new TextFieldWidget(textRenderer, left, 38, searchWidth, 21, Text.literal("Search items"));
        search.setPlaceholder(Text.literal("Search name or item ID"));
        search.setMaxLength(128);
        search.setText(query);
        search.setChangedListener(value -> { query = value; filter(); });
        addDrawableChild(search);
        setInitialFocus(search);
        if (!blocksOnly) {
            var held = new BotUiButton("Use held", left + searchWidth + 6, 38, 70, 21,
                    button -> choose(ClientActions.heldItem(client)));
            held.active = client.player != null && !client.player.getMainHandStack().isEmpty();
            addDrawableChild(held);
        }
        int tabWidth = panelWidth / tabColumns;
        for (var option : Category.values()) {
            int column = option.ordinal() % tabColumns, row = option.ordinal() / tabColumns;
            addDrawableChild(new BotUiButton(option.name(), left + column * tabWidth,
                    66 + row * 22, tabWidth - 3, 19, button -> {
                category = option;
                BotUiSound.confirm();
                filter();
                updateTabs();
                setFocused(search);
            }));
        }
        addDrawableChild(new BotUiButton("Back", left + panelWidth - 60, height - 28, 60, 19, button -> close()));
        filter();
        updateTabs();
    }

    private void updateTabs() {
        int firstTab = blocksOnly ? 1 : 2;
        for (var option : Category.values()) {
            var button = (BotUiButton) children().get(firstTab + option.ordinal());
            button.highlight(option == category);
        }
    }

    private void filter() {
        String[] tokens = query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        var recent = List.copyOf(RECENT);
        var matches = all.stream()
                .filter(entry -> (category == Category.Recent ? recent.contains(entry.id) : matchesCategory(entry, category))
                        && java.util.Arrays.stream(tokens).allMatch(entry.words::contains));
        if (category == Category.Recent) matches = matches.sorted(Comparator.comparingInt(entry -> recent.indexOf(entry.id)));
        filtered = matches.toList();
        cursor = 0;
        firstRow = 0;
    }

    private static boolean matchesCategory(Entry entry, Category category) {
        String id = entry.id.substring(entry.id.indexOf(':') + 1);
        return switch (category) {
            case Tools -> entry.item instanceof ToolItem || entry.item instanceof ShearsItem
                    || entry.item instanceof FishingRodItem || entry.item instanceof BowItem
                    || entry.item instanceof CrossbowItem || id.equals("brush") || id.equals("flint_and_steel");
            case Wood -> List.of("oak_", "spruce_", "birch_", "jungle_", "acacia_", "mangrove_", "cherry_",
                    "bamboo", "crimson_", "warped_", "stripped_").stream().anyMatch(id::startsWith) || id.equals("stick");
            case Stone -> List.of("stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "basalt")
                    .stream().anyMatch(id::contains);
            case Ores -> id.endsWith("_ore") || id.equals("ancient_debris") || id.startsWith("raw_");
            case Food -> entry.item.isFood();
            case Blocks -> entry.item instanceof BlockItem;
            default -> true;
        };
    }

    private int rows() { return Math.max(1, (height - listTop - 34) / ROW_HEIGHT); }
    private int totalRows() { return (filtered.size() + columns - 1) / columns; }
    private int cellWidth() { return panelWidth / columns; }

    private int indexAt(double x, double y) {
        if (x < left || x >= left + columns * cellWidth() || y < listTop || y >= listTop + rows() * ROW_HEIGHT) return -1;
        int index = (firstRow + (int)(y - listTop) / ROW_HEIGHT) * columns + (int)(x - left) / cellWidth();
        return index < filtered.size() ? index : -1;
    }

    @Override public boolean shouldPause() { return false; }

    @Override protected void renderPanel(DrawContext draw, int mouseX, int mouseY, float delta) {
        draw.fillGradient(0, 0, width, height, BotUiTheme.PANEL_BOTTOM, BotUiTheme.PANEL_TOP);
        draw.drawTextWithShadow(textRenderer, title, left, 17, BotUiTheme.TEXT);
        String count = filtered.size() + (filtered.size() == 1 ? " item" : " items");
        draw.drawText(textRenderer, count, left + panelWidth - textRenderer.getWidth(count), 17, BotUiTheme.MUTED, false);
        super.renderPanel(draw, mouseX, mouseY, delta);
        int hover = indexAt(mouseX, mouseY);
        for (int row = 0; row < rows(); row++) for (int col = 0; col < columns; col++) {
            int index = (firstRow + row) * columns + col;
            if (index >= filtered.size()) break;
            var entry = filtered.get(index);
            int x = left + col * cellWidth(), y = listTop + row * ROW_HEIGHT;
            draw.fill(x, y, x + cellWidth() - 4, y + 25, hover == index ? BotUiTheme.SELECTED : 0xDA37212A);
            if (cursor == index) draw.fill(x, y, x + 2, y + 25, BotUiTheme.ACCENT);
            draw.drawItem(entry.item.getDefaultStack(), x + 7, y + 4);
            draw.drawText(textRenderer, textRenderer.trimToWidth(entry.name, cellWidth() - 34),
                    x + 29, y + 4, BotUiTheme.TEXT, false);
            draw.drawText(textRenderer, textRenderer.trimToWidth(entry.id, cellWidth() - 34),
                    x + 29, y + 15, BotUiTheme.MUTED, false);
        }
        if (filtered.isEmpty()) {
            draw.drawCenteredTextWithShadow(textRenderer, category == Category.Recent && query.isBlank()
                    ? "No recent selections" : "No matching items", width / 2, listTop + 8, BotUiTheme.MUTED);
        }
        draw.drawText(textRenderer, textRenderer.trimToWidth("Arrows to move  ·  Enter to select", panelWidth - 68),
                left, height - 22, BotUiTheme.MUTED, false);
        if (totalRows() > rows()) {
            int h = rows() * ROW_HEIGHT - 3;
            int thumb = Math.max(8, h * rows() / totalRows());
            int y = listTop + firstRow * (h - thumb) / (totalRows() - rows());
            draw.fill(left + panelWidth - 2, listTop, left + panelWidth, listTop + h, BotUiTheme.EDGE);
            draw.fill(left + panelWidth - 2, y, left + panelWidth, y + thumb, BotUiTheme.ACCENT);
        }
    }

    private void choose(String id) {
        RECENT.remove(id);
        RECENT.addFirst(id);
        while (RECENT.size() > 16) RECENT.removeLast();
        BotUiSound.confirm();
        selected.accept(id);
    }

    @Override protected boolean panelMouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { close(); return true; }
        int index = indexAt(x, y);
        if (button == 0 && index >= 0) { choose(filtered.get(index).id); return true; }
        return super.panelMouseClicked(x, y, button);
    }

    @Override protected boolean panelMouseScrolled(double x, double y, double amount) {
        if (amount == 0 || y < listTop || y >= listTop + rows() * ROW_HEIGHT
                || x < left || x > left + panelWidth) return false;
        firstRow = Math.max(0, Math.min(Math.max(0, totalRows() - rows()), firstRow + (amount > 0 ? -2 : 2)));
        if (cursor / columns < firstRow || cursor / columns >= firstRow + rows()) cursor = firstRow * columns;
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (filtered.isEmpty()) return super.keyPressed(key, scan, modifiers);
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() == search) {
            choose(filtered.get(cursor).id);
            return true;
        }
        int offset = switch (key) {
            case GLFW.GLFW_KEY_DOWN -> 1;
            case GLFW.GLFW_KEY_UP -> -1;
            case GLFW.GLFW_KEY_PAGE_DOWN -> rows() * columns;
            case GLFW.GLFW_KEY_PAGE_UP -> -rows() * columns;
            default -> 0;
        };
        if (offset == 0) return super.keyPressed(key, scan, modifiers);
        cursor = Math.max(0, Math.min(filtered.size() - 1, cursor + offset));
        int row = cursor / columns;
        firstRow = Math.min(firstRow, row);
        firstRow = Math.max(firstRow, row - rows() + 1);
        setFocused(search);
        return true;
    }

    @Override public void close() { BotUiSound.back(); client.setScreen(parent); }
}
