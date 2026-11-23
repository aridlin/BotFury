package com.botfury;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import static com.botfury.ProjectQueue.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectQueueTest {
    @TempDir Path directory;
    private static final String WORLD = "test|overworld";

    @Test void retriesOnlyPlacementsWithExhaustedApproaches() throws Exception {
        var journal = directory.resolve("placement-approaches.json");
        var board = new ProjectQueue(journal);
        board.submit("house", WORLD, List.of(job("first", BUILD, List.of()), job("second", BUILD, List.of())));
        for (long now = 1; now <= 11; now += 2) {
            var lease = board.claim(WORLD, "worker", Set.of(BUILD), now).job();
            String evidence = lease.work().id().equals("first") ? "No reachable solid support face for 1, 2, 3"
                    : "Build target is occupied";
            assertTrue(board.finish("house", lease.work().id(), lease.lease(), false, evidence, now + 1));
        }
        assertEquals(1, board.retryPlacementApproaches(WORLD, "house"));
        var restored = new ProjectQueue(journal);
        var jobs = restored.snapshot(13).get(0).jobs();
        assertEquals(ProjectQueue.State.WAITING, jobs.get(0).state());
        assertEquals(ProjectQueue.State.FAILED, jobs.get(1).state());
    }

    private static ProjectQueue.Work job(String id, ProjectQueue.Kind kind, List<String> needs, String... locks) {
        return new ProjectQueue.Work(id, kind, "", needs, Set.of(locks));
    }

    @Test void retriesFailedExcavationWithoutReplayingCompletedWork() throws Exception {
        var journal = directory.resolve("retry-excavation.json");
        var board = new ProjectQueue(journal);
        board.submit("house", WORLD, List.of(job("first", EXCAVATE, List.of()),
                job("blocked", EXCAVATE, List.of("first")), job("place", BUILD, List.of("blocked"))));
        var first = board.claim(WORLD, "worker", Set.of(EXCAVATE), 1).job();
        assertTrue(board.finish("house", "first", first.lease(), true, "Already cleared", 2));
        for (long now = 3; now <= 7; now += 2) {
            var blocked = board.claim(WORLD, "worker", Set.of(EXCAVATE), now).job();
            assertTrue(board.finish("house", "blocked", blocked.lease(), false, "Unreachable", now + 1));
        }
        assertEquals(1, board.retryExcavation(WORLD, "house"));
        var restored = new ProjectQueue(journal);
        assertEquals(ProjectQueue.State.DONE, restored.snapshot(9).get(0).jobs().get(0).state());
        assertNull(restored.claim(WORLD, "builder", Set.of(BUILD), 9));
        var retry = restored.claim(WORLD, "worker", Set.of(EXCAVATE), 9).job();
        assertEquals("blocked", retry.work().id());
        assertEquals(1, retry.attempts());
        assertTrue(restored.finish("house", "blocked", retry.lease(), true, "Cleared", 10));
        assertEquals("place", restored.claim(WORLD, "builder", Set.of(BUILD), 11).job().work().id());
    }

    @Test void retriesDepositsWithoutReopeningFailedWithdrawals() throws Exception {
        var journal = directory.resolve("retry-deliveries.json");
        var board = new ProjectQueue(journal);
        board.submit("house", WORLD, List.of(
                new ProjectQueue.Work("deposit", TRANSFER, "{\"direction\":\"DEPOSIT\"}", List.of(), Set.of()),
                new ProjectQueue.Work("withdraw", TRANSFER, "{\"direction\":\"TAKE\"}", List.of(), Set.of())));
        for (long now = 1; now <= 11; now += 2) {
            var lease = board.claim(WORLD, "worker", Set.of(TRANSFER), now).job();
            assertTrue(board.finish("house", lease.work().id(), lease.lease(), false, "Out of reach", now + 1));
        }
        assertEquals(1, board.retryDeliveries(WORLD, "house"));
        var recovered = new ProjectQueue(journal);
        var snapshot = recovered.snapshot(13).get(0).jobs();
        assertEquals(ProjectQueue.State.WAITING, snapshot.get(0).state());
        assertEquals(ProjectQueue.State.FAILED, snapshot.get(1).state());
        assertEquals("deposit", recovered.claim(WORLD, "worker", Set.of(TRANSFER), 13).job().work().id());
    }

    @Test void coordinatorRestartDoesNotSpendTheLastAttemptOnAnInterruptedLease() throws Exception {
        var journal = directory.resolve("restarted-attempt.json");
        var board = new ProjectQueue(journal);
        board.submit("ore", WORLD, List.of(job("gather", GATHER, List.of())));
        for (long now = 1; now <= 3; now += 2) {
            var failed = board.claim(WORLD, "miner", Set.of(GATHER), now).job();
            assertTrue(board.finish("ore", "gather", failed.lease(), false, "Explicit failure", now + 1));
        }
        var interrupted = board.claim(WORLD, "miner", Set.of(GATHER), 5).job();
        var reopened = new ProjectQueue(journal);
        var retry = reopened.claim(WORLD, "miner", Set.of(GATHER), interrupted.expiresAt()).job();
        assertEquals(3, retry.attempts());
        assertNotEquals(interrupted.lease(), retry.lease());
        var state = reopened.snapshot(retry.expiresAt()).get(0).jobs().get(0);
        assertEquals(ProjectQueue.State.FAILED, state.state());
        assertEquals(1, reopened.retryInterruptedGathering(WORLD, "ore"));
    }

    @Test void workerHoldsCargoWhileAnotherDeliveryHasTheChest() throws Exception {
        var queue = new ProjectQueue(directory.resolve("chest-contention.json"));
        queue.submit("house", WORLD, List.of(job("collect", GATHER, List.of()),
                job("other-delivery", TRANSFER, List.of(), "chest"),
                new ProjectQueue.Work("deliver", TRANSFER, "", List.of("collect"), Set.of("chest"), "collect"),
                job("unrelated", CRAFT, List.of())));
        var gather = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        assertTrue(queue.finish("house", "collect", gather.lease(), true, "Collected the cargo", 2));
        var blocker = queue.claim(WORLD, "other", Set.of(TRANSFER), 3).job();
        assertNull(queue.claim(WORLD, "miner", Set.of(TRANSFER, CRAFT), 4));
        assertTrue(queue.finish("house", "other-delivery", blocker.lease(), false, "Temporary problem", 5));
        assertEquals("deliver", queue.claim(WORLD, "miner", Set.of(TRANSFER, CRAFT), 6).job().work().id());
    }

    @Test void unscopedWorkerFinishesItsReadyInventoryChainBeforeAnEarlierProject() throws Exception {
        var queue = new ProjectQueue(directory.resolve("inventory-priority.json"));
        queue.submit("stock", WORLD, List.of(job("gather", GATHER, List.of())));
        queue.submitSupplied("house", WORLD, List.of(job("logs", GATHER, List.of()),
                new ProjectQueue.Work("planks", CRAFT, "", List.of("logs"), Set.of(), "logs")), "block:2,64,0");
        var logs = queue.claim(WORLD, "worker", Set.of(GATHER), "house", 1).job();
        assertTrue(queue.finish("house", "logs", logs.lease(), true, "Collected logs", 2));
        var next = queue.claim(WORLD, "worker", Set.of(GATHER, CRAFT), 3);
        assertEquals("house", next.project());
        assertEquals("planks", next.job().work().id());
        assertEquals("stock", queue.claim(WORLD, "other", Set.of(GATHER), 4).project());
    }

    @Test void anotherProjectsRenewalsKeepAnUnscopedSupplierOnline() throws Exception {
        var queue = new ProjectQueue(directory.resolve("cross-project-heartbeat.json"));
        queue.submit("stock", WORLD, List.of(job("slow", GATHER, List.of())));
        queue.submitSupplied("house", WORLD, List.of(job("logs", GATHER, List.of()),
                new ProjectQueue.Work("planks", CRAFT, "", List.of("logs"), Set.of(), "logs")), "block:2,64,0");
        var logs = queue.claim(WORLD, "worker", Set.of(GATHER), "house", 1).job();
        assertTrue(queue.finish("house", "logs", logs.lease(), true, "Collected logs", 2));
        var slow = queue.claim(WORLD, "worker", Set.of(GATHER), 3).job();
        for (long now = 10_000; now <= 70_000; now += 10_000) {
            assertNotNull(queue.renew("stock", "slow", slow.lease(), now));
        }
        assertNull(queue.claim(WORLD, "other", Set.of(GATHER, CRAFT), "house", 70_001));
        var house = queue.snapshot(70_002).stream().filter(p -> p.id().equals("house")).findFirst().orElseThrow();
        assertEquals(ProjectQueue.State.DONE, house.jobs().get(0).state());
        assertTrue(house.supplyRecoveries().isEmpty());
    }

    @Test void scopedWorkersSkipOtherProjectsAndKeepTheirScopeAcrossLeaseExpiry() throws Exception {
        var journal = directory.resolve("scoped-board.json");
        var board = new ProjectQueue(journal);
        board.submit("diamonds", WORLD, List.of(job("gather-diamonds", GATHER, List.of())));
        board.submit("sunflowers", WORLD, List.of(job("gather-sunflowers", GATHER, List.of())));

        var oldLease = board.claim(WORLD, "Bot02", Set.of(GATHER), 1);
        assertEquals("diamonds", oldLease.project());
        assertNull(board.claim(WORLD, "Bot02", Set.of(GATHER), "sunflowers", 2));
        board = new ProjectQueue(journal);
        var sunflowerLease = board.claim(WORLD, "Bot02", Set.of(GATHER), "sunflowers", oldLease.job().expiresAt() + 1);
        assertEquals("sunflowers", sunflowerLease.project());
        assertTrue(board.finish("sunflowers", "gather-sunflowers", sunflowerLease.job().lease(), true,
                "Gathered sunflowers", oldLease.job().expiresAt() + 2));
        assertNull(board.claim(WORLD, "Bot02", Set.of(GATHER), "sunflowers", oldLease.job().expiresAt() + 3));
        assertEquals("diamonds", board.claim(WORLD, "Bot03", Set.of(GATHER), oldLease.job().expiresAt() + 4).project());
    }

    @Test void largeInspectionWaitsForAllPlacementsAcrossReopen() throws Exception {
        var journal = directory.resolve("large-board.json");
        var placements = java.util.stream.IntStream.range(0, 300)
                .mapToObj(i -> job("place-" + i, BUILD, List.of())).toList();
        var jobs = new java.util.ArrayList<>(placements);
        jobs.add(job("inspect", INSPECT, placements.stream().map(ProjectQueue.Work::id).toList()));
        var board = new ProjectQueue(journal);
        board.submit("large", WORLD, jobs);
        var lease = board.claim(WORLD, "Builder", Set.of(BUILD), 1);
        board = new ProjectQueue(journal);
        assertEquals(lease, board.claim(WORLD, "Builder", Set.of(BUILD), 2));
        assertNull(board.claim(WORLD, "Inspector", Set.of(INSPECT), 2));
        assertEquals(301, board.snapshot(2).get(0).jobs().size());
    }

    @Test void coordinatorCompletesBarrierAtomicallyWithLastPrerequisite() throws Exception {
        var journal = directory.resolve("barrier.json");
        var board = new ProjectQueue(journal);
        board.submit("barrier", WORLD, List.of(
                job("build", BUILD, List.of()),
                job("before-inspection", BARRIER, List.of("build")),
                job("inspect", INSPECT, List.of("before-inspection"))));
        assertNull(board.claim(WORLD, "Verifier", Set.of(BARRIER, INSPECT), 1));
        var build = board.claim(WORLD, "Builder", Set.of(BUILD), 2).job();
        assertTrue(board.finish("barrier", "build", build.lease(), true, "Observed expected block", 3));
        board = new ProjectQueue(journal);
        assertEquals("inspect", board.claim(WORLD, "Verifier", Set.of(BARRIER, INSPECT), 4).job().work().id());
        var gate = board.snapshot(4).get(0).jobs().get(1);
        assertEquals(ProjectQueue.State.DONE, gate.state());
        assertNull(gate.worker());
    }

    @Test void orderedTemporaryAccessCleanupWaitsForAllChecksEvenWhenOneFails() throws Exception {
        var journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        String lower = "block:4,64,0";
        String upper = "block:5,65,0";
        board.submit("access", WORLD, List.of(
                job("lower", BUILD, List.of(), lower),
                job("upper", BUILD, List.of("lower"), upper),
                job("check-left", VERIFY, List.of("upper")),
                job("check-right", VERIFY, List.of("upper")),
                new ProjectQueue.Work("remove-upper", EXCAVATE, "", List.of("upper"), Set.of(upper), null,
                        new ProjectQueue.Cleanup(upper, "check-left", List.of("check-right"))),
                new ProjectQueue.Work("remove-lower", EXCAVATE, "", List.of("lower"), Set.of(lower), null,
                        new ProjectQueue.Cleanup(lower, "remove-upper", List.of("check-right")))));
        long now = 0;
        for (var id : List.of("lower", "upper")) {
            var work = board.claim(WORLD, "Builder", Set.of(BUILD), now++).job();
            assertEquals(id, work.work().id());
            assertTrue(board.finish("access", id, work.lease(), true, "Observed access block", now++));
        }
        var first = board.claim(WORLD, "Verifier", Set.of(VERIFY), now++).job();
        assertTrue(board.finish("access", "check-left", first.lease(), true, "Observed expected block", now++));
        assertNull(board.claim(WORLD, "Cleaner", Set.of(EXCAVATE), now++));
        for (int attempt = 0; attempt < 3; attempt++) {
            var other = board.claim(WORLD, "Verifier", Set.of(VERIFY), now).job();
            assertTrue(board.finish("access", "check-right", other.lease(), false, "Mismatched block", now + 1));
            now += 30_000;
        }
        board = new ProjectQueue(journal);
        var top = board.claim(WORLD, "Cleaner", Set.of(EXCAVATE), now++).job();
        assertEquals("remove-upper", top.work().id());
        assertNull(board.claim(WORLD, "Another", Set.of(EXCAVATE), now++));
        assertTrue(board.finish("access", "remove-upper", top.lease(), true, "Observed restored air", now++));
        assertEquals("remove-lower", board.claim(WORLD, "Cleaner", Set.of(EXCAVATE), now).job().work().id());
    }

    @Test void sharedCleanupBarrierWaitsForUnrelatedWorkAfterFailureAndSurvivesReopen() throws Exception {
        Path journal = directory.resolve("settlement-barrier.json");
        var board = new ProjectQueue(journal);
        String site = "block:3,64,0";
        board.submit("structure", WORLD, List.of(
                job("access", BUILD, List.of(), site),
                job("construction", BUILD, List.of("access")),
                job("unrelated", VERIFY, List.of("access")),
                job("success-gate", BARRIER, List.of("construction")),
                job("inspection", INSPECT, List.of("success-gate")),
                job("before-cleanup", SETTLEMENT_BARRIER, List.of("inspection", "unrelated")),
                new ProjectQueue.Work("remove", EXCAVATE, "", List.of("access"), Set.of(site), null,
                        new ProjectQueue.Cleanup(site, "before-cleanup"))));
        var access = board.claim(WORLD, "Builder", Set.of(BUILD), 1).job();
        assertTrue(board.finish("structure", "access", access.lease(), true, "Observed cobblestone", 2));

        long now = 3;
        for (int attempt = 0; attempt < 3; attempt++) {
            var construction = board.claim(WORLD, "Builder", Set.of(BUILD), now).job();
            assertTrue(board.finish("structure", "construction", construction.lease(), false, "Missing materials", now + 1));
            now += 30_000;
        }
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "Cleaner", Set.of(SETTLEMENT_BARRIER, EXCAVATE), now++));
        var other = board.claim(WORLD, "Verifier", Set.of(VERIFY), now++).job();
        assertTrue(board.finish("structure", "unrelated", other.lease(), true, "Observed expected block", now++));
        board = new ProjectQueue(journal);
        var gate = board.snapshot(now).get(0).jobs().get(5);
        assertEquals(ProjectQueue.State.DONE, gate.state());
        assertNull(gate.worker());
        assertEquals("remove", board.claim(WORLD, "Cleaner", Set.of(EXCAVATE), now).job().work().id());
    }

    @Test void finalLeaseExpiryReleasesCleanupWithoutAnotherWorkerCompletion() throws Exception {
        Path journal = directory.resolve("expiry-barrier.json");
        var board = new ProjectQueue(journal);
        String site = "block:6,64,0";
        board.submit("structure", WORLD, List.of(
                job("access", BUILD, List.of(), site),
                job("inspection", INSPECT, List.of("access")),
                job("before-cleanup", SETTLEMENT_BARRIER, List.of("inspection")),
                new ProjectQueue.Work("remove", EXCAVATE, "", List.of("access"), Set.of(site), null,
                        new ProjectQueue.Cleanup(site, "before-cleanup"))));
        var access = board.claim(WORLD, "Builder", Set.of(BUILD), 1).job();
        assertTrue(board.finish("structure", "access", access.lease(), true, "Observed cobblestone", 2));

        long now = 3;
        for (int i = 0; i < 3; i++) {
            now = board.claim(WORLD, "Inspector", Set.of(INSPECT), now).job().expiresAt();
        }
        board.snapshot(now);
        board = new ProjectQueue(journal);
        assertEquals("remove", board.claim(WORLD, "Cleaner", Set.of(EXCAVATE), now).job().work().id());
    }

    @Test void sharedBarrierFitsAPlanWithHundredsOfScaffoldRemovals() throws Exception {
        var jobs = new ArrayList<ProjectQueue.Work>();
        var checked = new ArrayList<String>();
        for (int i = 0; i < 520; i++) {
            String site = "block:" + i + ",64,0";
            jobs.add(job("access-" + i, BUILD, List.of(), site));
            String id = "check-" + i;
            jobs.add(job(id, INSPECT, List.of("access-" + i)));
            checked.add(id);
        }
        jobs.add(job("before-cleanup", SETTLEMENT_BARRIER, checked));
        String previous = "before-cleanup";
        for (int i = 519; i >= 0; i--) {
            String site = "block:" + i + ",64,0";
            String id = "remove-" + i;
            jobs.add(new ProjectQueue.Work(id, EXCAVATE, "", List.of("access-" + i), Set.of(site), null,
                    new ProjectQueue.Cleanup(site, previous, List.of("before-cleanup"))));
            previous = id;
        }
        var board = new ProjectQueue(directory.resolve("wide-cleanup.json"));
        board.submit("wide", WORLD, jobs);
        assertEquals(1561, board.snapshot(1).get(0).jobs().size());
        assertNull(board.claim(WORLD, "Cleaner", Set.of(EXCAVATE), 2));
    }

    @Test void pickupShortfallRestartsOnlyItsSupplyChainAndKeepsCompletedHandoffs() throws Exception {
        var journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        String chest = "block:5,64,0";
        String material = "minecraft:stone";
        String payload = "{\"direction\":\"TAKE\",\"item\":\"minecraft:stone\",\"stock\":16}";
        board.submitSupplied("house", WORLD, List.of(
                job("gather", GATHER, List.of()),
                new ProjectQueue.Work("store", TRANSFER, "", List.of("gather"), Set.of(chest), "gather"),
                new ProjectQueue.Work("first", TRANSFER, payload, List.of("store"), Set.of(chest), null, null, material),
                new ProjectQueue.Work("second", TRANSFER, payload, List.of("store"), Set.of(chest), null, null, material),
                new ProjectQueue.Work("west", BUILD, "", List.of("first"), Set.of("west"), "first"),
                new ProjectQueue.Work("east", BUILD, "", List.of("second"), Set.of("east"), "second")
        ), chest);
        var gather = board.claim(WORLD, "Miner", Set.of(GATHER), 1).job();
        assertTrue(board.finish("house", "gather", gather.lease(), true, "Gathered 32", 2));
        var store = board.claim(WORLD, "Miner", Set.of(TRANSFER), 3).job();
        assertTrue(board.finish("house", "store", store.lease(), true, "Stored 32",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(material, 32)), 4));
        var abandoned = board.claim(WORLD, "Disconnected", Set.of(TRANSFER), 5).job();
        var first = board.claim(WORLD, "West", Set.of(TRANSFER), abandoned.expiresAt()).job();
        assertEquals("first", first.work().id());
        assertTrue(board.finish("house", "first", first.lease(), true, "Collected 16",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(material, 8)), 15_006));
        var west = board.claim(WORLD, "West", Set.of(BUILD), 15_007).job();
        var second = board.claim(WORLD, "East", Set.of(TRANSFER), 15_008).job();
        assertEquals("second", second.work().id());
        var observed = new ProjectQueue.ChestReport(chest, java.util.Map.of());
        assertFalse(board.finish("house", "first", abandoned.lease(), false, "Short", observed, null, true, 15_009));
        assertTrue(board.finish("house", "second", second.lease(), false, "Short eight", observed, null, true, 15_009));

        board = new ProjectQueue(journal);
        var project = board.snapshot(15_010).get(0);
        assertEquals(java.util.Map.of("gather", 1), project.supplyRecoveries());
        assertEquals(ProjectQueue.State.WAITING, project.jobs().get(3).state());
        assertEquals(observed, project.jobs().get(3).chest().report());
        assertEquals(first.lease(), project.jobs().get(2).lease());
        assertEquals(west.lease(), project.jobs().get(4).lease());
        assertEquals("gather", board.claim(WORLD, "East", Set.of(GATHER), 15_011).job().work().id());
    }

    @Test void laterBatchRecoveryKeepsEarlierPlacementsComplete() throws Exception {
        var journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        String chest = "block:5,64,0";
        String item = "minecraft:smooth_stone";
        String take = "{\"direction\":\"TAKE\",\"item\":\"minecraft:smooth_stone\",\"stock\":2}";
        board.submitSupplied("floor", WORLD, List.of(
                job("first-west", BUILD, List.of()),
                job("first-east", BUILD, List.of()),
                job("gather-next", GATHER, List.of("first-west", "first-east")),
                new ProjectQueue.Work("store-next", TRANSFER, "", List.of("gather-next"), Set.of(chest), "gather-next"),
                new ProjectQueue.Work("take-next", TRANSFER, take, List.of("store-next"), Set.of(chest), null, null, item)
        ), chest);
        for (int now = 1; now < 5; now += 2) {
            assertNull(board.claim(WORLD, "Miner", Set.of(GATHER), now));
            var placement = board.claim(WORLD, "Builder", Set.of(BUILD), now).job();
            assertTrue(board.finish("floor", placement.work().id(), placement.lease(), true, "Placed", now + 1));
        }
        var gather = board.claim(WORLD, "Miner", Set.of(GATHER), 5).job();
        assertTrue(board.finish("floor", gather.work().id(), gather.lease(), true, "Gathered", 6));
        var deposit = board.claim(WORLD, "Miner", Set.of(TRANSFER), 7).job();
        var stock = new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 2));
        assertTrue(board.finish("floor", deposit.work().id(), deposit.lease(), true, "Deposited", stock, 8));
        var pickup = board.claim(WORLD, "Builder", Set.of(TRANSFER), 9).job();
        var empty = new ProjectQueue.ChestReport(chest, java.util.Map.of());
        assertTrue(board.finish("floor", pickup.work().id(), pickup.lease(), false, "Chest empty", empty, null, true, 10));

        board = new ProjectQueue(journal);
        var project = board.snapshot(11).get(0);
        assertEquals(java.util.Map.of("gather-next", 1), project.supplyRecoveries());
        assertEquals(ProjectQueue.State.DONE, project.jobs().get(0).state());
        assertEquals(ProjectQueue.State.DONE, project.jobs().get(1).state());
        assertEquals("gather-next", board.claim(WORLD, "Builder", Set.of(GATHER), 12).job().work().id());
    }

    @Test void constructionBatchesCreditDifferentWorkersAndSurviveRestart() throws Exception {
        Path journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        String chest = "block:5,64,0";
        String material = "minecraft:stone";
        board.submitSupplied("house", WORLD, List.of(
                job("store", TRANSFER, List.of(), chest),
                new ProjectQueue.Work("take-west", TRANSFER, "", List.of("store"), Set.of(chest), null, null, material),
                new ProjectQueue.Work("take-east", TRANSFER, "", List.of("store"), Set.of(chest), null, null, material),
                new ProjectQueue.Work("west", BUILD, "", List.of("take-west"), Set.of("west"), "take-west"),
                new ProjectQueue.Work("east", BUILD, "", List.of("take-east"), Set.of("east"), "take-east")
        ), chest);
        var store = board.claim(WORLD, "Supplier", Set.of(TRANSFER), 1).job();
        var stock = new ProjectQueue.ChestReport(chest, java.util.Map.of(material, 4));
        assertTrue(board.finish("house", "store", store.lease(), true, "Stored four", stock, 2));
        var west = board.claim(WORLD, "West", Set.of(TRANSFER, BUILD), 3).job();
        assertEquals("take-west", west.work().id());
        assertTrue(board.finish("house", "take-west", west.lease(), true, "Collected two",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(material, 2)), 4));

        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "West", Set.of(TRANSFER), 5));
        var east = board.claim(WORLD, "East", Set.of(TRANSFER, BUILD), 6).job();
        assertEquals("take-east", east.work().id());
        assertTrue(board.finish("house", "take-east", east.lease(), true, "Collected two",
                new ProjectQueue.ChestReport(chest, java.util.Map.of()), 7));
        assertEquals("west", board.claim(WORLD, "West", Set.of(TRANSFER, BUILD), 8).job().work().id());
        assertEquals("east", board.claim(WORLD, "East", Set.of(TRANSFER, BUILD), 9).job().work().id());
    }

    @Test void fullJournalArchivesCompletedWorkAndKeepsItsReceiptsAfterRestart() throws Exception {
        Path journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        String chest = "block:5,64,0";
        board.submit("receipt", WORLD, List.of(job("deliver", TRANSFER, List.of(), chest)));
        var assignment = board.claim(WORLD, "Supplier", Set.of(TRANSFER), 1).job();
        var stock = new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:iron_ingot", 17));
        assertTrue(board.finish("receipt", "deliver", assignment.lease(), true, "Observed stock", stock, 2));

        for (int i = 0; i < 31; i++) {
            board.submit("waiting-" + i, WORLD, List.of(job("build", BUILD, List.of())));
        }
        assertTrue(board.history().isEmpty());
        assertEquals(32, board.snapshot(3).size());
        board.submit("overflow", WORLD, List.of(job("build", BUILD, List.of())));

        var recovered = new ProjectQueue(journal);
        assertEquals(32, recovered.snapshot(4).size());
        assertEquals(List.of("receipt"), recovered.history());
        assertEquals("Observed stock", recovered.archived("receipt").jobs().get(0).evidence());
        assertEquals(stock, recovered.chestStock(WORLD).get(chest).report());
        assertTrue(recovered.finish("receipt", "deliver", assignment.lease(), true, "Observed stock", stock, 4));
        assertFalse(recovered.finish("receipt", "deliver", assignment.lease(), true, "Different stock", stock, 4));
        assertThrows(IllegalArgumentException.class,
                () -> recovered.submit("receipt", WORLD, List.of(job("build", BUILD, List.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> recovered.submit("too-many", WORLD, List.of(job("build", BUILD, List.of()))));
        assertEquals(List.of("receipt"), recovered.history());
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
        var oreSite = new ProjectQueue.ResourceSite(72, -54, 120);
        assertTrue(board.finish("house", "raw", original.lease(), true, "Observed ore", null, oreSite, 1));
        var coal = board.claim(WORLD, "Original", Set.of(GATHER), 2).job();
        assertTrue(board.finish("house", "fuel", coal.lease(), true, "Observed coal", 3));
        var abandoned = board.claim(WORLD, "Original", Set.of(SMELT), 4).job();

        var recovered = new ProjectQueue(journal);
        assertNull(recovered.claim(WORLD, "Replacement", Set.of(GATHER, SMELT), 20_000));
        assertNull(recovered.claim(WORLD, "Replacement", Set.of(GATHER, SMELT), 79_999));
        var reassigned = recovered.claim(WORLD, "Replacement", Set.of(GATHER, SMELT), 80_000).job();
        assertEquals("raw", reassigned.work().id());
        assertEquals(oreSite, reassigned.resourceSite());
        assertFalse(recovered.finish("house", "smelt", abandoned.lease(), true, "Late report", 80_001));
        assertTrue(recovered.finish("house", "raw", reassigned.lease(), true, "Observed replacement ore", 80_002));
        var newFuel = recovered.claim(WORLD, "Replacement", Set.of(GATHER), 80_003).job();
        assertTrue(recovered.finish("house", "fuel", newFuel.lease(), true, "Observed replacement coal", 80_004));
        assertEquals("smelt", recovered.claim(WORLD, "Replacement", Set.of(SMELT), 80_005).job().work().id());
        assertEquals(java.util.Map.of("raw", 1), new ProjectQueue(journal).snapshot(80_006).get(0).supplyRecoveries());
    }

    @Test void observedResourceSiteSurvivesLeaseExpiryAndCoordinatorRestart() throws Exception {
        var journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        board.submit("mine", WORLD, List.of(job("ore", GATHER, List.of())));
        var original = board.claim(WORLD, "Original", Set.of(GATHER), 1).job();
        var source = new ProjectQueue.ResourceSite(400, -54, -240);
        var renewal = board.renew("mine", "ore", original.lease(), source, 2);
        assertEquals(source, renewal.resourceSite());
        var recovered = new ProjectQueue(journal);
        assertNull(recovered.claim("test|nether", "Replacement", Set.of(GATHER), renewal.expiresAt()));
        var claimed = recovered.claim(WORLD, "Replacement", Set.of(GATHER), renewal.expiresAt()).job();
        assertEquals(source, claimed.resourceSite());
        var stale = new ProjectQueue.ResourceSite(0, 0, 0);
        assertNull(recovered.renew("mine", "ore", original.lease(), stale, renewal.expiresAt() + 1));
        assertEquals(source, recovered.snapshot(renewal.expiresAt() + 1).get(0).jobs().get(0).resourceSite());
    }

    @Test void differentWorkerReusesMatchingGatheringSiteWithinProjectAfterRestart() throws Exception {
        var journal = directory.resolve("board.json");
        var board = new ProjectQueue(journal);
        String both = """
                {"resource":"iron","variant":"both","item":"minecraft:raw_iron","count":2}
                """;
        String different = both.replace("both", "deepslate");
        var source = new ProjectQueue.ResourceSite(240, -20, -400);
        board.submit("house", WORLD, List.of(
                new ProjectQueue.Work("first", GATHER, both, List.of(), Set.of()),
                new ProjectQueue.Work("different", GATHER, different, List.of("first"), Set.of()),
                new ProjectQueue.Work("next", GATHER, both.replace(":2", ":8"), List.of("first"), Set.of())));
        board.submit("other", WORLD, List.of(new ProjectQueue.Work("first", GATHER, both, List.of(), Set.of())));
        var first = board.claim(WORLD, "Miner", Set.of(GATHER), "house", 1).job();
        assertTrue(board.finish("house", "first", first.lease(), true, "Observed raw iron", null, source, 2));

        var recovered = new ProjectQueue(journal);
        assertNull(recovered.claim(WORLD, "OtherWorker", Set.of(GATHER), "other", 3).job().resourceSite());
        assertNull(recovered.claim(WORLD, "VariantWorker", Set.of(GATHER), "house", 4).job().resourceSite());
        var next = recovered.claim(WORLD, "NewWorker", Set.of(GATHER), "house", 5).job();
        assertEquals("next", next.work().id());
        assertEquals(source, next.resourceSite());
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
