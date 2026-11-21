package com.botfury;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.List;

final class MiningScreen extends Screen {
    private final Screen parent;
    private String category="",resource="";
    private int offset;
    private String error="";
    MiningScreen(Screen parent){super(Text.literal("Coordinated mining"));this.parent=parent;}
    @Override public boolean shouldPause(){return false;}
    @Override protected void init(){rebuild();}
    private void rebuild(){
        clearChildren();int x=width/2-150,y=65;
        if(category.isEmpty())for(String name:List.of("Ores","Wood","Stone","Choose another block")){
            addDrawableChild(ButtonWidget.builder(Text.literal(name),b->{if(name.equals("Choose another block"))client.setScreen(new ItemPickerScreen(this,true,id->start("block:"+id,"normal")));else{category=name;offset=0;rebuild();}}).dimensions(x,y,300,24).build());y+=30;
        }else if(!resource.isEmpty()){
            var entry=ResourceCatalog.RESOURCES.stream().filter(r->r.id().equals(resource)).findFirst().orElseThrow();
            for(String choice:List.of("both","normal","deepslate")){
                if(choice.equals("deepslate")&&entry.deepslate().isEmpty())continue;
                String label=choice.equals("both")?"Both normal + deepslate":choice.equals("normal")?"Normal only":"Deepslate only";
                addDrawableChild(ButtonWidget.builder(Text.literal(label),b->start(resource,choice)).dimensions(x,y,300,24).build());y+=30;
            }
        }else{
            var resources=ResourceCatalog.RESOURCES.stream().filter(r->r.category().equals(category)).toList();
            int rows=Math.max(1,(height-130)/28);
            for(int i=offset;i<resources.size()&&i<offset+rows;i++){
                var value=resources.get(i);
                addDrawableChild(ButtonWidget.builder(Text.literal(value.label()),b->{if(value.deepslate().isEmpty())start(value.id(),"normal");else{resource=value.id();rebuild();}}).dimensions(x,y,300,24).build());y+=28;
            }
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"),b->{if(!resource.isEmpty()){resource="";rebuild();}else if(!category.isEmpty()){category="";rebuild();}else close();}).dimensions(width/2-70,height-35,140,22).build());
    }
    private void start(String id,String variant){
        String command="mining:"+id+"|"+variant+"|"+BotFuryMod.controlPort();
        if(BotTracker.sendSelected(command)>0)client.setScreen(null);
        else error="No compatible live bots. Update selected bots to 0.3.1 and retry.";
    }
    @Override public void render(DrawContext draw,int x,int y,float delta){
        draw.fill(0,0,width,height,0xED101927);draw.drawCenteredTextWithShadow(textRenderer,title,width/2,18,0xFF5DE4D2);
        String path="Mine"+(category.isEmpty()?"":" / "+category)+(resource.isEmpty()?"":" / "+ResourceCatalog.title(resource));
        draw.drawCenteredTextWithShadow(textRenderer,path+" · "+BotTracker.targetLabel(),width/2,37,0xFFC0CEDD);
        if(!error.isEmpty())draw.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth(error,width-20),width/2,height-55,0xFFFFC35C);
        super.render(draw,x,y,delta);
    }
    @Override public boolean mouseScrolled(double x,double y,double amount){if(!category.isEmpty()&&resource.isEmpty()){int size=(int)ResourceCatalog.RESOURCES.stream().filter(r->r.category().equals(category)).count();offset=Math.max(0,Math.min(Math.max(0,size-Math.max(1,(height-130)/28)),offset+(amount>0?-2:2)));rebuild();}return true;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(FormationClient.wheel.matchesMouse(button)){close();return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public void close(){client.setScreen(parent);}
}
