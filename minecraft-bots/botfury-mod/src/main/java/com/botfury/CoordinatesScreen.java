package com.botfury;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
final class CoordinatesScreen extends FleetPanel {
    private final Screen parent;private TextFieldWidget x,y,z;private String error="";
    CoordinatesScreen(Screen parent){super(Text.literal("Baritone goto"));this.parent=parent;}
    @Override public boolean shouldPause(){return false;}
    @Override protected void initPanel(){
        int left=width/2-150;var pos=client.player.getBlockPos();
        x=new TextFieldWidget(textRenderer,left,80,95,22,Text.literal("X"));y=new TextFieldWidget(textRenderer,left+102,80,95,22,Text.literal("Y"));z=new TextFieldWidget(textRenderer,left+204,80,95,22,Text.literal("Z"));
        x.setText(""+pos.getX());y.setText(""+pos.getY());z.setText(""+pos.getZ());addDrawableChild(x);addDrawableChild(y);addDrawableChild(z);
        addDrawableChild(ButtonWidget.builder(Text.literal("Go"),b->{try{int a=Integer.parseInt(x.getText()),h=Integer.parseInt(y.getText()),c=Integer.parseInt(z.getText());if(Math.abs((long)a)>30000000||Math.abs((long)c)>30000000||h< -128||h>512)throw new NumberFormatException();BotTracker.sendSelected("baritone:goto "+a+" "+h+" "+c);client.setScreen(null);}catch(NumberFormatException e){error="Enter valid integer coordinates";}}).dimensions(width/2-100,125,95,22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"),b->close()).dimensions(width/2+5,125,95,22).build());
    }
    @Override protected void renderPanel(DrawContext draw,int mx,int my,float delta){draw.fill(0,0,width,height,BotUiTheme.PANEL_TOP);draw.drawCenteredTextWithShadow(textRenderer,title,width/2,24,BotUiTheme.ACCENT);draw.drawCenteredTextWithShadow(textRenderer,"X                         Y                         Z",width/2,66,BotUiTheme.MUTED);draw.drawCenteredTextWithShadow(textRenderer,error.isEmpty()?BotTracker.targetLabel():error,width/2,165,BotUiTheme.ACCENT);super.renderPanel(draw,mx,my,delta);}
    @Override protected boolean panelMouseClicked(double x,double y,int button){
        if(FormationClient.wheel.matchesMouse(button)){close();return true;}
        return super.panelMouseClicked(x,y,button);
    }
    @Override public void close(){client.setScreen(parent);}
}
