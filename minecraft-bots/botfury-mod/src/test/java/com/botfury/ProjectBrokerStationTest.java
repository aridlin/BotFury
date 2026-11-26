package com.botfury;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectBrokerStationTest {
    @Test void aCraftingShortcutRequiresLocalAccessOrAPortableTable() {
        var station = new BlockPos(-7, 124, 11);
        var stranded = new BlockPos(-107, 63, 35);
        assertFalse(ProjectBroker.craftingStationWithinReach(stranded, station, false));
        assertFalse(ProjectBroker.craftingStationWithinReach(station.down(7), station, false));
        assertFalse(ProjectBroker.craftingStationWithinReach(station.east(25), station, false));
        assertTrue(ProjectBroker.craftingStationWithinReach(station.east(24), station, false));
        assertTrue(ProjectBroker.craftingStationWithinReach(stranded, station, true));
    }
}
