package com.botfury;

import com.botfury.ProjectQueue.ResourceSite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerReturnTrailTest {
    @TempDir Path folder;

    @Test void restoresAContinuousRouteFromTheMineToTheStation() throws Exception {
        var trail = new WorkerReturnTrail();
        var station = new ResourceSite(0, 64, 0);
        var entrance = new ResourceSite(4, 62, 0);
        var ledge = new ResourceSite(8, 58, 0);
        var corner = new ResourceSite(8, 54, 4);
        var mine = new ResourceSite(4, 50, 4);
        for (var point : List.of(station, entrance, ledge, corner, mine)) trail.record(point);

        var owner = new WorkerEnrollment("Miner", "paper:25565|minecraft:overworld", 8840);
        var file = folder.resolve("trail.json");
        trail.save(file, owner);
        var restored = new WorkerReturnTrail();
        restored.load(file, owner);

        assertEquals(List.of(corner, ledge, entrance), restored.backtrack(mine, station));
        assertTrue(restored.backtrack(new ResourceSite(30, 50, 4), station).isEmpty());
        restored.load(file, new WorkerEnrollment("OtherMiner", owner.world(), owner.port()));
        assertTrue(restored.backtrack(mine, station).isEmpty());
    }

    @Test void largeDropsAndTeleportsBreakTheTrail() {
        var trail = new WorkerReturnTrail();
        var station = new ResourceSite(0, 64, 0);
        var path = new ResourceSite(4, 62, 0);
        var fall = new ResourceSite(4, 55, 0);
        trail.record(station);
        trail.record(path);
        trail.record(fall);
        assertTrue(trail.backtrack(fall, station).isEmpty());

        trail.record(station);
        trail.record(path);
        var teleported = new ResourceSite(200, 62, 0);
        trail.record(teleported);
        assertTrue(trail.backtrack(teleported, station).isEmpty());
    }

    @Test void returnsTheUsefulPartOfATrailThatDoesNotReachTheStation() {
        var trail = new WorkerReturnTrail();
        var obsoleteDetour = new ResourceSite(36, -20, 0);
        var entrance = new ResourceSite(24, -16, 0);
        var lip = new ResourceSite(24, -20, 0);
        var bend = new ResourceSite(24, -24, 0);
        var slope = new ResourceSite(28, -28, 0);
        var mine = new ResourceSite(28, -32, 0);
        for (var point : List.of(obsoleteDetour, entrance, lip, bend, slope, mine)) trail.record(point);

        assertEquals(List.of(slope, bend, lip, entrance), trail.backtrack(mine, new ResourceSite(0, 64, 0)));
        assertTrue(trail.backtrack(mine, new ResourceSite(28, -30, 152)).isEmpty());
        assertTrue(trail.backtrack(new ResourceSite(48, -32, 0), new ResourceSite(0, 64, 0)).isEmpty());
    }

    @Test void surfaceAscentCanFollowAStaircaseAwayFromTheSurveyColumn() {
        var trail = new WorkerReturnTrail();
        for (int climb = 32; climb >= 0; climb -= 4) {
            trail.record(new ResourceSite(climb, 32 + climb, 0));
        }

        var mine = new ResourceSite(0, 32, 0);
        var survey = new ResourceSite(0, 64, 0);
        var route = trail.backtrackToSurface(mine, survey);
        assertEquals(8, route.size());
        assertEquals(new ResourceSite(32, 64, 0), route.get(route.size() - 1));
        assertTrue(trail.backtrack(mine, survey).size() < route.size());
    }

    @Test void doesNotUndoAWorkersClimbToReachAnOlderTrail() {
        var trail = new WorkerReturnTrail();
        var current = new ResourceSite(36, -20, 0);
        for (var point : List.of(new ResourceSite(16, -8, 0), new ResourceSite(16, -12, 0),
                new ResourceSite(16, -16, 0), new ResourceSite(20, -20, 0), new ResourceSite(24, -24, 0),
                new ResourceSite(28, -28, 0), new ResourceSite(32, -32, 0),
                new ResourceSite(36, -28, 0), new ResourceSite(36, -24, 0), current)) trail.record(point);
        assertTrue(trail.backtrack(current, new ResourceSite(0, 64, 0)).isEmpty());
    }

    @Test void retracingToAPreviousCellDiscardsTheDetour() {
        var trail = new WorkerReturnTrail();
        var station = new ResourceSite(0, 64, 0);
        var bend = new ResourceSite(4, 60, 0);
        var detour = new ResourceSite(12, 56, 0);
        var mine = new ResourceSite(4, 54, 6);
        trail.record(station);
        trail.record(bend);
        trail.record(detour);
        trail.record(bend);
        trail.record(new ResourceSite(4, 57, 3));
        trail.record(mine);
        assertEquals(List.of(new ResourceSite(4, 57, 3), bend), trail.backtrack(mine, station));
    }
}
