package com.botfury;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectBrokerStationTest {
    @Test void aCraftingShortcutRequiresLocalAccessOrAPortableTable() {
        var station = new BlockPos(-7, 124, 11);
        var stranded = new BlockPos(-107, 63, 35);
        assertFalse(ProjectBroker.craftingStationIsLocal(stranded, station, false));
        assertFalse(ProjectBroker.craftingStationIsLocal(station.down(7), station, false));
        assertFalse(ProjectBroker.craftingStationIsLocal(station.east(25), station, false));
        assertTrue(ProjectBroker.craftingStationIsLocal(station.east(24), station, false));
        assertTrue(ProjectBroker.craftingStationIsLocal(stranded, station, true));
    }
}
