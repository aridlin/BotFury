package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

final class MiningScreen extends Screen {
    private final Screen parent;
    private String category = "Ores", variant = "both", error = "";
    private int offset;

    MiningScreen(Screen parent) {
        super(Text.literal("Coordinated mining"));
        this.parent = parent;
    }

    @Override public boolean shouldPause() { return false; }
    @Override protected void init() { rebuild(); }

    private List<ResourceCatalog.Resource> resources() {
        return ResourceCatalog.RESOURCES.stream().filter(resource -> resource.category().equals(category)).toList();
    }

    private int rows() { return Math.max(1, (height - 139) / 24); }

    private void rebuild() {
        clearChildren();
        int content = Math.min(350, width - 24), left = (width - content) / 2;
        int column = content / 3;
        var categories = List.of("Ores", "Wood", "Stone");
        for (int i = 0; i < categories.size(); i++) {
            String name = categories.get(i);
            var tab = new BotUiButton(name, left + i * column, 48, column - 3, 20, button -> {
                category = name;
                offset = 0;
                error = "";
                BotUiSound.confirm();
                rebuild();
            });
            tab.highlight(category.equals(name));
            addDrawableChild(tab);
        }

        boolean hasVariants = resources().stream().anyMatch(resource -> !resource.deepslate().isEmpty());
        if (hasVariants) {
            var choices = List.of("both", "normal", "deepslate");
            for (int i = 0; i < choices.size(); i++) {
                String choice = choices.get(i);
                String label = switch (choice) { case "both" -> "Both"; case "normal" -> "Normal"; default -> "Deepslate"; };
                var tab = new BotUiButton(label, left + i * column, 74, column - 3, 20, button -> {
                    variant = choice;
                    BotUiSound.confirm();
                    rebuild();
                });
                tab.highlight(variant.equals(choice));
                addDrawableChild(tab);
            }
        }

        var list = resources();
        offset = Math.max(0, Math.min(offset, Math.max(0, list.size() - rows())));
        for (int i = offset; i < list.size() && i < offset + rows(); i++) {
            var entry = list.get(i);
            var button = new BotUiButton(entry.label(), left, 101 + (i - offset) * 24, content - 3, 21,
                    action -> start(entry.id(), entry.deepslate().isEmpty() ? "normal" : variant));
            button.active = !hasVariants || !variant.equals("deepslate") || !entry.deepslate().isEmpty();
            addDrawableChild(button);
        }
        addDrawableChild(new BotUiButton("Search blocks…", left, height - 28, content - 80, 20, button -> {
            BotUiSound.open();
            client.setScreen(new ItemPickerScreen(this, true, id -> start("block:" + id, "normal")));
        }));
        addDrawableChild(new BotUiButton("Back", left + content - 74, height - 28, 71, 20, button -> close()));
    }

    private void start(String id, String choice) {
        String command = "mining:" + id + "|" + choice + "|" + BotFuryMod.controlPort();
        if (BotTracker.sendSelected(command) > 0) {
            BotUiSound.confirm();
            client.setScreen(null);
        } else {
            error = "No compatible live bots.";
            if (client.currentScreen != this) client.setScreen(this);
        }
    }

    @Override public void render(DrawContext draw, int x, int y, float delta) {
        draw.fillGradient(0, 0, width, height, 0xF0121A2D, 0xEA0C1220);
        draw.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFE9F6FF);
        draw.drawCenteredTextWithShadow(textRenderer, error.isEmpty() ? BotTracker.targetLabel() : error,
                width / 2, 32, error.isEmpty() ? 0xFFB5C8D8 : 0xFFFFCB7F);
        super.render(draw, x, y, delta);
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (y < 101 || y >= height - 28 || amount == 0) return false;
        int previous = offset;
        offset = Math.max(0, Math.min(Math.max(0, resources().size() - rows()), offset + (amount > 0 ? -2 : 2)));
        if (offset != previous) rebuild();
        return true;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (FormationClient.wheel.matchesMouse(button)) { close(); return true; }
        return super.mouseClicked(x, y, button);
    }
    @Override public void close() { BotUiSound.back(); client.setScreen(parent); }
}
