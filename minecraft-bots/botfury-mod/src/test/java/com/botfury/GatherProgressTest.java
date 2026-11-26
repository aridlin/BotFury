package com.botfury;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatherProgressTest {
    @Test void aSlowSurfaceClimbContinuesWhileHeightIncreases() {
        var progress = new GatherProgress();
        assertTrue(progress.active(3, 55, 3, true, true, 0, 100, 90));
        assertTrue(progress.active(4, 56, 3, true, true, 0, 180, 90));
        assertTrue(progress.active(5, 57, 3, true, true, 0, 260, 90));
        assertTrue(progress.active(4, 56, 3, true, true, 0, 310, 90));
        assertFalse(progress.active(5, 57, 3, true, true, 0, 350, 90));
    }

    @Test void walkingBackAndForthDoesNotKeepAGatherJobAlive() {
        var progress = new GatherProgress();
        assertTrue(progress.active(-1, 120, 35, true, false, 0, 100, 90));
        assertTrue(progress.active(18, 110, 39, true, false, 0, 140, 90));
        assertTrue(progress.active(-1, 112, 35, true, false, 0, 200, 90));
        assertFalse(progress.active(18, 108, 39, true, false, 0, 230, 90));
        assertTrue(progress.active(18, 108, 39, true, false, 1, 231, 90));
        assertFalse(progress.active(18, 108, 39, true, false, 1, 321, 90));
    }

    @Test void descendingAndEnteringNewTerrainContinueToCountAsProgress() {
        var progress = new GatherProgress();
        assertTrue(progress.active(1, 16, 1, false, false, 0, 100, 90));
        assertTrue(progress.active(1, 7, 1, false, false, 0, 180, 90));
        assertTrue(progress.active(1, -1, 1, false, false, 0, 260, 90));
        assertTrue(progress.active(17, -1, 1, false, false, 0, 340, 90));
        progress.reset();
        assertTrue(progress.active(17, -1, 1, false, false, 0, 500, 90));
    }
}
