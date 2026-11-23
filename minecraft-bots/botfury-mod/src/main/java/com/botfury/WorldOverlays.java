package com.botfury;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/** Renders local planning information against the terrain's depth buffer. */
final class WorldOverlays {
    static final int IDLE_COLOR = 0xC9AEBC;
    private static final int RANGE = 112;
    private static final RenderLayer FILLS = RenderLayer.getDebugQuads();
    private static final RenderLayer LINES = RenderLayer.getLines();
    private static final VertexConsumerProvider.Immediate buffers = VertexConsumerProvider.immediate(
            Map.of(FILLS, new BufferBuilder(128 * 1024), LINES, new BufferBuilder(256 * 1024)), new BufferBuilder(256));
    private record Preview(ClientWorld world, Map<BlockPos, BlockState> original, Map<BlockPos, BlockState> cells, BlockPos origin) {}
    private static Preview preview;

    static void preview(ClientWorld world, Map<BlockPos, BlockState> original, Map<BlockPos, BlockState> cells, BlockPos origin) {
        preview = new Preview(world, original, cells, origin);
    }

    static void clear() { preview = null; }

    static void register() {
        WorldRenderEvents.LAST.register(context -> {
            var client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null || client.options.hudHidden
                    || Boolean.getBoolean("botfury.managedHttp")) return;
            if (preview != null && preview.world != client.world) clear();
            var matrices = context.matrixStack();
            if (matrices == null) return;
            var camera = context.camera().getPos();
            if (BotUiConfig.paths) drawPaths(client, matrices, camera);
            if (preview != null) drawPreview(client, matrices, camera, context.frustum(), preview);
        });
    }

    private static void drawPaths(MinecraftClient client, MatrixStack matrices, Vec3d camera) {
        var lines = buffers.getBuffer(LINES);
        for (var bot : BotTracker.all()) {
            if (!bot.fresh() || !bot.connected() || !bot.alive() || !bot.sameWorld() || bot.route().isEmpty()) continue;
            var player = client.world.getPlayers().stream()
                    .filter(entity -> entity.getGameProfile().getName().equalsIgnoreCase(bot.name()))
                    .findFirst().orElse(null);
            var previous = (player == null ? new Vec3d(bot.x(), bot.y(), bot.z()) : player.getPos()).add(0, .11, 0);
            int color = pathColor(bot.port());
            float alpha = bot.port() == BotTracker.selected() ? .72f : .45f;
            for (var block : bot.route()) {
                var next = Vec3d.ofBottomCenter(block).add(0, .11, 0);
                double length = previous.squaredDistanceTo(next);
                if (length > .001 && length < 36 && (camera.squaredDistanceTo(previous) < RANGE * RANGE
                        || camera.squaredDistanceTo(next) < RANGE * RANGE)) {
                    line(lines, matrices.peek(), previous.subtract(camera), next.subtract(camera), color, alpha);
                }
                previous = next;
            }
        }
        buffers.draw(LINES);
    }

    static int pathColor(int port) {
        return taskColor(BotTracker.get(port));
    }

    static int taskColor(BotTracker.Bot bot) {
        if (bot == null) return IDLE_COLOR;
        return switch (bot.task()) {
            case "orbit", "strike" -> 0xF56F84;
            case "mining", "GATHER", "EXCAVATE" -> 0xF8D788;
            case "CRAFT", "SMELT", "FILL_BUCKETS" -> 0xFAAE86;
            case "BUILD", "VERIFY", "INSPECT" -> 0x8DDBC3;
            case "TRANSFER", "inventory", "goto", "move" -> 0x9EB8FF;
            default -> IDLE_COLOR;
        };
    }

    private static void line(VertexConsumer buffer, MatrixStack.Entry matrix, Vec3d a, Vec3d b, int color, float alpha) {
        var normal = b.subtract(a).normalize();
        vertex(buffer, matrix, a, normal, color, alpha);
        vertex(buffer, matrix, b, normal, color, alpha);
    }

    private static void vertex(VertexConsumer buffer, MatrixStack.Entry matrix, Vec3d point, Vec3d normal, int color, float alpha) {
        buffer.vertex(matrix.getPositionMatrix(), (float) point.x, (float) point.y, (float) point.z)
                .color(channel(color, 16), channel(color, 8), channel(color, 0), alpha)
                .normal(matrix.getNormalMatrix(), (float) normal.x, (float) normal.y, (float) normal.z).next();
    }

    private static void drawPreview(MinecraftClient client, MatrixStack matrices, Vec3d camera, Frustum frustum, Preview ghost) {
        var lines = buffers.getBuffer(LINES);
        var fills = buffers.getBuffer(FILLS);
        var world = client.world;
        for (var entry : ghost.cells.entrySet()) {
            var state = entry.getValue();
            if (state.isAir()) continue;
            var relative = entry.getKey();
            var pos = ghost.origin.add(relative);
            var observed = world.getBlockState(pos);
            if (!pos.isWithinDistance(camera, RANGE) || state.equals(observed)
                    || frustum != null && !frustum.isVisible(new Box(pos))) continue;
            if (state.isFullCube(world, pos) && covered(ghost, world, relative, pos)) continue;
            var shape = state.getOutlineShape(world, pos);
            int tint = !observed.isAir() ? 0xFF797F
                    : state.equals(ghost.original.get(relative)) ? 0xF6D590 : 0xFFF2CE;
            if (shape.isEmpty()) {
                ghostBox(matrices, lines, fills, new Box(pos).offset(-camera.x, -camera.y, -camera.z), tint);
            } else {
                for (var box : shape.getBoundingBoxes()) {
                    ghostBox(matrices, lines, fills, box.offset(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z), tint);
                }
            }
        }
        buffers.draw(FILLS);
        buffers.draw(LINES);
    }

    private static boolean covered(Preview preview, ClientWorld world, BlockPos relative, BlockPos absolute) {
        for (var direction : net.minecraft.util.math.Direction.values()) {
            var adjacent = preview.cells.get(relative.offset(direction));
            if (adjacent == null || !adjacent.isFullCube(world, absolute.offset(direction))) return false;
        }
        return true;
    }

    private static void ghostBox(MatrixStack matrices, VertexConsumer lines, VertexConsumer fills, Box box, int tint) {
        var matrix = matrices.peek().getPositionMatrix();
        float x = (float) box.minX, y = (float) box.minY, z = (float) box.minZ;
        float right = (float) box.maxX, top = (float) box.maxY, back = (float) box.maxZ;
        if (x > 0) face(fills, matrix, tint, x, y, z, 0, 0, back - z, 0, top - y, 0);
        if (right < 0) face(fills, matrix, tint, right, y, back, 0, 0, z - back, 0, top - y, 0);
        if (y > 0) face(fills, matrix, tint, x, y, z, right - x, 0, 0, 0, 0, back - z);
        if (top < 0) face(fills, matrix, tint, x, top, back, right - x, 0, 0, 0, 0, z - back);
        if (z > 0) face(fills, matrix, tint, right, y, z, x - right, 0, 0, 0, top - y, 0);
        if (back < 0) face(fills, matrix, tint, x, y, back, right - x, 0, 0, 0, top - y, 0);
        WorldRenderer.drawBox(matrices, lines, box, channel(tint, 16), channel(tint, 8), channel(tint, 0), .42f);
    }

    private static void face(VertexConsumer buffer, org.joml.Matrix4f matrix, int tint,
                             float x, float y, float z, float ux, float uy, float uz, float vx, float vy, float vz) {
        float r = channel(tint, 16), g = channel(tint, 8), b = channel(tint, 0);
        buffer.vertex(matrix, x, y, z).color(r, g, b, .13f).next();
        buffer.vertex(matrix, x + ux, y + uy, z + uz).color(r, g, b, .13f).next();
        buffer.vertex(matrix, x + ux + vx, y + uy + vy, z + uz + vz).color(r, g, b, .13f).next();
        buffer.vertex(matrix, x + vx, y + vy, z + vz).color(r, g, b, .13f).next();
    }

    private static float channel(int color, int shift) { return ((color >> shift) & 255) / 255f; }
}
