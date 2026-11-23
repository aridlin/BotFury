package com.botfury;

import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import org.lwjgl.opengl.GL11;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.IdentityHashMap;

abstract class BotHalftone extends RenderLayer {
    private static final Identifier SHADER = Identifier.of("botfury", "bot_halftone");
    private static final VertexConsumerProvider.Immediate buffers = VertexConsumerProvider.immediate(new BufferBuilder(64 * 1024));
    private static final Map<Identifier, RenderLayer> layers = new WeakHashMap<>();
    private static final Map<Entity, Boolean> occluded = new IdentityHashMap<>();
    private static net.minecraft.client.gl.ShaderProgram shader;
    private static final ShaderProgram PROGRAM = new ShaderProgram(() -> shader);

    private BotHalftone() {
        super("botfury_halftone", VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS,
                256, false, true, () -> {}, () -> {});
    }

    static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context ->
                context.register(SHADER, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, loaded -> shader = loaded));
        WorldRenderEvents.START.register(context -> occluded.clear());
        WorldRenderEvents.AFTER_ENTITIES.register(context -> buffers.draw());
    }

    static boolean available() { return shader != null; }

    static boolean occluded(Entity bot) {
        return occluded.computeIfAbsent(bot, entity -> {
            var client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return false;
            var eye = client.gameRenderer.getCamera().getPos();
            var center = entity.getPos().add(0, entity.getHeight() * .55, 0);
            return client.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, client.player)).getType() == HitResult.Type.BLOCK;
        });
    }

    static VertexConsumer buffer(Identifier texture) {
        return buffers.getBuffer(layers.computeIfAbsent(texture, BotHalftone::layer));
    }

    private static RenderLayer layer(Identifier texture) {
        return RenderLayer.of("botfury_occluded", VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                VertexFormat.DrawMode.QUADS, 256, MultiPhaseParameters.builder()
                        .program(PROGRAM)
                        .texture(new Texture(texture, false, false))
                        .depthTest(new DepthTest(">", GL11.GL_GREATER))
                        .writeMaskState(COLOR_MASK)
                        .transparency(TRANSLUCENT_TRANSPARENCY)
                        .build(false));
    }

}
