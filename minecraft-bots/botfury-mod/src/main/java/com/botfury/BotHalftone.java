package com.botfury;

import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.WeakHashMap;

abstract class BotHalftone extends RenderLayer {
    private static final Identifier SHADER = Identifier.of("botfury", "bot_halftone");
    private static final VertexConsumerProvider.Immediate buffers = VertexConsumerProvider.immediate(new BufferBuilder(64 * 1024));
    private static final Map<Identifier, RenderLayer> layers = new WeakHashMap<>();
    private static SimpleFramebuffer terrainDepth;
    private static boolean hasTerrainDepth;
    private static net.minecraft.client.gl.ShaderProgram shader;
    private static final ShaderProgram PROGRAM = new ShaderProgram(() -> shader);
    private static int previousDepthSampler;
    private static final Texturing TERRAIN_SAMPLER = new Texturing("botfury_terrain_depth", () -> {
        previousDepthSampler = RenderSystem.getShaderTexture(3);
        RenderSystem.setShaderTexture(3, terrainDepth.getDepthAttachment());
    }, () -> RenderSystem.setShaderTexture(3, previousDepthSampler));

    private BotHalftone() {
        super("botfury_halftone", VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS,
                256, false, true, () -> {}, () -> {});
    }

    static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context ->
                context.register(SHADER, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, loaded -> shader = loaded));
        WorldRenderEvents.BEFORE_ENTITIES.register(context -> captureTerrainDepth());
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (hasTerrainDepth) buffers.draw();
        });
    }

    private static void captureTerrainDepth() {
        var client = MinecraftClient.getInstance();
        var main = client.getFramebuffer();
        hasTerrainDepth = shader != null && BotUiConfig.esp && !Boolean.getBoolean("botfury.managedHttp")
                && main.useDepthAttachment && BotTracker.all().stream().anyMatch(BotTracker.Bot::sameWorld);
        if (!hasTerrainDepth) return;
        if (terrainDepth == null) {
            terrainDepth = new SimpleFramebuffer(main.textureWidth, main.textureHeight, true, MinecraftClient.IS_SYSTEM_MAC);
        } else if (terrainDepth.textureWidth != main.textureWidth || terrainDepth.textureHeight != main.textureHeight) {
            terrainDepth.resize(main.textureWidth, main.textureHeight, MinecraftClient.IS_SYSTEM_MAC);
        }
        // Snapshot opaque scene depth before translucent bot models can write their own depth.
        terrainDepth.copyDepthFrom(main);
        main.beginWrite(false);
    }

    static boolean available() { return hasTerrainDepth; }

    static VertexConsumer buffer(Identifier texture) {
        return buffers.getBuffer(layers.computeIfAbsent(texture, BotHalftone::layer));
    }

    private static RenderLayer layer(Identifier texture) {
        return RenderLayer.of("botfury_occluded", VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                VertexFormat.DrawMode.QUADS, 256, MultiPhaseParameters.builder()
                        .program(PROGRAM)
                        .texture(new Texture(texture, false, false))
                        .depthTest(ALWAYS_DEPTH_TEST)
                        .texturing(TERRAIN_SAMPLER)
                        .writeMaskState(COLOR_MASK)
                        .transparency(TRANSLUCENT_TRANSPARENCY)
                        .build(false));
    }

}
