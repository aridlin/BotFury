package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;

/** Gives fleet panels their own display scale while retaining vanilla widget input and narration. */
abstract class FleetPanel extends Screen {
    private float scale, left, top;

    protected FleetPanel(Text title) { super(title); }

    @Override protected final void init() {
        var window = client.getWindow();
        int viewportWidth = window.getScaledWidth(), viewportHeight = window.getScaledHeight();
        width = 466;
        height = 301;
        float target = scaleForWindow(window);
        scale = Math.min(target, Math.min((viewportWidth - 10f) / width, (viewportHeight - 10f) / height));
        left = (viewportWidth - width * scale) / 2;
        top = (viewportHeight - height * scale) / 2;
        initPanel();
    }

    protected abstract void initPanel();
    protected boolean framed() { return true; }
    protected DitherPress.Surface frameSurface() { return DitherPress.Surface.PANEL; }

    static float scaleForWindow(Window window) {
        return Math.max(window.getScaledHeight() / 750f, 1.2f / (float) window.getScaleFactor());
    }

    @Override public final void render(DrawContext draw, int mouseX, int mouseY, float delta) {
        var matrices = draw.getMatrices();
        matrices.push();
        matrices.translate(left, top, 0);
        matrices.scale(scale, scale, 1);
        if (framed()) DitherPress.draw(draw, frameSurface(), 0, 0, width, height);
        renderPanel(draw, (int) panelX(mouseX), (int) panelY(mouseY), delta);
        matrices.pop();
    }

    protected void renderPanel(DrawContext draw, int mouseX, int mouseY, float delta) {
        super.render(draw, mouseX, mouseY, delta);
    }

    protected static void drawListRow(DrawContext draw, int x, int y, int right, int bottom,
                                      boolean selected, boolean hovered) {
        BotUiTheme.row(draw, x, y, right, bottom, selected, hovered);
    }

    protected void clipPanel(DrawContext draw, int x, int y, int right, int bottom) {
        draw.enableScissor((int) (left + x * scale), (int) (top + y * scale),
                (int) Math.ceil(left + right * scale), (int) Math.ceil(top + bottom * scale));
    }

    protected double panelX(double x) { return (x - left) / scale; }
    protected double panelY(double y) { return (y - top) / scale; }

    @Override public final boolean mouseClicked(double x, double y, int button) {
        return panelMouseClicked(panelX(x), panelY(y), button);
    }

    protected boolean panelMouseClicked(double x, double y, int button) {
        return super.mouseClicked(x, y, button);
    }

    @Override public final boolean mouseReleased(double x, double y, int button) {
        return panelMouseReleased(panelX(x), panelY(y), button);
    }

    protected boolean panelMouseReleased(double x, double y, int button) {
        return super.mouseReleased(x, y, button);
    }

    @Override public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return super.mouseDragged(panelX(x), panelY(y), button, dx / scale, dy / scale);
    }

    @Override public final boolean mouseScrolled(double x, double y, double amount) {
        double px = panelX(x), py = panelY(y);
        return px >= 0 && px < width && py >= 0 && py < height && panelMouseScrolled(px, py, amount);
    }

    protected boolean panelMouseScrolled(double x, double y, double amount) {
        return super.mouseScrolled(x, y, amount);
    }
}
