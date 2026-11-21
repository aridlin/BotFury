package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import net.minecraft.util.math.Vec3d;

final class BotMarkers {
    private static final float LABEL_SCALE = 0.75f;
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
        int width=draw.getScaledWindowWidth(),height=draw.getScaledWindowHeight(),index=0;
        var tracked = BotUiConfig.trackedItems;
        int itemHeight = ((tracked.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW) * 18;
        int labelHeight = 24 + itemHeight;
        int heightScaled = (int)Math.ceil(LABEL_SCALE * labelHeight);
        boolean saturn = LocalFormation.mode().equals("orbit");
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
            int color=!live?0xFF8995A5:!alive?0xFFFF7777:entity==null&&age>2000?0xFFFFC35C:0xFF5DE4D2;
            if (saturn) {
                draw.fill(x - 1, y - 2, x + 2, y + 3, color);
                draw.fill(x - 2, y - 1, x + 3, y + 2, color);
                continue;
            }
            String name=bot.name()+" · "+Math.round(position.distanceTo(client.player.getLerpedPos(delta)))+"m";
            String task=bot.mining().isEmpty()?(bot.inventory().isEmpty()?bot.mode():bot.inventory()):bot.mining();
            String info=live?(alive?Math.round(health)+" HP · "+client.textRenderer.trimToWidth(task,140):"Respawning"):"Last known "+Math.max(0,age/1000)+"s";
            if(point.edge()){
                double angle=Math.atan2(y-height/2.0,x-width/2.0);
                line(draw,x,y,x-(int)(6*Math.cos(angle-.6)),y-(int)(6*Math.sin(angle-.6)),color);
                line(draw,x,y,x-(int)(6*Math.cos(angle+.6)),y-(int)(6*Math.sin(angle+.6)),color);
            }else {draw.fill(x-1,y-1,x+2,y+2,color);}
            var counts = new java.util.ArrayList<ItemCount>();
            int maxRow = 0, rowWidth = 0;
            for (String id : tracked) {
                String count = bot.items() == null ? "?" : Integer.toString(bot.items().getOrDefault(id, 0));
                if (bot.items() != null && (!bot.fresh() || !bot.connected())) count = "~" + count;
                if (counts.size() % ITEMS_PER_ROW == 0) rowWidth = 0;
                int cellWidth = 20 + client.textRenderer.getWidth(count) + 4;
                counts.add(new ItemCount(net.minecraft.registry.Registries.ITEM.get(new net.minecraft.util.Identifier(id)),
                        count, cellWidth));
                rowWidth += cellWidth;
                maxRow = Math.max(maxRow, rowWidth);
            }
            int w = Math.max(Math.max(client.textRenderer.getWidth(name), client.textRenderer.getWidth(info)), maxRow) + 10;
            int widthScaled = (int)Math.ceil(LABEL_SCALE * w);
            int labelX = Math.max(5, Math.min(width - widthScaled - 5, x - widthScaled / 2));
            int preferredY = point.edge() ? y + 5 + (index++ % 3) * 3 : y - heightScaled - 6;
            int labelY = Math.max(5, Math.min(height - heightScaled - 5, preferredY));
            if (labelX < 238 && labelY < 88) labelY = 90;
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
                        : Math.max(92, labelY - 2 * heightScaled - attempt * 12);
            }
            occupied.add(new int[]{labelX, labelY, widthScaled, heightScaled});
            var matrices = draw.getMatrices();
            matrices.push();
            matrices.translate(labelX, labelY, 0);
            matrices.scale(LABEL_SCALE, LABEL_SCALE, 1);
            draw.fill(0, 0, w, labelHeight, 0xD5111926);
            draw.fill(0, 0, 2, labelHeight, color);
            for (int i = 0; i < counts.size(); i += ITEMS_PER_ROW) {
                var row = counts.subList(i, Math.min(counts.size(), i + ITEMS_PER_ROW));
                int rowX = (w - row.stream().mapToInt(ItemCount::width).sum()) / 2;
                int rowY = 1 + (i / ITEMS_PER_ROW) * 18;
                for (var entry : row) {
                    draw.drawItem(entry.item.getDefaultStack(), rowX, rowY);
                    draw.drawText(client.textRenderer, entry.label, rowX + 18, rowY + 4, color, false);
                    rowX += entry.width;
                }
            }
            draw.drawText(client.textRenderer, name, 5, itemHeight + 3, color, false);
            draw.drawText(client.textRenderer, info, 5, itemHeight + 13, 0xFFB6C5D7, false);
            matrices.pop();
        }
    }
    private static void line(DrawContext draw,int x1,int y1,int x2,int y2,int color){
        int steps=Math.max(Math.abs(x2-x1),Math.abs(y2-y1));
        for(int i=0;i<=steps;i++){int x=x1+(x2-x1)*i/Math.max(1,steps),y=y1+(y2-y1)*i/Math.max(1,steps);draw.fill(x,y,x+2,y+2,color);}
    }
}
