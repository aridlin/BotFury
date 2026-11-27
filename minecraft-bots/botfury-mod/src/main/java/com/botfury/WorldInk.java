package com.botfury;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

/** A separate translucent layer for BotFury schematic previews. */
abstract class WorldInk extends RenderLayer {
    private static RenderLayer layer;

    private WorldInk() {
        super("botfury_world_ink", VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS,
                256, false, true, () -> {}, () -> {});
    }

    static RenderLayer layer() {
        if (layer == null) {
            layer = RenderLayer.of("botfury_world_ink", VertexFormats.POSITION_TEXTURE_COLOR,
                    VertexFormat.DrawMode.QUADS, 256, MultiPhaseParameters.builder()
                            .program(new ShaderProgram(GameRenderer::getPositionTexColorProgram))
                            .texture(new Texture(DitherPress.worldPlate(), false, false))
                            .cull(DISABLE_CULLING)
                            .transparency(TRANSLUCENT_TRANSPARENCY)
                            .writeMaskState(COLOR_MASK)
                            .build(false));
        }
        return layer;
    }
}
