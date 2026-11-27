package com.botfury;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** The same 8x8, nine-density screening steps as artwork/botfury/render.py. */
final class DitherPress {
    static final int INK = 0xff191815;
    static final int RUST = 0xff943823;
    static final int ORANGE = 0xffee653c;
    static final int PAPER = 0xfff4edda;
    static final int HORIZONTAL = 1, VERTICAL = 2, RISING = 3, FALLING = 4;
    static final Identifier ATLAS = Identifier.of("botfury", "textures/gui/dumbass-dither-broken-stripes.png");
    private static final int[] INKS = {INK, RUST, ORANGE, PAPER};
    private static final boolean[][][] MARKS = readAtlas();
    private static final int CACHE_LIMIT = 104;
    private static final Map<Plate, Identifier> TEXTURES = new LinkedHashMap<>(32, .75f, true);

    enum Surface {
        PANEL, DIALOG, SHEET, CARD, SELECTED, HOVERED, BUTTON, BUTTON_HOT, FIELD, FIELD_HOT,
        HUD, LABEL, WEDGE, WEDGE_HOT, WEDGE_DISABLED, WORLD
    }

    private record Plate(Surface surface, int width, int height, int direction) {}

    private DitherPress() {}

    private static boolean[][][] readAtlas() {
        try (var stream = DitherPress.class.getResourceAsStream("/assets/botfury/" + ATLAS.getPath())) {
            if (stream == null) throw new IllegalStateException("Dither Press atlas is missing");
            var image = ImageIO.read(stream);
            if (image.getWidth() != 72 || image.getHeight() != 48) {
                throw new IllegalStateException("Dither Press atlas must contain nine densities and six pattern rows");
            }
            int mark = image.getRGB(64, 0);
            var atlas = new boolean[6][9][64];
            for (int row = 0; row < 6; row++) {
                for (int column = 0; column <= 8; column++) {
                    for (int offset = 0; offset < 64; offset++) {
                        atlas[row][column][offset] = image.getRGB(column * 8 + offset % 8, row * 8 + offset / 8) == mark;
                    }
                }
            }
            return atlas;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static boolean marked(int direction, int density, int x, int y) {
        return MARKS[direction][density][(y & 7) * 8 + (x & 7)];
    }

    private static int screen(double tone, int direction, int x, int y) {
        tone = Math.max(0, Math.min(3, tone));
        int lower = (int) tone;
        int density = (int) ((tone - lower) * 8 + .5);
        return INKS[Math.min(3, lower + (marked(direction, density, x, y) ? 1 : 0))];
    }

    static void draw(DrawContext draw, Surface surface, int x, int y, int right, int bottom) {
        int w = right - x, h = bottom - y;
        if (w <= 0 || h <= 0) return;
        var texture = texture(new Plate(surface, w, h, RISING));
        draw.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        draw.drawTexture(texture, x, y, 0, 0, w, h, w, h);
        RenderSystem.disableBlend();
    }

    static Identifier wedge(boolean enabled, boolean selected, int sector) {
        int direction = sector % 2 == 0 ? FALLING : RISING;
        var surface = !enabled ? Surface.WEDGE_DISABLED : selected ? Surface.WEDGE_HOT : Surface.WEDGE;
        return texture(new Plate(surface, 56, 52, direction));
    }

    static Identifier worldPlate() {
        return texture(new Plate(Surface.WORLD, 8, 8, FALLING));
    }

    private static Identifier texture(Plate plate) {
        var cached = TEXTURES.get(plate);
        if (cached != null) return cached;
        var textures = MinecraftClient.getInstance().getTextureManager();
        if (TEXTURES.size() >= CACHE_LIMIT) {
            var oldest = TEXTURES.entrySet().iterator();
            while (oldest.hasNext()) {
                var entry = oldest.next();
                if (entry.getKey().surface != Surface.WORLD) {
                    textures.destroyTexture(entry.getValue());
                    oldest.remove();
                    break;
                }
            }
        }
        var image = new NativeImage(plate.width, plate.height, false);
        for (int y = 0; y < plate.height; y++) {
            for (int x = 0; x < plate.width; x++) {
                int argb = pixel(plate, x, y);
                int abgr = argb & 0xff00ff00 | (argb & 0xff) << 16 | (argb >> 16 & 0xff);
                image.setColor(x, y, abgr);
            }
        }
        var id = textures.registerDynamicTexture("botfury_press", new NativeImageBackedTexture(image));
        TEXTURES.put(plate, id);
        return id;
    }

    private static int pixel(Plate plate, int x, int y) {
        int w = plate.width, h = plate.height;
        int edgeX = Math.min(x, w - 1 - x), edgeY = Math.min(y, h - 1 - y);
        double u = (x + .5) / w, v = (y + .5) / h;
        return switch (plate.surface) {
            case PANEL -> {
                if (edgeX + edgeY < 3) yield 0;
                if (edgeX == 0 || edgeY == 0) yield RUST;
                double seam = 1 - Math.min(Math.abs(u - .94 - (v - .45) * .18) * 13, 1);
                double lowerBand = Math.max(0, (v - .77 - .15 * u) * 4.5);
                double tone = .06 + seam * (v < .34 ? 1.13 : .38) + lowerBand * .56;
                yield screen(tone, v > .78 ? HORIZONTAL : u > .83 ? FALLING : RISING, x / 2, y / 2);
            }
            case DIALOG -> {
                if (edgeX + edgeY < 3) yield 0;
                if (edgeX == 0 || edgeY == 0) yield RUST;
                double band = Math.max(0, 1 - Math.abs(v - .8 - .32 * u) * 7);
                double side = Math.max(0, 1 - Math.min(u, 1 - u) * 9);
                yield screen(.05 + band * .64 + side * (.28 + v * .72), band > .2 ? RISING : HORIZONTAL,
                        x / 2, y / 2);
            }
            case SHEET -> {
                if (y < 17) {
                    yield x >= w - 21 ? screen(2.18 + (1 - v) * .4, FALLING, x, y) : ORANGE;
                }
                if (x >= w - 3 || y >= h - 2) yield screen(2.72, HORIZONTAL, x, y);
                yield PAPER;
            }
            case CARD, SELECTED, HOVERED -> {
                boolean selected = plate.surface == Surface.SELECTED;
                boolean hover = plate.surface == Surface.HOVERED;
                if (edgeX + edgeY < 1) yield 0;
                if (y == h - 1) yield selected ? ORANGE : RUST;
                if (x < 5) {
                    double tone = selected ? 2.5 - v * .8 : hover ? 1.42 - v * .44 : .49;
                    yield screen(tone, HORIZONTAL, x, y);
                }
                if (x > w - 12) {
                    double tone = selected ? 1 + (u - .83) : hover ? .32 : .15;
                    yield screen(tone, FALLING, x, y);
                }
                yield selected ? RUST : INK;
            }
            case BUTTON, BUTTON_HOT -> {
                boolean hot = plate.surface == Surface.BUTTON_HOT;
                if (edgeX + edgeY < 1) yield 0;
                if (y == 0 || y == h - 1) yield hot ? ORANGE : RUST;
                if (x < Math.min(7, w / 6)) yield screen(hot ? 2.12 - v * .48 : .56 - v * .26,
                        VERTICAL, x, y);
                yield hot ? RUST : INK;
            }
            case FIELD, FIELD_HOT -> {
                if (edgeX + edgeY == 0) yield 0;
                if (edgeX == 0 || edgeY == 0) yield plate.surface == Surface.FIELD_HOT ? ORANGE : RUST;
                yield INK;
            }
            case HUD, LABEL -> {
                if (edgeX + edgeY < 3) yield 0;
                boolean hud = plate.surface == Surface.HUD;
                if (y == 0 && hud) yield PAPER;
                if (x == 0 || x == w - 1 || y == h - 1) yield hud ? RUST : ORANGE;
                if (x < 5 && hud) yield screen(1.62 - v * .68, HORIZONTAL, x, y);
                yield INK;
            }
            case WEDGE, WEDGE_HOT, WEDGE_DISABLED -> {
                boolean hot = plate.surface == Surface.WEDGE_HOT;
                double border = Math.max(0, (v - .52 - .15 * (u - .5)) * 2.3);
                double sides = Math.max(0, (Math.abs(u - .5) - .29) * 4);
                double light = plate.surface == Surface.WEDGE_DISABLED ? 0
                        : (hot ? .88 : .16) + border * (hot ? 1.37 : .88) + sides * .55;
                yield screen(light, v < .29 ? HORIZONTAL : plate.direction, x, y);
            }
            case WORLD -> marked(plate.direction, 5, x, y) ? 0xFFFFFFFF : 0x00FFFFFF;
        };
    }
}
