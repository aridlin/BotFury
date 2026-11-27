package com.botfury;

import com.botfury.mixin.SpriteImageAccess;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

final class FleetTerrain {
    private static final int CHUNK = 16;
    private static final int PIXELS = CHUNK * CHUNK;
    private static final int RADIUS = 4;
    private static final long RESAMPLE_MILLIS = 90_000;
    private static final java.util.concurrent.Executor ENCODER = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "BotFury terrain encoder");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<SpriteContents, int[]> SPRITES = new WeakHashMap<>();
    private static final Map<ChunkPos, Long> VISITED = new LinkedHashMap<>();
    private static final ArrayDeque<Tile> RECENT = new ArrayDeque<>();
    private static final Random MODEL_RANDOM = Random.create();
    private static ClientWorld world;
    private static ChunkPos center;
    private static List<ChunkPos> candidates = List.of();
    private static CompletableFuture<Tile> encoding;
    private static int ticks;

    private record Tile(int x, int z, long seen, int[] quarters, String png) {}

    static void tick(MinecraftClient client) {
        if (world != client.world) {
            world = client.world;
            center = null;
            candidates = List.of();
            VISITED.clear();
            RECENT.clear();
            encoding = null;
            ticks = 0;
        }
        if (world == null || client.player == null || client.getCurrentServerEntry() == null) return;
        if (encoding != null && encoding.isDone()) {
            Tile tile = encoding.getNow(null);
            encoding = null;
            if (tile != null) {
                if (RECENT.size() == 4) RECENT.removeFirst();
                RECENT.addLast(tile);
            }
        }
        if (++ticks % 30 != 1 || encoding != null || client.getOverlay() != null) return;
        var current = client.player.getChunkPos();
        if (!current.equals(center)) {
            center = current;
            candidates = ChunkPos.stream(center, RADIUS)
                    .sorted(Comparator.comparingInt(center::getChebyshevDistance)).toList();
        }
        long now = System.currentTimeMillis();
        for (ChunkPos candidate : candidates) {
            if (now - VISITED.getOrDefault(candidate, 0L) < RESAMPLE_MILLIS) continue;
            var chunk = world.getChunkManager().getChunk(candidate.x, candidate.z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            capture(client, chunk, now);
            if (VISITED.size() >= 512) VISITED.remove(VISITED.keySet().iterator().next());
            VISITED.put(candidate, now);
            return;
        }
    }

    static Object snapshot() {
        return RECENT;
    }

    private static void capture(MinecraftClient client, WorldChunk chunk, long now) {
        int[] pixels = new int[PIXELS * PIXELS];
        int[][] totals = new int[4][3];
        var origin = chunk.getPos();
        var pos = new BlockPos.Mutable();
        for (int z = 0; z < CHUNK; z++) {
            for (int x = 0; x < CHUNK; x++) {
                int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z);
                pos.set(origin.getStartX() + x, y, origin.getStartZ() + z);
                BlockState state = chunk.getBlockState(pos);
                for (int depth = 0; depth < 8 && y > world.getBottomY()
                        && state.getMapColor(world, pos) == MapColor.CLEAR; depth++) {
                    pos.setY(--y);
                    state = chunk.getBlockState(pos);
                }
                int fallback = state.getMapColor(world, pos).color;
                int[] mapColor = totals[(z / 8) * 2 + x / 8];
                mapColor[0] += (fallback >> 16) & 255;
                mapColor[1] += (fallback >> 8) & 255;
                mapColor[2] += fallback & 255;
                var quad = topQuad(client, state, pos);
                int tint = quad != null && quad.hasColor()
                        ? client.getBlockColors().getColor(state, world, pos, quad.getColorIndex()) : -1;
                int[] texture = quad == null ? null : SPRITES.computeIfAbsent(quad.getSprite().getContents(), FleetTerrain::readTexels);
                for (int tz = 0; tz < CHUNK; tz++) {
                    int offset = (z * CHUNK + tz) * PIXELS + x * CHUNK;
                    for (int tx = 0; tx < CHUNK; tx++) {
                        int texel = texture == null ? 0 : texture[tz * CHUNK + tx];
                        int color = (texel >>> 24) < 32 ? fallback : tintedRgb(texel, tint);
                        pixels[offset + tx] = 0xff000000 | color;
                    }
                }
            }
        }
        int[] quarters = new int[4];
        for (int index = 0; index < quarters.length; index++) {
            int[] q = totals[index];
            quarters[index] = (q[0] / 64 << 16) | (q[1] / 64 << 8) | (q[2] / 64);
        }
        encoding = CompletableFuture.supplyAsync(() -> encode(origin, now, pixels, quarters), ENCODER)
                .exceptionally(error -> null);
    }

    private static BakedQuad topQuad(MinecraftClient client, BlockState state, BlockPos pos) {
        if (!state.getFluidState().isEmpty()) return null;
        var model = client.getBlockRenderManager().getModel(state);
        MODEL_RANDOM.setSeed(state.getRenderingSeed(pos));
        for (BakedQuad quad : model.getQuads(state, Direction.UP, MODEL_RANDOM)) {
            if (quad.getFace() == Direction.UP) return quad;
        }
        MODEL_RANDOM.setSeed(state.getRenderingSeed(pos));
        for (BakedQuad quad : model.getQuads(state, null, MODEL_RANDOM)) {
            if (quad.getFace() == Direction.UP) return quad;
        }
        return null;
    }

    private static int[] readTexels(SpriteContents sprite) {
        var image = ((SpriteImageAccess) sprite).botfury$image();
        var colors = new int[CHUNK * CHUNK];
        for (int z = 0; z < CHUNK; z++) {
            for (int x = 0; x < CHUNK; x++) {
                colors[z * CHUNK + x] = image.getColor(x * sprite.getWidth() / CHUNK, z * sprite.getHeight() / CHUNK);
            }
        }
        return colors;
    }

    private static int tintedRgb(int abgr, int tint) {
        int red = (abgr & 255) * ((tint >> 16) & 255) / 255;
        int green = ((abgr >> 8) & 255) * ((tint >> 8) & 255) / 255;
        int blue = ((abgr >> 16) & 255) * (tint & 255) / 255;
        return (red << 16) | (green << 8) | blue;
    }

    private static Tile encode(ChunkPos chunk, long seen, int[] pixels, int[] quarters) {
        var image = new BufferedImage(PIXELS, PIXELS, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, PIXELS, PIXELS, pixels, 0, PIXELS);
        try (var sink = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", sink);
            return new Tile(chunk.x, chunk.z, seen, quarters, Base64.getEncoder().encodeToString(sink.toByteArray()));
        } catch (IOException incompatibleImage) {
            return null;
        }
    }

    private FleetTerrain() {}
}
