package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import net.minecraft.util.math.Vec3d;

final class BotMarkers {
    private static final int ITEMS_PER_ROW = 3;
    private record ItemCount(net.minecraft.item.Item item, String label, int width) {}
    private static Matrix4f matrix;
    private static Vec3d camera;
    static void register(){WorldRenderEvents.START.register(context->{
        if(context.matrixStack()!=null&&context.projectionMatrix()!=null&&context.camera()!=null){matrix=new Matrix4f(context.projectionMatrix()).mul(context.matrixStack().peek().getPositionMatrix());camera=context.camera().getPos();}
    });}
    static void render(DrawContext draw,float delta){
        var client=MinecraftClient.getInstance();
        if(!BotUiConfig.esp||client.player==null||client.world==null||client.options.hudHidden||client.currentScreen!=null||matrix==null||camera==null||Boolean.getBoolean("botfury.managedHttp"))return;
        int width=draw.getScaledWindowWidth(),height=draw.getScaledWindowHeight();
        float hudScale = FleetPanel.scaleForWindow(client.getWindow());
        float labelScale = hudScale * .78f;
        int hudRight = (int) (212 * hudScale);
        int hudBottom = (int) ((84 + ((BotUiConfig.displayedItems().size() + 2) / 3) * 22) * hudScale);
        var tracked = BotUiConfig.displayedItems();
        int itemHeight = ((tracked.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW) * 18;
        int labelHeight = 24 + itemHeight;
        int heightScaled = (int)Math.ceil(labelScale * labelHeight);

        var occupied=new java.util.ArrayList<int[]>();
        for(var bot:BotTracker.all()){
            long age=System.currentTimeMillis()-bot.positionAt();
            if(!bot.sameWorld())continue;
            var entity=client.world.getPlayers().stream()
                    .filter(player->player!=client.player&&!player.isRemoved()
                            &&player.getGameProfile().getName().equalsIgnoreCase(bot.name()))
                    .findFirst().orElse(null);
            if(entity==null&&(bot.positionAt()==0||age>30000))continue;
            // Use Minecraft's per-frame entity interpolation whenever the bot is loaded.
            // HTTP reports remain the fallback for bots outside entity tracking range.
            Vec3d position=entity!=null?entity.getLerpedPos(delta):new Vec3d(bot.x(),bot.y(),bot.z());
            double markerHeight=entity!=null?entity.getHeight()+0.2:2.0;
            boolean live=entity!=null||(bot.fresh()&&bot.connected());
            boolean alive=entity!=null?entity.isAlive():bot.alive();
            float health=entity!=null?entity.getHealth():bot.health();
            Vector4f clip=matrix.transform(new Vector4f((float)(position.x-camera.x),(float)(position.y+markerHeight-camera.y),(float)(position.z-camera.z),1));
            var point=MarkerProjection.project(clip.x,clip.y,clip.w,width,height,25);
            if(point==null)continue;
            int x=(int)point.x(),y=(int)point.y();
            if(point.edge()&&Math.abs(x-width/2)<140&&y>height-80)y=height-80;
            int color=!live?BotUiTheme.MUTED:!alive?BotUiTheme.DANGER:entity==null&&age>2000?BotUiTheme.ACCENT:0xFF000000 | WorldOverlays.pathColor(bot.port());
            if (bot.task().equals("orbit")) {
                if (!point.edge()) {
                    float radius = Math.max(1.2f, 3.2f * hudScale);
                    BotGeometry.circle(draw, x, y, radius + .8f, DitherPress.INK);
                    BotGeometry.circle(draw, x, y, radius, color);
                }
                continue;
            }
            if (point.edge()) {
                double angle = Math.atan2(y - height / 2.0, x - width / 2.0);
                float size = Math.max(4.5f, 10f * hudScale);
                BotGeometry.arrow(draw, x, y, angle, size + 1.5f, DitherPress.INK);
                BotGeometry.arrow(draw, x, y, angle, size, color);
                continue;
            }
            float radius = Math.max(1f, 2.5f * hudScale);
            BotGeometry.circle(draw, x, y, radius + .9f, DitherPress.INK);
            BotGeometry.circle(draw, x, y, radius, color);
            String name=bot.name()+" · "+Math.round(position.distanceTo(client.player.getLerpedPos(delta)))+"m";
            String task=bot.mining().isEmpty()?(bot.inventory().isEmpty()?bot.mode():bot.inventory()):bot.mining();
            String info=live?(alive?Math.round(health)+" HP · "+task:"Respawning"):"Last known "+Math.max(0,age/1000)+"s";
            var counts = new java.util.ArrayList<ItemCount>();
            int maxRow = 0, rowWidth = 0;
            for (var item : tracked) {
                String count = bot.items() == null ? "?" : Integer.toString(item.count(bot.items()));
                if (bot.items() != null && (!bot.fresh() || !bot.connected())) count = "~" + count;
                if (counts.size() % ITEMS_PER_ROW == 0) rowWidth = 0;
                int cellWidth = 20 + client.textRenderer.getWidth(count) + 4;
                counts.add(new ItemCount(net.minecraft.registry.Registries.ITEM.get(new net.minecraft.util.Identifier(item.icon())),
                        count, cellWidth));
                rowWidth += cellWidth;
                maxRow = Math.max(maxRow, rowWidth);
            }
            int w = Math.max(Math.max(Math.min(155, client.textRenderer.getWidth(name) + 8),
                    Math.min(155, client.textRenderer.getWidth(info))), maxRow) + 10;
            int widthScaled = (int)Math.ceil(labelScale * w);
            int labelX = Math.max(5, Math.min(width - widthScaled - 5, x - widthScaled / 2));
            int preferredY = y - heightScaled - 6;
            int labelY = Math.max(5, Math.min(height - heightScaled - 5, preferredY));
            if (BotUiConfig.hud && labelX < hudRight && labelY < hudBottom) labelY = hudBottom;
            for (int attempt = 0; attempt < 8; attempt++) {
                boolean collision = false;
                for (int[] other : occupied) {
                    if (labelX < other[0] + other[2] + 3 && labelX + widthScaled + 3 > other[0]
                            && labelY < other[1] + other[3] + 3 && labelY + heightScaled + 3 > other[1]) {
                        collision = true;
                        break;
                    }
                }
                if (!collision) break;
                labelY = labelY + heightScaled + 3 < height - heightScaled - 5 ? labelY + heightScaled + 3
                        : Math.max(hudBottom, labelY - 2 * heightScaled - attempt * 12);
            }
            occupied.add(new int[]{labelX, labelY, widthScaled, heightScaled});
            var matrices = draw.getMatrices();
            matrices.push();
            matrices.translate(labelX, labelY, 0);
            matrices.scale(labelScale, labelScale, 1);
            BotUiTheme.label(draw, 0, 0, w, labelHeight);
            for (int i = 0; i < counts.size(); i += ITEMS_PER_ROW) {
                var row = counts.subList(i, Math.min(counts.size(), i + ITEMS_PER_ROW));
                int rowX = (w - row.stream().mapToInt(ItemCount::width).sum()) / 2;
                int rowY = 1 + (i / ITEMS_PER_ROW) * 18;
                for (var entry : row) {
                    draw.drawItem(entry.item.getDefaultStack(), rowX, rowY);
                    draw.drawText(client.textRenderer, entry.label, rowX + 18, rowY + 4, BotUiTheme.TEXT, false);
                    rowX += entry.width;
                }
            }
            BotGeometry.circle(draw, 7, itemHeight + 7, 2f, color);
            scrollLine(draw, client.textRenderer, name, 13, itemHeight + 3, w - 18, BotUiTheme.TEXT, labelX, labelY, labelScale);
            scrollLine(draw, client.textRenderer, info, 5, itemHeight + 13, w - 10, BotUiTheme.MUTED, labelX, labelY, labelScale);
            matrices.pop();
        }
    }

    private static void scrollLine(DrawContext draw, net.minecraft.client.font.TextRenderer font,
                                   String text, int x, int y, int room, int color, int left, int top, float scale) {
        int overflow = font.getWidth(text) - room;
        if (overflow <= 0) {
            draw.drawText(font, text, x, y, color, false);
            return;
        }
        long travel = overflow * 45L;
        long pause = 1000;
        long phase = net.minecraft.util.Util.getMeasuringTimeMs() % (2 * (travel + pause));
        long distance = Math.min(phase, 2 * (travel + pause) - phase);
        int shift = (int) Math.min(overflow, Math.max(0, distance - pause) / 45L);
        draw.enableScissor(left + (int) Math.floor(x * scale), top + (int) Math.floor(y * scale),
                left + (int) Math.ceil((x + room) * scale), top + (int) Math.ceil((y + font.fontHeight) * scale));
        draw.drawText(font, text, x - shift, y, color, false);
        draw.disableScissor();
    }
}
