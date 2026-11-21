package com.botfury;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import static com.botfury.ProjectQueue.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectQueueTest {
    @TempDir Path directory;
    private static final String WORLD = "test|overworld";

    private static ProjectQueue.Work job(String id, ProjectQueue.Kind kind, List<String> needs, String... locks) {
        return new ProjectQueue.Work(id, kind, "", needs, Set.of(locks));
    }

    @Test void separatesRolesAndPreventsTwoBotsFromUsingTheSameWorkArea() throws Exception {
        var board = new ProjectQueue(directory.resolve("board.json"));
        board.submit("house", WORLD, List.of(
                job("clear", EXCAVATE, List.of(), "area:house"),
                job("ore", GATHER, List.of(), "area:mine"),
                job("brick", SMELT, List.of("ore"), "chest:hub"),
                job("place", BUILD, List.of("clear", "brick"), "area:house")));
        board.submit("road", WORLD, List.of(job("clear", EXCAVATE, List.of(), "area:house")));
        assertNotNull(board.claim(WORLD, "Excavator", Set.of(EXCAVATE), 0));
        assertNull(board.claim(WORLD, "OtherExcavator", Set.of(EXCAVATE), 1));
        var ore = board.claim(WORLD, "Miner", Set.of(GATHER), 1);
        assertNotNull(ore);
        assertEquals(ore, board.claim(WORLD, "Miner", Set.of(GATHER), 2));
        assertNull(board.claim(WORLD, "Builder", Set.of(BUILD), 3));
        assertNull(board.claim(WORLD, "Smelter", Set.of(SMELT), 3));
        assertTrue(board.finish("house", "ore", ore.job().lease(), true, "Observed 12 raw iron in hub", 4));
        assertNotNull(board.claim(WORLD, "Smelter", Set.of(SMELT), 5));
    }

    @Test void recoversAfterRestartAndRejectsLateReportsFromReassignedWork() throws Exception {
        Path journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        board.submit("house", WORLD, List.of(job("gather", GATHER, List.of(), "mine")));
        var old = board.claim(WORLD, "First", Set.of(GATHER), 100).job();
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "Second", Set.of(GATHER), 101));
        var next = board.claim(WORLD, "Second", Set.of(GATHER), old.expiresAt()).job();
        assertNotEquals(old.lease(), next.lease());
        assertNull(board.renew("house", "gather", old.lease(), old.expiresAt()));
        assertFalse(board.finish("house", "gather", old.lease(), true, "stale", old.expiresAt()));
        assertTrue(board.finish("house", "gather", next.lease(), true, "Observed the chest contents", old.expiresAt()));
        board = new ProjectQueue(journal);
        assertTrue(board.finish("house", "gather", next.lease(), true, "Observed the chest contents", old.expiresAt()));
        assertEquals(ProjectQueue.State.DONE, board.snapshot(old.expiresAt()).get(0).jobs().get(0).state());
    }

    @Test void concurrentClaimsGrantOneLeaseAndRepeatedFailureBlocksDependents() throws Exception {
        var board = new ProjectQueue(directory.resolve("board.json"));
        board.submit("house", WORLD, List.of(job("clear", EXCAVATE, List.of(), "site"),
                job("place", BUILD, List.of("clear"), "site")));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> board.claim(WORLD, "First", Set.of(EXCAVATE), 0));
            var second = workers.submit(() -> board.claim(WORLD, "Second", Set.of(EXCAVATE), 0));
            var a = first.get();
            var b = second.get();
            assertTrue((a == null) != (b == null));
            var winner = a == null ? b : a;
            assertTrue(board.finish("house", "clear", winner.job().lease(), false, "Unreachable stand", 1));
            for (int attempt = 2; attempt <= 3; attempt++) {
                var job = board.claim(WORLD, "Third", Set.of(EXCAVATE), attempt).job();
                assertTrue(board.finish("house", "clear", job.lease(), false, "Unreachable stand", attempt));
            }
            assertEquals(ProjectQueue.State.FAILED, board.snapshot(5).get(0).jobs().get(0).state());
            assertNull(board.claim(WORLD, "Fourth", Set.of(EXCAVATE, BUILD), 5));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void stockChangesOnlyWithTheCurrentTransferLeaseAndSurvivesRestart() throws Exception {
        Path journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        board.submit("hub", WORLD, List.of(job("deliver", TRANSFER, List.of(), "block:5,64,0")));
        var stale = board.claim(WORLD, "First", Set.of(TRANSFER), 0).job();
        var current = board.claim(WORLD, "Replacement", Set.of(TRANSFER), stale.expiresAt()).job();
        var stock = new ProjectQueue.ChestReport("block:5,64,0", java.util.Map.of("minecraft:iron_ingot", 17));
        assertFalse(board.finish("hub", "deliver", stale.lease(), true, "stale", stock, stale.expiresAt()));
        assertTrue(board.chestStock(WORLD).isEmpty());
        assertTrue(board.finish("hub", "deliver", current.lease(), true, "observed", stock, stale.expiresAt()));
        var recovered = new ProjectQueue(journal);
        assertEquals(stock, recovered.chestStock(WORLD).get("block:5,64,0").report());
        assertTrue(recovered.chestStock("test|nether").isEmpty());
    }

    @Test void inventoryContinuitySurvivesCoordinatorRestart() throws Exception {
        Path journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        var carry = job("gather", GATHER, List.of(), "mine");
        var smelt = new ProjectQueue.Work("smelt", SMELT, "", List.of("gather"), Set.of("furnace"), "gather");
        board.submit("house", WORLD, List.of(carry, smelt));
        var assignment = board.claim(WORLD, "Supplier", Set.of(GATHER), 0);
        assertTrue(board.finish("house", "gather", assignment.job().lease(), true, "Carrying raw ore", 1));
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "Other", Set.of(SMELT), 2));
        var followup = board.claim(WORLD, "Supplier", Set.of(SMELT), 2);
        assertEquals("smelt", followup.job().work().id());
    }

    @Test void rejectsUnknownDependenciesBeforeWriting() throws Exception {
        var board = new ProjectQueue(directory.resolve("board.json"));
        assertThrows(IllegalArgumentException.class, () -> board.submit("broken", WORLD,
                List.of(job("place", BUILD, List.of("missing"), "site"))));
        assertTrue(board.snapshot(0).isEmpty());
    }
}
