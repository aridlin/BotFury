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

    @Test void pullDeformsNearbyMassesAndReturnsToRest() {
        var wedge = new WheelSoftBody();
        wedge.reset(0);
        for (int frame = 0; frame < 90; frame++) wedge.advance(1.0 / 60, 1, .75f);
        int last = WheelSoftBody.POINTS - 1;
        assertTrue(wedge.radius(WheelSoftBody.OUTER, last - 3) > wedge.radius(WheelSoftBody.OUTER, 3) + .05);
        assertTrue(wedge.angle(WheelSoftBody.OUTER, last - 3) > .02);
        for (int frame = 0; frame < 120; frame++) wedge.advance(1.0 / 60, 0, 0);
        assertEquals(1, wedge.radius(WheelSoftBody.OUTER, last - 3), .001);
        assertEquals(0, wedge.angle(WheelSoftBody.OUTER, last - 3), .001);
    }

    @Test void contactMovesTheNeighborAndSeparatesTheSharedEdges() {
        var before = new WheelSoftBody();
        var after = new WheelSoftBody();
        before.reset(0);
        after.reset(0);
        float neighborMotion = 0;
        float centerMotion = 0;
        for (int frame = 0; frame < 120; frame++) {
            before.advance(WheelSoftBody.STEP, 1, .98f);
            after.advance(WheelSoftBody.STEP, 0, 0);
            for (int pass = 0; pass < 3; pass++) WheelSoftBody.collide(before, after);
            neighborMotion = Math.max(neighborMotion, after.angle(WheelSoftBody.OUTER, 2));
            centerMotion = Math.max(centerMotion, after.angle(WheelSoftBody.OUTER, WheelSoftBody.POINTS / 2));
        }
        assertTrue(neighborMotion > .025f);
        assertTrue(centerMotion > .01f);
        int edge = WheelSoftBody.POINTS - 1;
        float sharedOuter = Math.min(before.radius(WheelSoftBody.OUTER, edge), after.radius(WheelSoftBody.OUTER, 0));
        assertTrue(angleAt(before, edge, sharedOuter) - angleAt(after, 0, sharedOuter) <= .0301f);
        for (int frame = 0; frame < 240; frame++) {
            before.advance(WheelSoftBody.STEP, 0, 0);
            after.advance(WheelSoftBody.STEP, 0, 0);
            WheelSoftBody.collide(before, after);
        }
        assertEquals(0, after.angle(WheelSoftBody.OUTER, 2), .001);
    }

    private static float angleAt(WheelSoftBody mesh, int point, float radius) {
        float inner = mesh.radius(WheelSoftBody.INNER, point);
        float outer = mesh.radius(WheelSoftBody.OUTER, point);
        float weight = (radius - inner) / (outer - inner);
        return (1 - weight) * mesh.angle(WheelSoftBody.INNER, point) + weight * mesh.angle(WheelSoftBody.OUTER, point);
    }
}
