package com.botfury;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WheelSoftBodyTest {
    @Test void compressedWedgeOvershootsThenSettlesAtTheOriginalRadius() {
        var wedge = new WheelSoftBody();
        wedge.reset(0);
        int middle = WheelSoftBody.POINTS / 2;
        float peak = 0;
        for (int frame = 0; frame < 120; frame++) {
            wedge.advance(1.0 / 60, 0, 0);
            peak = Math.max(peak, wedge.radius(WheelSoftBody.OUTER, middle));
        }
        assertTrue(peak > 1.03f);
        assertEquals(1, wedge.radius(WheelSoftBody.OUTER, middle), .001);
        assertEquals(.32, wedge.radius(WheelSoftBody.INNER, middle), .001);
    }

    @Test void pullDeformsNearbyMassesAndKeepsTheAngularBoundariesPinned() {
        var wedge = new WheelSoftBody();
        wedge.reset(0);
        for (int frame = 0; frame < 90; frame++) wedge.advance(1.0 / 60, 1, .75f);
        int last = WheelSoftBody.POINTS - 1;
        assertTrue(wedge.radius(WheelSoftBody.OUTER, last - 3) > wedge.radius(WheelSoftBody.OUTER, 3) + .05);
        assertTrue(wedge.angle(WheelSoftBody.OUTER, last - 3) > .02);
        assertEquals(0, wedge.angle(WheelSoftBody.OUTER, 0), .0001);
        assertEquals(0, wedge.angle(WheelSoftBody.OUTER, last), .0001);
        for (int frame = 0; frame < 120; frame++) wedge.advance(1.0 / 60, 0, 0);
        assertEquals(1, wedge.radius(WheelSoftBody.OUTER, last - 3), .001);
        assertEquals(0, wedge.angle(WheelSoftBody.OUTER, last - 3), .001);
    }
}
