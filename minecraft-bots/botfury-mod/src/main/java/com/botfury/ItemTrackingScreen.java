package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.registry.Registries;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

final class ItemTrackingScreen extends Screen {
    private final Screen parent;
    ItemTrackingScreen(Screen parent){super(Text.literal("Track item"));this.parent=parent;}
    @Override public boolean shouldPause(){return false;}
    @Override protected void init(){
        int x=width/2-100;
        addDrawableChild(ButtonWidget.builder(Text.literal("Choose item…"),b->client.setScreen(new ItemPickerScreen(this,false,id->{BotUiConfig.trackedItem=id;BotUiConfig.save();client.setScreen(this);}))).dimensions(x,110,200,22).build());
        var clear=addDrawableChild(ButtonWidget.builder(Text.literal("Stop tracking"),b->{BotUiConfig.trackedItem="";BotUiConfig.save();clearAndInit();}).dimensions(x,140,200,22).build());
        clear.active=!BotUiConfig.trackedItem.isEmpty();
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"),b->close()).dimensions(x,height-35,200,22).build());
    }
    @Override public void render(DrawContext draw,int x,int y,float delta){
        draw.fill(0,0,width,height,0xED101927);
        draw.drawCenteredTextWithShadow(textRenderer,title,width/2,18,0xFF5DE4D2);
        var id=Identifier.tryParse(BotUiConfig.trackedItem);
        var item=id!=null&&Registries.ITEM.containsId(id)?Registries.ITEM.get(id):Items.AIR;
        if(item!=Items.AIR){draw.drawItem(item.getDefaultStack(),width/2-8,55);draw.drawCenteredTextWithShadow(textRenderer,item.getName(),width/2,80,0xFFE7F0FA);}
        else draw.drawCenteredTextWithShadow(textRenderer,"Off",width/2,70,0xFF9DB1C7);
        super.render(draw,x,y,delta);
    }
    @Override public boolean mouseClicked(double x,double y,int button){if(FormationClient.wheel.matchesMouse(button)){close();return true;}return super.mouseClicked(x,y,button);}
    @Override public void close(){client.setScreen(parent);}
}
