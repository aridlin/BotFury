package com.botfury;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;

final class BotGeometry {
    private static final int SIDES = 24;

    private BotGeometry() {}

    static void circle(DrawContext draw, float x, float y, float radius, int color) {
        var matrix = draw.getMatrices().peek().getPositionMatrix();
        var buffer = start(draw);
        for (int i = 0; i < SIDES; i++) {
            double a = i * Math.PI * 2 / SIDES, b = (i + 1) * Math.PI * 2 / SIDES;
            float ax = (float) Math.cos(a), ay = (float) Math.sin(a);
            float bx = (float) Math.cos(b), by = (float) Math.sin(b);
            float innerX = x + ax * radius, innerY = y + ay * radius;
            float nextX = x + bx * radius, nextY = y + by * radius;
            float outer = radius + .48f;
            float outerX = x + ax * outer, outerY = y + ay * outer;
            float nextOuterX = x + bx * outer, nextOuterY = y + by * outer;
            point(buffer, matrix, x, y, color);
            point(buffer, matrix, innerX, innerY, color);
            point(buffer, matrix, nextX, nextY, color);
            point(buffer, matrix, innerX, innerY, color);
            point(buffer, matrix, outerX, outerY, color & 0xFFFFFF);
            point(buffer, matrix, nextOuterX, nextOuterY, color & 0xFFFFFF);
            point(buffer, matrix, innerX, innerY, color);
            point(buffer, matrix, nextOuterX, nextOuterY, color & 0xFFFFFF);
            point(buffer, matrix, nextX, nextY, color);
        }
        end(buffer);
    }

    static void arrow(DrawContext draw, float x, float y, double angle, float size, int color) {
        var matrix = draw.getMatrices().peek().getPositionMatrix();
        var buffer = start(draw);
        float ax = (float) Math.cos(angle) * size, ay = (float) Math.sin(angle) * size;
        float tipX = x + ax, tipY = y + ay;
        float tailX = x - ax * .60f, tailY = y - ay * .60f;
        float leftX = tailX - ay * .67f, leftY = tailY + ax * .67f;
        float rightX = tailX + ay * .67f, rightY = tailY - ax * .67f;
        point(buffer, matrix, tipX, tipY, color);
        point(buffer, matrix, leftX, leftY, color);
        point(buffer, matrix, x - ax * .27f, y - ay * .27f, color);
        point(buffer, matrix, tipX, tipY, color);
        point(buffer, matrix, x - ax * .27f, y - ay * .27f, color);
        point(buffer, matrix, rightX, rightY, color);
        end(buffer);
    }

    private static BufferBuilder start(DrawContext draw) {
        draw.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        var buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        return buffer;
    }

    private static void end(BufferBuilder buffer) {
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void point(BufferBuilder buffer, Matrix4f matrix, float x, float y, int color) {
        buffer.vertex(matrix, x, y, 0).color((color >> 16) & 255, (color >> 8) & 255, color & 255, color >>> 24).next();
    }
}
