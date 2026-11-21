package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.List;

final class BotRosterScreen extends Screen {
    private final Screen parent;
    private int offset;
    BotRosterScreen(Screen parent){super(Text.literal("Bot fleet"));this.parent=parent;}
    @Override public boolean shouldPause(){return false;}
    @Override protected void init(){buttons();BotTracker.refresh();}
    private void buttons(){
        clearChildren();
        addDrawableChild(ButtonWidget.builder(Text.literal("Defense: "+(BotUiConfig.defense?"on":"off")),b->{BotUiConfig.defense=!BotUiConfig.defense;BotUiConfig.save();BotTracker.sendDefenses();buttons();}).dimensions(18,14,110,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Track item…"),b->client.setScreen(new ItemTrackingScreen(this))).dimensions(width-128,14,110,20).build());
        int x=width/2+8,w=Math.max(60,(width/2-44)/2);
        addDrawableChild(ButtonWidget.builder(Text.literal("All bots"),b->{BotTracker.select(-1);buttons();}).dimensions(18,42,100,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"),b->BotTracker.refresh()).dimensions(124,42,85,20).build());
        String[] labels={"Recall","Hold / Stop","Resume","Toggle render","Bring item","Keep one","Collect one","Mine…"};
        for(int i=0;i<labels.length;i++){
            int action=i;
            addDrawableChild(ButtonWidget.builder(Text.literal(labels[i]),b->{
                if(action==0)BotTracker.manageSelected("recall");
                else if(action==1)BotTracker.manageSelected("hold");
                else if(action==2)BotTracker.manageSelected("resume");
                else if(action==3)for(var bot:BotTracker.targets())BotTracker.command(bot.port(),"toggle_render");
                else if(action==7)client.setScreen(new MiningScreen(this));
                else ClientActions.inventory(client,action==4?"deliver":action==5?"keep":"collect",ClientActions.taskItem(client),1);
            }).dimensions(x+(i%2)*(w+6),Math.min(205,Math.max(110,height-140))+(i/2)*26,w,22).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Markers: "+(BotUiConfig.esp?"on":"off")),b->{BotUiConfig.toggleEsp();buttons();}).dimensions(18,height-30,94,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Item…"),b->client.setScreen(new ItemPickerScreen(this,false,id->{BotUiConfig.item=id;BotUiConfig.save();client.setScreen(this);}))).dimensions(width/2-40,height-30,80,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"),b->close()).dimensions(width-110,height-30,92,20).build());
    }
    private int rows(){return Math.max(1,(height-110)/35);}
    @Override public void render(DrawContext draw,int mx,int my,float delta){
        draw.fill(0,0,width,height,0xED101927);draw.drawCenteredTextWithShadow(textRenderer,"BOT FLEET",width/2,16,0xFF5DE4D2);
        var list=BotTracker.all();int leftWidth=width/2-32;
        for(int i=0;i<rows()&&i+offset<list.size();i++){
            var bot=list.get(i+offset);int y=72+i*35;boolean selected=BotTracker.selected()==bot.port();
            draw.fill(18,y,18+leftWidth,y+33,selected?0xFF275C58:0xC51B2B40);
            int color=bot.fresh()?bot.alive()?0xFF5DE4D2:0xFFFF7777:0xFF8B9AAD;
            String line=bot.name()+(bot.sameWorld()?" · "+Math.round(bot.distance())+"m":" · other world");
            draw.drawText(textRenderer,textRenderer.trimToWidth(line,leftWidth-12),24,y+5,color,false);
            draw.drawText(textRenderer,textRenderer.trimToWidth(bot.summary(),leftWidth-12),24,y+19,0xFFB1C2D5,false);
        }
        if(list.isEmpty())draw.drawText(textRenderer,"Waiting for local managed bots…",20,80,0xFF9CAFC5,false);
        int x=width/2+8;
        var bot=BotTracker.get(BotTracker.selected());
        draw.drawText(textRenderer,BotTracker.targetLabel(),x,45,0xFF5DE4D2,false);
        if(bot!=null){
            String[] details={Math.round(bot.health())+" / "+Math.round(bot.maxHealth())+" HP · "+bot.mode(),
                    bot.summary(),"Position: "+Math.round(bot.x())+", "+Math.round(bot.y())+", "+Math.round(bot.z()),
                    bot.dimension().replace("minecraft:",""),"Build "+bot.build(),"Control: "+LocalFormation.overrideLabel(bot.port())};
            for(int i=0;i<(height<360?3:details.length);i++)draw.drawText(textRenderer,textRenderer.trimToWidth(details[i],width-x-20),x,66+i*(height<360?12:18),0xFFC0CEDD,false);
        }else draw.drawText(textRenderer,"Actions apply to the tracked fleet",x,72,0xFFC0CEDD,false);
        if(height>=360)draw.drawText(textRenderer,textRenderer.trimToWidth("Item: "+ClientActions.taskItem(client).replace("minecraft:",""),width-x-20),x,184,0xFF8FA6BE,false);
        super.render(draw,mx,my,delta);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(FormationClient.wheel.matchesMouse(button)){close();return true;}
        var list=BotTracker.all();int index=(int)(y-72)/35+offset;
        if(button==0&&x>=18&&x<width/2-14&&y>=72&&index>=offset&&index<list.size()&&index-offset<rows()){BotTracker.select(list.get(index).port());buttons();return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double amount){offset=Math.max(0,Math.min(Math.max(0,BotTracker.all().size()-rows()),offset+(amount>0?-2:2)));return true;}
    @Override public void close(){client.setScreen(parent);}
}
