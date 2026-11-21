package com.botfury;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import java.util.*;
import java.util.function.Consumer;

final class ItemPickerScreen extends Screen {
    private record Entry(Item item,String id,String name) {}
    private final Screen parent;
    private final Consumer<String> selected;
    private final boolean blocksOnly;
    private final List<Entry> all;
    private List<Entry> filtered=List.of();
    private TextFieldWidget search;
    private String category="All";
    private int offset;
    ItemPickerScreen(Screen parent,boolean blocksOnly,Consumer<String> selected){
        super(Text.literal(blocksOnly?"Choose mining block":"Choose item"));this.parent=parent;this.blocksOnly=blocksOnly;this.selected=selected;
        all=Registries.ITEM.getIds().stream().map(id->new Entry(Registries.ITEM.get(id),id.toString(),Registries.ITEM.get(id).getName().getString()))
            .filter(e->e.item!=Items.AIR&&(!blocksOnly||e.item instanceof BlockItem)).sorted(Comparator.comparing(Entry::name).thenComparing(Entry::id)).toList();
    }
    @Override protected void init(){
        search=new TextFieldWidget(textRenderer,132,42,width-152,20,Text.literal("Search items"));
        search.setPlaceholder(Text.literal("Search name or item ID"));
        search.setMaxLength(128);search.setChangedListener(value->filter());addDrawableChild(search);setInitialFocus(search);
        int y=42;for(String name:List.of("All","Tools","Wood","Stone","Ores","Food","Blocks")){
            addDrawableChild(ButtonWidget.builder(Text.literal(name),b->{category=name;offset=0;filter();}).dimensions(18,y,100,20).build());y+=24;
        }
        if(!blocksOnly)addDrawableChild(ButtonWidget.builder(Text.literal("Held item"),b->selected.accept(ClientActions.heldItem(client))).dimensions(width-110,14,92,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"),b->close()).dimensions(18,height-30,100,20).build());filter();
    }
    private void filter(){
        String query=search==null?"":search.getText().toLowerCase(Locale.ROOT);
        filtered=all.stream().filter(e->matchesCategory(e,category)&&(e.id.toLowerCase(Locale.ROOT).contains(query)||e.name.toLowerCase(Locale.ROOT).contains(query))).toList();offset=0;
    }
    private static boolean matchesCategory(Entry e,String category){
        String id=e.id;
        return switch(category){
            case "Tools"->e.item instanceof ToolItem||e.item instanceof ShearsItem||e.item instanceof FishingRodItem||e.item instanceof BowItem||e.item instanceof CrossbowItem||id.contains("brush")||id.contains("flint_and_steel");
            case "Wood"->List.of("oak","spruce","birch","jungle","acacia","mangrove","cherry","bamboo","crimson","warped","stick").stream().anyMatch(id::contains);
            case "Stone"->List.of("stone","deepslate","granite","diorite","andesite","tuff","calcite","basalt").stream().anyMatch(id::contains);
            case "Ores"->id.endsWith("_ore")||id.contains("ancient_debris")||id.contains("raw_");
            case "Food"->e.item.isFood();
            case "Blocks"->e.item instanceof BlockItem;
            default->true;
        };
    }
    private int rows(){return Math.max(1,(height-110)/24);}
    @Override public boolean shouldPause(){return false;}
    @Override public void render(DrawContext draw,int mouseX,int mouseY,float delta){
        draw.fill(0,0,width,height,0xEB101927);draw.drawCenteredTextWithShadow(textRenderer,title,width/2,16,0xFF5DE4D2);
        super.render(draw,mouseX,mouseY,delta);
        draw.drawText(textRenderer,category+" · "+filtered.size()+" items",132,67,0xFF9DB1C7,false);
        for(int row=0;row<rows()&&offset+row<filtered.size();row++){
            Entry e=filtered.get(offset+row);int y=82+row*24;boolean hover=mouseX>=130&&mouseX<width-18&&mouseY>=y&&mouseY<y+23;
            draw.fill(130,y,width-18,y+23,hover?0xFF275C58:0xB51C2B40);draw.drawItem(e.item.getDefaultStack(),136,y+3);
            draw.drawText(textRenderer,textRenderer.trimToWidth(e.name,width-182),158,y+3,0xFFE7F0FA,false);
            draw.drawText(textRenderer,textRenderer.trimToWidth(e.id,width-182),158,y+13,0xFF8FA6BE,false);
        }
        draw.drawText(textRenderer,"Scroll · click an item to select",132,height-23,0xFF8FA6BE,false);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(FormationClient.wheel.matchesMouse(button)){close();return true;}
        if(button==0&&x>=130&&x<width-18&&y>=82){int row=(int)(y-82)/24;if(row<rows()&&offset+row<filtered.size()){selected.accept(filtered.get(offset+row).id);return true;}}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double amount){offset=Math.max(0,Math.min(Math.max(0,filtered.size()-rows()),offset+(amount>0?-3:3)));return true;}
    @Override public void close(){client.setScreen(parent);}
}
