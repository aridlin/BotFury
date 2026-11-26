package com.botfury;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatherProgressTest {
    @Test void walkingBackAndForthDoesNotKeepAGatherJobAlive() {
        var progress = new GatherProgress();
        assertTrue(progress.active(-1, 120, 35, true, 0, 100, 90));
        assertTrue(progress.active(18, 110, 39, true, 0, 140, 90));
        assertTrue(progress.active(-1, 112, 35, true, 0, 200, 90));
        assertFalse(progress.active(18, 108, 39, true, 0, 230, 90));
        assertTrue(progress.active(18, 108, 39, true, 1, 231, 90));
        assertFalse(progress.active(18, 108, 39, true, 1, 321, 90));
    }

    @Test void descendingAndEnteringNewTerrainContinueToCountAsProgress() {
        var progress = new GatherProgress();
        assertTrue(progress.active(1, 16, 1, false, 0, 100, 90));
        assertTrue(progress.active(1, 7, 1, false, 0, 180, 90));
        assertTrue(progress.active(1, -1, 1, false, 0, 260, 90));
        assertTrue(progress.active(17, -1, 1, false, 0, 340, 90));
        progress.reset();
        assertTrue(progress.active(17, -1, 1, false, 0, 500, 90));
    }
}
