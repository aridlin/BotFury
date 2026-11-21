package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import net.minecraft.util.math.Vec3d;

final class BotMarkers {
    private static Matrix4f matrix;
    private static Vec3d camera;
    static void register(){WorldRenderEvents.START.register(context->{
        if(context.matrixStack()!=null&&context.projectionMatrix()!=null&&context.camera()!=null){matrix=new Matrix4f(context.projectionMatrix()).mul(context.matrixStack().peek().getPositionMatrix());camera=context.camera().getPos();}
    });}
    static void render(DrawContext draw,float delta){
        var client=MinecraftClient.getInstance();
        if(!BotUiConfig.esp||client.player==null||client.world==null||client.options.hudHidden||client.currentScreen!=null||matrix==null||camera==null||Boolean.getBoolean("botfury.managedHttp"))return;
        int width=draw.getScaledWindowWidth(),height=draw.getScaledWindowHeight(),index=0;
        var trackedId=net.minecraft.util.Identifier.tryParse(BotUiConfig.trackedItem);
        var tracked=trackedId!=null&&net.minecraft.registry.Registries.ITEM.containsId(trackedId)
                ?net.minecraft.registry.Registries.ITEM.get(trackedId):net.minecraft.item.Items.AIR;
        boolean showItem=tracked!=net.minecraft.item.Items.AIR;
        int itemHeight=showItem?20:0,labelHeight=27+itemHeight;
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
            String name=bot.name()+" · "+Math.round(position.distanceTo(client.player.getLerpedPos(delta)))+"m";
            String task=bot.mining().isEmpty()?(bot.inventory().isEmpty()?bot.mode():bot.inventory()):bot.mining();
            String info=live?(alive?Math.round(health)+" HP · "+client.textRenderer.trimToWidth(task,140):"Respawning"):"Last known "+Math.max(0,age/1000)+"s";
            if(point.edge()){
                double angle=Math.atan2(y-height/2.0,x-width/2.0);
                line(draw,x,y,x-(int)(9*Math.cos(angle-.6)),y-(int)(9*Math.sin(angle-.6)),color);
                line(draw,x,y,x-(int)(9*Math.cos(angle+.6)),y-(int)(9*Math.sin(angle+.6)),color);
            }else {draw.fill(x-2,y-2,x+2,y+2,color);}
            String count=bot.items()==null?"?":Integer.toString(bot.items().getOrDefault(BotUiConfig.trackedItem,0));
            if(bot.items()!=null&&(!bot.fresh()||!bot.connected()))count="~"+count;
            int w=Math.max(Math.max(client.textRenderer.getWidth(name),client.textRenderer.getWidth(info)),showItem?22+client.textRenderer.getWidth(count):0)+12;
            int labelX=Math.max(5,Math.min(width-w-5,x-w/2));
            int preferredY=point.edge()?y+8+(index++%3)*4:y-labelHeight-14;
            int labelY=Math.max(5,Math.min(height-labelHeight-5,preferredY));
            if(labelX<238&&labelY<88)labelY=90;
            for(int attempt=0;attempt<8;attempt++){
                boolean collision=false;
                for(int[] other:occupied)if(labelX<other[0]+other[2]+3&&labelX+w+3>other[0]&&labelY<other[1]+labelHeight+3&&labelY+labelHeight+3>other[1]){collision=true;break;}
                if(!collision)break;
                labelY=labelY+labelHeight+3<height-labelHeight-5?labelY+labelHeight+3:Math.max(92,labelY-2*labelHeight-attempt*20);
            }
            occupied.add(new int[]{labelX,labelY,w});
            draw.fill(labelX,labelY,labelX+w,labelY+labelHeight,0xD5111926);
            draw.fill(labelX,labelY,labelX+2,labelY+labelHeight,color);
            if(showItem){
                int rowX=labelX+(w-22-client.textRenderer.getWidth(count))/2;
                draw.drawItem(tracked.getDefaultStack(),rowX,labelY+2);
                draw.drawText(client.textRenderer,count,rowX+21,labelY+6,color,false);
            }
            draw.drawText(client.textRenderer,name,labelX+6,labelY+itemHeight+4,color,false);
            draw.drawText(client.textRenderer,info,labelX+6,labelY+itemHeight+15,0xFFB6C5D7,false);
        }
    }
    private static void line(DrawContext draw,int x1,int y1,int x2,int y2,int color){
        int steps=Math.max(Math.abs(x2-x1),Math.abs(y2-y1));
        for(int i=0;i<=steps;i++){int x=x1+(x2-x1)*i/Math.max(1,steps),y=y1+(y2-y1)*i/Math.max(1,steps);draw.fill(x,y,x+2,y+2,color);}
    }
}
