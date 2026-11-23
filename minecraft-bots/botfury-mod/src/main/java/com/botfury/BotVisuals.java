package com.botfury;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/** Applies client-side opacity to tracked models and their textured render features. */
public final class BotVisuals {
    private BotVisuals() {}

    public static boolean isBot(Entity entity) {
        return !Boolean.getBoolean("botfury.managedHttp") && entity instanceof PlayerEntity player
                && BotTracker.isTrackedPlayer(player.getGameProfile().getName());
    }

    public static boolean hideVanillaLabel(Entity entity) {
        return BotUiConfig.esp && isBot(entity);
    }

    public static VertexConsumer buffer(VertexConsumerProvider provider, RenderLayer layer, Entity bot) {
        if (layer.getVertexFormat() == VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
                && layer instanceof RenderLayer.MultiPhase layered) {
            var texture = layered.phases.texture.getId();
            if (texture.isPresent()) {
                boolean orbit = LocalFormation.mode().equals("orbit");
                var visible = new FadedVertex(provider.getBuffer(RenderLayer.getEntityTranslucent(texture.get())), orbit ? 10 : 30, true);
                return BotUiConfig.esp && BotHalftone.available() && BotHalftone.occluded(bot)
                        ? VertexConsumers.union(visible, new FadedVertex(BotHalftone.buffer(texture.get()), orbit ? 34 : 70, false))
                        : visible;
            }
        }
        return provider.getBuffer(layer);
    }

    private record FadedVertex(VertexConsumer delegate, int opacity, boolean tint) implements VertexConsumer {
        @Override public VertexConsumer vertex(double x, double y, double z) { delegate.vertex(x, y, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { delegate.color(r, tint ? g * 52 / 100 : g, tint ? b * 60 / 100 : b, (a * opacity + 50) / 100); return this; }
        @Override public VertexConsumer texture(float u, float v) { delegate.texture(u, v); return this; }
        @Override public VertexConsumer overlay(int u, int v) { delegate.overlay(u, v); return this; }
        @Override public VertexConsumer light(int u, int v) { delegate.light(u, v); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { delegate.normal(x, y, z); return this; }
        @Override public void next() { delegate.next(); }
        @Override public void fixedColor(int r, int g, int b, int a) { delegate.fixedColor(r, tint ? g * 52 / 100 : g, tint ? b * 60 / 100 : b, (a * opacity + 50) / 100); }
        @Override public void unfixColor() { delegate.unfixColor(); }
    }
}
