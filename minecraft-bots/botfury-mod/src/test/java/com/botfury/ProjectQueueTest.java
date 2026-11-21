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

    @Test void failedPlacementStillReleasesItsTemporarySupportAfterRestart() throws Exception {
        Path path = directory.resolve("board.json");
        var board = new ProjectQueue(path);
        String site = "block:3,64,0";
        board.submit("pillar", WORLD, List.of(
                job("support", BUILD, List.of(), site),
                job("pillar", BUILD, List.of("support"), site),
                new ProjectQueue.Work("remove", EXCAVATE, "", List.of("support"), Set.of(site), null,
                        new ProjectQueue.Cleanup(site, "pillar"))));
        board.submit("other", WORLD, List.of(job("replace", EXCAVATE, List.of(), site)));
        var support = board.claim(WORLD, "Builder", Set.of(BUILD), 1).job();
        assertTrue(board.finish("pillar", "support", support.lease(), true, "Observed cobblestone", 2));
        assertNull(board.claim(WORLD, "Cleaner", Set.of(EXCAVATE), 3));

        for (int i = 0; i < 3; i++) {
            var pillar = board.claim(WORLD, "Builder", Set.of(BUILD), 4 + i * 2).job();
            assertTrue(board.finish("pillar", "pillar", pillar.lease(), false, "No log in inventory", 5 + i * 2));
        }
        board = new ProjectQueue(path);
        var cleanup = board.claim(WORLD, "Cleaner", Set.of(EXCAVATE), 11).job();
        assertEquals("remove", cleanup.work().id());
        assertNull(board.claim(WORLD, "Other", Set.of(EXCAVATE), 12));
        assertTrue(board.finish("pillar", "remove", cleanup.lease(), true, "Observed air", 13));
        assertEquals("other", board.claim(WORLD, "Other", Set.of(EXCAVATE), 14).project());
    }

    @Test void aSupportThatWasNeverPlacedDoesNotMakeCleanupEligible() throws Exception {
        var board = new ProjectQueue(directory.resolve("board.json"));
        String site = "block:3,64,0";
        board.submit("pillar", WORLD, List.of(
                job("check", VERIFY, List.of(), site),
                job("support", BUILD, List.of("check"), site),
                job("pillar", BUILD, List.of("support"), site),
                new ProjectQueue.Work("remove", EXCAVATE, "", List.of("support"), Set.of(site), null,
                        new ProjectQueue.Cleanup(site, "pillar"))));
        board.submit("other", WORLD, List.of(job("replace", EXCAVATE, List.of(), site)));
        for (int i = 0; i < 3; i++) {
            var probe = board.claim(WORLD, "Checker", Set.of(VERIFY), 1 + i * 2).job();
            assertTrue(board.finish("pillar", "check", probe.lease(), false, "Site occupied", 2 + i * 2));
        }
        assertNull(board.claim(WORLD, "Builder", Set.of(BUILD), 7));
        assertEquals("other", board.claim(WORLD, "Other", Set.of(EXCAVATE), 7).project());
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

    @Test void suppliedProjectReservesItsChestUntilCompletionAcrossRestarts() throws Exception {
        Path journal = directory.resolve("board.json");
        String chest = "block:5,64,0";
        var board = new ProjectQueue(journal);
        board.submitSupplied("house", WORLD, List.of(
                job("materials", GATHER, List.of()),
                job("store", TRANSFER, List.of("materials"), chest)), chest);
        board.submit("other", WORLD, List.of(job("take", TRANSFER, List.of(), chest)));

        var recovered = new ProjectQueue(journal);
        assertNull(recovered.claim(WORLD, "Other", Set.of(TRANSFER), 0));
        assertThrows(IllegalArgumentException.class, () -> recovered.submitSupplied("road", WORLD,
                List.of(job("store", TRANSFER, List.of(), chest)), chest));

        var material = recovered.claim(WORLD, "Miner", Set.of(GATHER), 1).job();
        assertTrue(recovered.finish("house", "materials", material.lease(), true, "Gathered", 2));
        var store = recovered.claim(WORLD, "Miner", Set.of(TRANSFER), 3).job();
        var observation = new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:stone", 1));
        assertTrue(recovered.finish("house", "store", store.lease(), true, "Deposited", observation, 4));
        assertEquals("other", recovered.claim(WORLD, "Other", Set.of(TRANSFER), 5).project());
    }

    @Test void suppliedProjectReassignsAStalledProductionChainAfterCoordinatorRestart() throws Exception {
        Path journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        var raw = job("raw", GATHER, List.of());
        var fuel = new ProjectQueue.Work("fuel", GATHER, "", List.of("raw"), Set.of(), "raw");
        var smelt = new ProjectQueue.Work("smelt", SMELT, "", List.of("fuel"), Set.of(), "fuel");
        board.submitSupplied("house", WORLD, List.of(raw, fuel, smelt), "block:5,64,0");
        var original = board.claim(WORLD, "Original", Set.of(GATHER), 0).job();
        assertTrue(board.finish("house", "raw", original.lease(), true, "Observed ore", 1));
        var coal = board.claim(WORLD, "Original", Set.of(GATHER), 2).job();
        assertTrue(board.finish("house", "fuel", coal.lease(), true, "Observed coal", 3));
        var abandoned = board.claim(WORLD, "Original", Set.of(SMELT), 4).job();

        var recovered = new ProjectQueue(journal);
        assertNull(recovered.claim(WORLD, "Replacement", Set.of(GATHER, SMELT), 20_000));
        assertNull(recovered.claim(WORLD, "Replacement", Set.of(GATHER, SMELT), 79_999));
        var reassigned = recovered.claim(WORLD, "Replacement", Set.of(GATHER, SMELT), 80_000).job();
        assertEquals("raw", reassigned.work().id());
        assertFalse(recovered.finish("house", "smelt", abandoned.lease(), true, "Late report", 80_001));
        assertTrue(recovered.finish("house", "raw", reassigned.lease(), true, "Observed replacement ore", 80_002));
        var newFuel = recovered.claim(WORLD, "Replacement", Set.of(GATHER), 80_003).job();
        assertTrue(recovered.finish("house", "fuel", newFuel.lease(), true, "Observed replacement coal", 80_004));
        assertEquals("smelt", recovered.claim(WORLD, "Replacement", Set.of(SMELT), 80_005).job().work().id());
        assertEquals(java.util.Map.of("raw", 1), new ProjectQueue(journal).snapshot(80_006).get(0).supplyRecoveries());
    }

    @Test void producersWorkInParallelButDepositFromDistinctStockObservations() throws Exception {
        Path journal = directory.resolve("board.json");
        String key = "block:5,64,0";
        String item = "minecraft:iron_pickaxe";
        var work = new java.util.ArrayList<ProjectQueue.Work>();
        for (int index = 0; index < 2; index++) {
            String lane = "lane-" + index + "-";
            work.add(job(lane + "gather", GATHER, List.of()));
            work.add(new ProjectQueue.Work(lane + "inspect", TRANSFER, "", List.of(lane + "gather"),
                    Set.of(key), lane + "gather"));
            work.add(new ProjectQueue.Work(lane + "deposit", TRANSFER, "{\"stock\":0}",
                    List.of(lane + "inspect"), Set.of(key), lane + "inspect"));
        }
        var board = new ProjectQueue(journal);
        board.submitOutput("tools", WORLD, work, new ProjectQueue.Repeat(2, item, key, 0, null, false, 54));
        var first = board.claim(WORLD, "First", Set.of(GATHER), 0).job();
        var second = board.claim(WORLD, "Second", Set.of(GATHER), 0).job();
        assertEquals("lane-0-gather", first.work().id());
        assertEquals("lane-1-gather", second.work().id());
        assertTrue(board.finish("tools", first.work().id(), first.lease(), true, "Carried", 1));
        assertTrue(board.finish("tools", second.work().id(), second.lease(), true, "Carried", 1));

        var inspection = board.claim(WORLD, "First", Set.of(TRANSFER), 2).job();
        assertTrue(board.finish("tools", inspection.work().id(), inspection.lease(), true, "Full",
                new ProjectQueue.ChestReport(key, java.util.Map.of(), 0), 3));
        assertNull(board.claim(WORLD, "First", Set.of(TRANSFER), 4));
        board = new ProjectQueue(journal);
        inspection = board.claim(WORLD, "First", Set.of(TRANSFER), 10_003).job();
        assertTrue(board.finish("tools", inspection.work().id(), inspection.lease(), true, "Observed",
                new ProjectQueue.ChestReport(key, java.util.Map.of(), 10), 10_004));
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "Second", Set.of(TRANSFER), 10_005));
        var delivery = board.claim(WORLD, "First", Set.of(TRANSFER), 10_005).job();
        assertEquals("{\"stock\":2}", delivery.work().payload());
        assertTrue(board.finish("tools", delivery.work().id(), delivery.lease(), true, "Deposited",
                new ProjectQueue.ChestReport(key, java.util.Map.of(item, 2)), 10_006));
        var nextInspection = board.claim(WORLD, "Second", Set.of(TRANSFER), 10_007).job();
        assertEquals("lane-1-inspect", nextInspection.work().id());
        assertTrue(board.finish("tools", nextInspection.work().id(), nextInspection.lease(), true, "Observed",
                new ProjectQueue.ChestReport(key, java.util.Map.of(item, 2), 8), 10_008));
        assertEquals("{\"stock\":4}", board.claim(WORLD, "Second", Set.of(TRANSFER), 10_009).job().work().payload());
        assertEquals(1, board.snapshot(10_010).get(0).repeat().completed());
    }

    @Test void outputCyclesAddToObservedStockAndRespectCancellationAfterRestart() throws Exception {
        Path journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        String key = "block:5,64,0";
        String item = "minecraft:diamond_block";
        var inspect = job("inspect", TRANSFER, List.of(), key);
        var deposit = new ProjectQueue.Work("deposit", TRANSFER, "{\"stock\":0}", List.of("inspect"), Set.of(key), "inspect");
        board.submitOutput("output", WORLD, List.of(inspect, deposit),
                new ProjectQueue.Repeat(10, item, key, 0, null, false));
        board.submit("other", WORLD, List.of(job("take", TRANSFER, List.of(), key)));
        var first = board.claim(WORLD, "Miner", Set.of(TRANSFER), 0).job();
        assertTrue(board.finish("output", "inspect", first.lease(), true, "Counted stock",
                new ProjectQueue.ChestReport(key, java.util.Map.of(item, 30)), 1));
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "Unrelated", Set.of(TRANSFER), 2));
        var move = board.claim(WORLD, "Miner", Set.of(TRANSFER), 2).job();
        assertEquals("{\"stock\":40}", move.work().payload());
        var stock = new ProjectQueue.ChestReport(key, java.util.Map.of(item, 40));
        assertTrue(board.finish("output", "deposit", move.lease(), true, "Deposited", stock, 3));
        assertEquals(stock, board.chestStock(WORLD).get(key).report());
        assertEquals(1, board.snapshot(4).get(0).repeat().completed());
        assertEquals("inspect", board.claim(WORLD, "Miner", Set.of(TRANSFER), 4).job().work().id());
        assertTrue(board.cancelOutput(WORLD, key));
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "Other", Set.of(TRANSFER), 5));
        assertEquals("other", board.claim(WORLD, "Other", Set.of(TRANSFER), 15_005).project());
        assertTrue(board.snapshot(15_005).get(0).repeat().cancelled());
    }

    @Test void rejectsUnknownDependenciesBeforeWriting() throws Exception {
        var board = new ProjectQueue(directory.resolve("board.json"));
        assertThrows(IllegalArgumentException.class, () -> board.submit("broken", WORLD,
                List.of(job("place", BUILD, List.of("missing"), "site"))));
        assertTrue(board.snapshot(0).isEmpty());
    }
}
