package com.botfury;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class TrackingMathTest {
    @Test void oreJobsIncludeNamedResourcesAndDirectBlockSelections() {
        assertTrue(ResourceCatalog.isOre("iron"));
        assertTrue(ResourceCatalog.isOre("coal"));
        assertTrue(ResourceCatalog.isOre("block:minecraft:deepslate_iron_ore"));
        assertTrue(ResourceCatalog.isOre("block:minecraft:ancient_debris"));
        assertFalse(ResourceCatalog.isOre("any_stone"));
        assertFalse(ResourceCatalog.isOre("block:minecraft:cobblestone"));
    }

    @Test void projectsCenterAndClampsOffscreenAndBehindMarkers(){
        var center=MarkerProjection.project(0,0,1,640,480,24);assertEquals(320,center.x());assertEquals(240,center.y());assertFalse(center.edge());
        var right=MarkerProjection.project(10,0,1,640,480,24);assertTrue(right.edge());assertEquals(616,right.x());
        var behind=MarkerProjection.project(0,0,-1,640,480,24);assertTrue(behind.behind());assertEquals(456,behind.y());
        assertNull(MarkerProjection.project(Float.NaN,0,1,640,480,24));
    }
    @Test void onlyCancelsLocalCollisionWithKnownBots(){
        BotCollisionPolicy.replace(List.of("Bot01","Bot02"));
        assertTrue(BotCollisionPolicy.cancel(true,false,"Master","bot01"));
        assertTrue(BotCollisionPolicy.cancel(false,true,"Bot02","Master"));
        assertFalse(BotCollisionPolicy.cancel(true,false,"Master","OtherPlayer"));
        assertFalse(BotCollisionPolicy.cancel(false,false,"Bot01","Bot02"));
        BotCollisionPolicy.replace(List.of());
    }
    @Test void ironBothIncludesBothOreVariants(){
        assertEquals(List.of("minecraft:iron_ore","minecraft:deepslate_iron_ore"),ResourceCatalog.blocks("iron","both"));
        assertEquals(List.of("minecraft:deepslate_iron_ore"),ResourceCatalog.blocks("iron","deepslate"));
        assertThrows(IllegalArgumentException.class,()->ResourceCatalog.blocks("iron","invalid"));
        assertThrows(IllegalArgumentException.class,()->ResourceCatalog.blocks("unknown","both"));
    }
}
