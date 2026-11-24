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
