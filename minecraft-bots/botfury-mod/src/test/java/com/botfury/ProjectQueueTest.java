package com.botfury;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
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

    @Test void anotherWorkerCanResumeCraftingWithTheMissedDrop() throws Exception {
        var file = directory.resolve("missed-drop.json");
        var queue = new ProjectQueue(file);
        var gather = job("oak", GATHER, List.of()).withPayload("""
                {"resource":"block:minecraft:oak_log","variant":"both","item":"minecraft:oak_log","count":1}
                """);
        var craft = new ProjectQueue.Work("planks", CRAFT,
                "{\"recipe\":\"minecraft:oak_planks\",\"count\":4,\"inventory\":true}",
                List.of("oak"), Set.of(), "oak");
        queue.submit("project", WORLD, List.of(gather, craft));
        var initial = queue.claim(WORLD, "original", Set.of(GATHER), "project", 1).job();
        assertNull(queue.claim(WORLD, "carrier", Set.of(GATHER), "project", 2,
                null, block -> false, (worker, item) -> 1, null));
        assertTrue(queue.finish("project", "oak", initial.lease(), false, ProjectQueue.GATHER_PICKUP_MISSED, 3));
        queue = new ProjectQueue(file);
        assertNull(queue.claim(WORLD, "empty", Set.of(GATHER), "project", 4,
                null, block -> false, (worker, item) -> 0, null));
        var resumed = queue.claim(WORLD, "carrier", Set.of(GATHER), "project", 5,
                null, block -> false, (worker, item) -> 1, null).job();
        assertNotEquals(initial.lease(), resumed.lease());
        assertFalse(queue.finish("project", "oak", initial.lease(), true, "Stale original", 6));
        assertTrue(queue.finish("project", "oak", resumed.lease(), true, "Carried the log", 7));
        assertNull(queue.claim(WORLD, "original", Set.of(CRAFT), "project", 8));
        assertEquals("planks", queue.claim(WORLD, "carrier", Set.of(CRAFT), "project", 9).job().work().id());
    }

    @Test void expiredPrivateGatherResumesFromIncreasingRecoveredInputs() throws Exception {
        var path = directory.resolve("recovered-inventory-gather.json");
        var queue = new ProjectQueue(path);
        var gather = job("logs", GATHER, List.of()).withPayload("""
                {"resource":"block:minecraft:oak_log","variant":"both","item":"minecraft:oak_log","count":4}
                """);
        var craft = new ProjectQueue.Work("planks", CRAFT,
                "{\"recipe\":\"minecraft:oak_planks\",\"count\":16,\"inventory\":true}",
                List.of("logs"), Set.of(), "logs");
        queue.submitSupplied("house", WORLD, List.of(gather, craft), "block:3,64,0");
        long now = 1;
        for (int attempt = 0; attempt < 3; attempt++) {
            var work = queue.claim(WORLD, "lost", Set.of(GATHER), "house", now).job();
            now = work.expiresAt() + 1;
        }
        assertEquals(ProjectQueue.State.FAILED, queue.snapshot(now).get(0).jobs().get(0).state());
        assertNull(queue.claim(WORLD, "helper", Set.of(GATHER), "house", now,
                null, material -> false, (worker, item) -> 2, null));
        var recovered = queue.claim(WORLD, "helper", Set.of(GATHER), "house", now + 1,
                null, material -> false, (worker, item) -> 3, null).job();
        assertEquals("helper", recovered.worker());
        assertNull(recovered.resourceSite());
        now = recovered.expiresAt() + 1;
        assertEquals(ProjectQueue.State.FAILED, queue.snapshot(now).get(0).jobs().get(0).state());

        var restored = new ProjectQueue(path);
        assertNull(restored.claim(WORLD, "helper", Set.of(GATHER), "house", now,
                null, material -> false, (worker, item) -> 3, null));
        var complete = restored.claim(WORLD, "helper", Set.of(GATHER), "house", now + 1,
                null, material -> false, (worker, item) -> 4, null).job();
        assertTrue(restored.finish("house", "logs", complete.lease(), true, "Observed four logs", now + 2));
        assertEquals("planks", restored.claim(WORLD, "helper", Set.of(CRAFT), "house", now + 3).job().work().id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyReassignsSmeltingWhileTheFullBatchIsAtTheFurnace(boolean withdrawing) throws Exception {
        var file = directory.resolve("staged-furnace.json");
        var queue = new ProjectQueue(file);
        var supply = job("fuel", GATHER, List.of()).withPayload("""
                {"resource":"coal","variant":"both","item":"minecraft:coal","count":1}
                """);
        var payload = """
                {"target":{"x":2,"y":64,"z":0},"stand":{"x":1,"y":64,"z":0},
                 "block":"minecraft:furnace","recipe":"minecraft:iron_ingot_from_smelting_raw_iron",
                 "input":"minecraft:raw_iron","fuel":"minecraft:coal","count":6}
                """;
        var locks = Set.of("block:2,64,0", "block:1,64,0");
        var smelt = new ProjectQueue.Work("smelt", SMELT, payload, List.of("fuel"), locks, "fuel");
        queue.submit("batch", WORLD, List.of(supply, smelt));
        var fuel = queue.claim(WORLD, "original", Set.of(GATHER), "batch", 1).job();
        assertTrue(queue.finish("batch", "fuel", fuel.lease(), true, "Fuel ready", 2));
        var leased = queue.claim(WORLD, "original", Set.of(SMELT), "batch", 3).job();
        var staged = queue.renew("batch", "smelt", leased.lease(), null, 4, false, false, null, true);
        assertEquals(ProjectQueue.FURNACE_BATCH_STAGED, staged.evidence());
        assertNull(queue.claim(WORLD, "helper", Set.of(SMELT), "batch", 5,
                null, block -> false, null, null, 0, false, null, true));
        if (withdrawing) {
            staged = queue.renew("batch", "smelt", leased.lease(), null, 6, false, false, null, false);
            assertNull(staged.evidence());
        }

        long expired = staged.expiresAt() + 1;
        assertEquals(ProjectQueue.State.WAITING, queue.snapshot(expired).get(0).jobs().get(1).state());
        var restored = new ProjectQueue(file);
        assertNull(restored.claim(WORLD, "legacy", Set.of(SMELT), "batch", expired));
        var helper = restored.claim(WORLD, "helper", Set.of(SMELT), "batch", expired,
                null, block -> false, null, null, 0, false, null, true);
        if (withdrawing) {
            assertNull(helper);
            assertEquals("original", restored.claim(WORLD, "original", Set.of(SMELT), "batch", expired).job().worker());
        } else {
            assertNotNull(helper);
            assertEquals("helper", helper.job().worker());
            assertEquals(ProjectQueue.FURNACE_BATCH_STAGED, helper.job().evidence());
            assertNotEquals(leased.lease(), helper.job().lease());
            assertNull(helper.job().work().sameWorkerAs());
            assertEquals(List.of("fuel"), helper.job().work().needs());
            assertEquals(locks, helper.job().work().locks());
            assertFalse(restored.finish("batch", "smelt", leased.lease(), true, "Stale receipt", expired + 1));
            assertTrue(restored.finish("batch", "smelt", helper.job().lease(), true, "Collected six ingots", expired + 2));
            assertEquals("original", restored.snapshot(expired + 3).get(0).jobs().get(0).worker());
        }
    }

    @Test void reopeningPreparationReleasesAnAlreadyCompletedFurnacesReservation() throws Exception {
        var file = directory.resolve("rebuilt-furnace.json");
        String key = "block:0,64,0";
        String spec = "{\"target\":{\"x\":0,\"y\":64,\"z\":0},\"stand\":{\"x\":1,\"y\":64,\"z\":0},"
                + "\"block\":\"minecraft:furnace\",\"resumeLoadedFurnace\":true}";
        var rebuilt = new ProjectQueue.Job(new ProjectQueue.Work("rebuilt", SMELT, spec, List.of(), Set.of(key)),
                ProjectQueue.State.WAITING, 0, null, null, 0, ProjectQueue.GATHER_STALLED + "; reacquiring materials", null);
        var salvage = new ProjectQueue.Job(new ProjectQueue.Work("salvage", SMELT, spec, List.of(), Set.of(key)),
                ProjectQueue.State.WAITING, 1, null, null, 0, ProjectQueue.TARGET_TIMEOUT, null);
        var old = new ProjectQueue.Project("rebuilt", WORLD, List.of(rebuilt), null, null, java.util.Map.of());
        var current = new ProjectQueue.Project("salvage", WORLD, List.of(salvage), null, null, java.util.Map.of());
        var projects = new java.util.LinkedHashMap<String, ProjectQueue.Project>();
        projects.put(old.id(), old);
        projects.put(current.id(), current);
        Files.writeString(file, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1, "projects", projects)));

        var queue = new ProjectQueue(file);
        assertFalse(queue.snapshot(1).get(0).jobs().get(0).work().payload().contains("resumeLoadedFurnace"));
        assertNull(queue.claim(WORLD, "miner", Set.of(SMELT), "rebuilt", 2));
        assertEquals("salvage", queue.claim(WORLD, "miner", Set.of(SMELT), "salvage", 3).job().work().id());
    }

    @Test void emptyWorkerIsNotSentToAnOrdinaryFurnaceWithAnOldLoadedFuelLink() throws Exception {
        var file = directory.resolve("orphaned-furnace-fuel.json");
        var raw = new ProjectQueue.Work("raw", GATHER, """
                {"resource":"iron","variant":"both","item":"minecraft:raw_iron","count":3}
                """, List.of(), Set.of());
        var fuel = new ProjectQueue.Work("fuel", GATHER, """
                {"resource":"coal","variant":"both","item":"minecraft:coal","count":1,"fuelForLoadedFurnace":true}
                """, List.of(raw.id()), Set.of());
        var smelt = new ProjectQueue.Work("smelt", SMELT, """
                {"input":"minecraft:raw_iron","fuel":"minecraft:coal","count":3}
                """, List.of(fuel.id()), Set.of("block:0,64,0"), fuel.id());
        var project = new ProjectQueue.Project("stock", WORLD, List.of(
                new ProjectQueue.Job(raw, ProjectQueue.State.DONE, 1, "old-miner", null, 0,
                        "Observed 3 minecraft:raw_iron in worker inventory", null),
                new ProjectQueue.Job(fuel, ProjectQueue.State.DONE, 1, "courier", null, 0, "Observed coal", null),
                new ProjectQueue.Job(smelt, ProjectQueue.State.WAITING, 0, null, null, 0, null, null)),
                null, "block:2,64,0", java.util.Map.of());
        Files.writeString(file, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of("stock", project))));

        var queue = new ProjectQueue(file);
        assertNull(queue.claim(WORLD, "courier", Set.of(SMELT), "stock", 1, null, ignored -> true,
                (worker, item) -> 0, java.util.Map.of(), 13, true));
        var repaired = new ProjectQueue(file);
        var jobs = repaired.snapshot(2).get(0).jobs();
        assertTrue(jobs.stream().allMatch(j -> j.state() == ProjectQueue.State.WAITING));
        assertEquals(raw.id(), jobs.get(1).work().sameWorkerAs());
        assertFalse(jobs.get(1).work().payload().contains("fuelForLoadedFurnace"));

        var mining = repaired.claim(WORLD, "new-miner", Set.of(GATHER), "stock", 3).job();
        assertEquals(raw.id(), mining.work().id());
        assertTrue(repaired.finish("stock", mining.work().id(), mining.lease(), true,
                "Observed 3 minecraft:raw_iron in worker inventory", 4));
        assertNull(repaired.claim(WORLD, "courier", Set.of(GATHER, SMELT), "stock", 5));
        assertEquals(fuel.id(), repaired.claim(WORLD, "new-miner", Set.of(GATHER), "stock", 6).job().work().id());
    }

    @Test void idleCourierCanShareItsLastStickForAStationRouteTool() throws Exception {
        var queue = new ProjectQueue(directory.resolve("station-route-sticks.json"));
        queue.submit("batch", WORLD, List.of(job("smelt", SMELT, List.of())));
        var station = queue.claim(WORLD, "miner", Set.of(SMELT), 1).job();
        queue.recordRouteStickNeed("batch", "smelt", station.lease(), true, 2);
        queue.recordMealNeed("batch", "smelt", station.lease(), false, false, 2);
        queue.claim(WORLD, "courier", Set.of(VERIFY), 2);
        var route = queue.offerStickResupply(WORLD, "courier", null, 3,
                worker -> new ProjectQueue.ResourceSite(4, worker.equals("miner") ? 40 : 55, 7),
                (worker, item) -> 1);
        assertNotNull(route);
        var request = queue.routeToolDelivery("batch", route.job().work().id(), route.job().lease(), 4);
        assertEquals("miner", request.recipient());
        assertEquals("minecraft:stick", request.item());
        assertEquals(1, request.quantity());
        queue.recordRouteStickReceipt("batch", "smelt", station.lease(), 5);
        assertEquals(ProjectQueue.State.DONE, queue.snapshot(6).get(0).jobs().get(1).state());
    }

    @Test void loadedFurnaceRecoveryPersistsItsExactStationAndReopensOnlyFuel() throws Exception {
        var file = directory.resolve("loaded-furnace.json");
        var raw = job("raw", GATHER, List.of());
        raw = raw.withPayload("{\"resource\":\"iron\",\"variant\":\"both\",\"item\":\"minecraft:raw_iron\",\"count\":3}");
        var coal = new ProjectQueue.Work("fuel", GATHER,
                "{\"resource\":\"coal\",\"variant\":\"both\",\"item\":\"minecraft:coal\",\"count\":1}",
                List.of(raw.id()), Set.of(), raw.id());
        var smelt = new ProjectQueue.Work("smelt", SMELT,
                "{\"input\":\"minecraft:raw_iron\",\"fuel\":\"minecraft:crafting_table\",\"plannedFuel\":\"minecraft:coal\",\"count\":3}",
                List.of(coal.id()), Set.of("block:0,64,0"), coal.id());
        var queue = new ProjectQueue(file);
        var pickaxe = new ProjectQueue.Work("pickaxe", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\"}",
                List.of(smelt.id()), Set.of(), smelt.id());
        queue.submitSupplied("stock", WORLD, List.of(raw, coal, smelt, pickaxe), "block:1,64,0");
        for (var work : List.of(raw, coal)) {
            var lease = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
            assertEquals(work.id(), lease.work().id());
            assertTrue(queue.finish("stock", work.id(), lease.lease(), true, "Gathered", 2));
        }
        var first = queue.claim(WORLD, "miner", Set.of(SMELT), 3).job();
        var field = new ProjectQueue.FurnaceSite(new ProjectQueue.ResourceSite(20, 32, 10),
                new ProjectQueue.ResourceSite(19, 32, 10));
        assertTrue(queue.finish("stock", smelt.id(), first.lease(), false, "Missing furnace fuel minecraft:crafting_table",
                null, null, false, List.of(), field, 4));
        queue = new ProjectQueue(file);
        assertTrue(queue.reusableSmeltingOutputs(WORLD).isEmpty());
        queue.submit("other", WORLD, List.of(new ProjectQueue.Work("take", CRAFT, "{}",
                List.of(), Set.of("block:20,32,10"))));
        assertNull(queue.claim(WORLD, "other", Set.of(CRAFT), "other", 5));
        assertNull(queue.claim(WORLD, "unarmed", Set.of(GATHER), "stock", 6));
        var fuel = queue.claim(WORLD, "courier", Set.of(GATHER), "stock", 6, null,
                "minecraft:coal_ore"::equals).job();
        assertEquals(coal.id(), fuel.work().id());
        assertTrue(queue.finish("stock", fuel.work().id(), fuel.lease(), true, "Observed coal", 7));
        assertNull(queue.claim(WORLD, "miner", Set.of(SMELT), "stock", 8));
        var recovery = queue.claim(WORLD, "courier", Set.of(SMELT), "stock", 9).job();
        assertTrue(recovery.work().locks().contains("block:20,32,10"));
        assertFalse(recovery.work().locks().contains("block:0,64,0"));
        assertTrue(recovery.work().payload().contains("\"fuel\":\"minecraft:coal\""));
        assertEquals(ProjectQueue.State.DONE, queue.snapshot(10).stream()
                .filter(project -> project.id().equals("stock")).findFirst().orElseThrow().jobs().get(0).state());
    }

    @Test void unfinishedFurnaceDoesNotReserveTheWorkerNeededToBuildIt() throws Exception {
        var file = directory.resolve("unavailable-furnace-worker.json");
        String furnaceKey = "block:2,64,0";
        var stone = job("stone", GATHER, List.of());
        var craft = new ProjectQueue.Work("craft-furnace", CRAFT, "", List.of("stone"), Set.of("block:1,64,0"), "stone");
        var furnace = new ProjectQueue.Work("setup-furnace", BUILD,
                "{\"target\":{\"x\":2,\"y\":64,\"z\":0},\"block\":\"minecraft:furnace\"}",
                List.of(craft.id()), Set.of(furnaceKey), craft.id());
        var ore = job("ore", GATHER, List.of());
        var smelt = new ProjectQueue.Work("smelt", SMELT,
                "{\"input\":\"minecraft:raw_iron\",\"fuel\":\"minecraft:coal\",\"count\":3}",
                List.of(ore.id()), Set.of(furnaceKey), ore.id());
        var jobs = List.of(
                new ProjectQueue.Job(stone, ProjectQueue.State.DONE, 1, "previous", null, 0, "Observed", null),
                new ProjectQueue.Job(craft, ProjectQueue.State.FAILED, 3, null, null, 0, ProjectQueue.TARGET_TIMEOUT, null),
                new ProjectQueue.Job(furnace, ProjectQueue.State.WAITING, 0, null, null, 0, null, null),
                new ProjectQueue.Job(ore, ProjectQueue.State.DONE, 1, "helper", null, 0, "Observed", null),
                new ProjectQueue.Job(smelt, ProjectQueue.State.WAITING, 0, "helper", null, 100,
                        "Assigned smelting to a worker with observed supplies", null));
        var project = new ProjectQueue.Project("stock", WORLD, jobs, null, "block:3,64,0", java.util.Map.of());
        Files.writeString(file, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of("stock", project))));

        var ownerRetry = new ProjectQueue(Files.copy(file, directory.resolve("workbench-owner-retry.json")));
        assertNull(ownerRetry.claim(WORLD, "previous", Set.of(CRAFT), 1));
        var request = ownerRetry.failedWorkstationCrafts(WORLD).get(0);
        var inputs = Set.of("minecraft:cobblestone");
        assertFalse(ownerRetry.reassignTimedOutCraft(WORLD, request, "previous", inputs, false, 2));
        assertTrue(ownerRetry.reassignTimedOutCraft(WORLD, request, "previous", inputs, true, 2));
        assertFalse(ownerRetry.reassignTimedOutCraft(WORLD, request, "previous", inputs, true, 3));
        assertEquals(craft.id(), ownerRetry.claim(WORLD, "previous", Set.of(CRAFT), 3).job().work().id());

        var queue = new ProjectQueue(file);
        assertNull(queue.claim(WORLD, "helper", Set.of(SMELT), 1));
        assertTrue(queue.reassignTimedOutCraft(WORLD, queue.failedWorkstationCrafts(WORLD).get(0),
                "helper", Set.of("minecraft:cobblestone"), false, 2));
        assertTrue(queue.resumableSmelting(WORLD, 100).isEmpty());
        var craftLease = queue.claim(WORLD, "helper", Set.of(CRAFT, SMELT), 3).job();
        assertEquals(craft.id(), craftLease.work().id());
        assertTrue(queue.finish("stock", craft.id(), craftLease.lease(), true, "Observed furnace item", 4));
        var build = queue.claim(WORLD, "helper", Set.of(BUILD, SMELT), 5).job();
        assertEquals(furnace.id(), build.work().id());
        assertTrue(queue.finish("stock", furnace.id(), build.lease(), true, "Observed furnace block", 6));
        assertEquals(smelt.id(), queue.claim(WORLD, "helper", Set.of(SMELT), 7).job().work().id());
    }

    @Test void carriedFurnaceOutputReplacesEmptyPrivateWorkAndKeepsTheFollowingStageAssigned() throws Exception {
        verifyCarriedFurnaceOutput("Missing ingredients for minecraft:iron_pickaxe; reacquiring materials");
    }

    @Test void carriedFurnaceOutputCanAvoidAnUnstartedSmelt() throws Exception {
        verifyCarriedFurnaceOutput(null);
    }

    private void verifyCarriedFurnaceOutput(String smeltingEvidence) throws Exception {
        var file = directory.resolve("carried-furnace-output.json");
        var queue = new ProjectQueue(file);
        var ore = new ProjectQueue.Work("ore", GATHER,
                "{\"resource\":\"iron\",\"variant\":\"both\",\"item\":\"minecraft:raw_iron\",\"count\":3}",
                List.of(), Set.of());
        var metal = new ProjectQueue.Work("metal", SMELT,
                "{\"recipe\":\"minecraft:iron_ingot_from_smelting_raw_iron\",\"input\":\"minecraft:raw_iron\","
                        + "\"fuel\":\"minecraft:coal\",\"count\":3}", List.of("ore"), Set.of(), "ore");
        var wood = new ProjectQueue.Work("wood", GATHER,
                "{\"resource\":\"block:minecraft:oak_log\",\"variant\":\"both\",\"item\":\"minecraft:oak_log\",\"count\":1}",
                List.of("metal"), Set.of(), "metal");
        var jobs = List.of(ore, metal, wood).stream().map(work -> new ProjectQueue.Job(work,
                ProjectQueue.State.WAITING, 0, null, null, 0, work == metal ? smeltingEvidence : null, null)).toList();
        var project = new ProjectQueue.Project("stock", WORLD, jobs, null, "block:2,64,0", java.util.Map.of());
        Files.writeString(file, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of("stock", project))));
        queue = new ProjectQueue(file);
        var active = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        assertNull(queue.claim(WORLD, "holder", Set.of(SMELT), 2));
        var candidate = queue.reusableSmeltingOutputs(WORLD).get(0);
        var ingot = "minecraft:iron_ingot";
        assertFalse(queue.reuseCarriedSmeltingOutput(WORLD, candidate, "holder", ingot, 3,
                (worker, item) -> worker.equals("holder") ? 2 : 0));
        assertFalse(queue.reuseCarriedSmeltingOutput(WORLD, candidate, "holder", ingot, 4,
                (worker, item) -> worker.equals("holder") ? 3 : 1));
        assertTrue(queue.reuseCarriedSmeltingOutput(WORLD, candidate, "holder", ingot, 5,
                (worker, item) -> worker.equals("holder") ? 3 : 0));
        queue = new ProjectQueue(file);
        assertNull(queue.renew("stock", "ore", active.lease(), 6));
        assertNull(queue.claim(WORLD, "miner", Set.of(GATHER), 7));
        assertEquals("wood", queue.claim(WORLD, "holder", Set.of(GATHER), 8).job().work().id());
        assertTrue(queue.reusableSmeltingOutputs(WORLD).isEmpty());
    }

    @Test void aHungryWorkerCanReceiveAnotherMealWithoutChangingItsWorkLease() throws Exception {
        var journal = directory.resolve("food-resupply.json");
        var queue = new ProjectQueue(journal);
        queue.submit("mine", WORLD, List.of(new ProjectQueue.Work("ore", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":4}",
                List.of(), Set.of())));
        var miner = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        var field = new ProjectQueue.ResourceSite(8, 24, 0);
        assertNull(queue.claim(WORLD, "courier", Set.of(RESUPPLY), "mine", 2, field));
        assertNull(queue.offerMealResupply(WORLD, "courier", "mine", 3, "minecraft:bread", name -> field,
                (name, item) -> name.equals("courier") ? 3 : 0, false));
        queue.recordMealNeed("mine", "ore", miner.lease(), true, false, 4);
        assertNull(queue.offerMealResupply(WORLD, "courier", "mine", 5, "minecraft:bread", name -> field,
                (name, item) -> name.equals("courier") ? 2 : 0, false));
        var delivery = queue.offerMealResupply(WORLD, "courier", "mine", 6, "minecraft:bread", name -> field,
                (name, item) -> name.equals("courier") ? 3 : 0, false).job();
        assertEquals(1, queue.routeToolDelivery("mine", delivery.work().id(), delivery.lease(), 7).quantity());
        queue = new ProjectQueue(journal);
        queue.recordMealNeed("mine", "ore", miner.lease(), true, true, 8);
        var jobs = queue.snapshot(9).get(0).jobs();
        assertEquals(miner.lease(), jobs.get(0).lease());
        assertEquals(ProjectQueue.State.LEASED, jobs.get(0).state());
        assertEquals(ProjectQueue.State.DONE, jobs.get(1).state());
        assertEquals("Recipient confirmed receipt of food", jobs.get(1).evidence());
        var raw = "minecraft:beef";
        java.util.function.ToIntBiFunction<String, String> held = (name, item) -> name.startsWith("courier") ? 1 : 0;
        assertNull(queue.claim(WORLD, "courier-b", Set.of(RESUPPLY), "mine", 9, field));
        assertNull(queue.offerMealResupply(WORLD, "courier-b", "mine", 9, raw, name -> field, held, false, 1, null));
        queue.recordMealNeed("mine", "ore", miner.lease(), true, false, 10);
        var second = queue.offerMealResupply(WORLD, "courier-b", "mine", 11, raw, name -> field, held, false, 1, null).job();
        assertEquals(delivery.work().id() + "-2", second.work().id());
        assertNull(queue.offerMealResupply(WORLD, "courier", "mine", 12, raw, name -> field, held, false, 1, null));
        queue.recordMealNeed("mine", "ore", miner.lease(), false, true, 13);
        jobs = queue.snapshot(14).get(0).jobs();
        assertEquals(miner.lease(), jobs.get(0).lease());
        assertEquals(ProjectQueue.State.DONE, jobs.get(2).state());
    }

    @Test void aDistantMealDeliveryUsesTheMinersTrailWithoutChangingTheMiningLease() throws Exception {
        var queue = new ProjectQueue(directory.resolve("food-trail.json"));
        queue.submit("mine", WORLD, List.of(new ProjectQueue.Work("ore", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":4}",
                List.of(), Set.of())));
        var miner = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        var field = new ProjectQueue.ResourceSite(28, 46, 0);
        var entrance = new ProjectQueue.ResourceSite(0, 64, 0);
        var bend = new ProjectQueue.ResourceSite(14, 55, 0);
        var middle = new ProjectQueue.ResourceSite(21, 52, 0);
        java.util.function.Function<String, ProjectQueue.ResourceSite> position = name ->
                name.equals("courier") ? entrance : field;
        java.util.function.ToIntBiFunction<String, String> held = (name, item) -> name.equals("courier") ? 3 : 0;
        assertNull(queue.claim(WORLD, "courier", Set.of(RESUPPLY), "mine", 2, entrance));
        queue.recordMealNeed("mine", "ore", miner.lease(), true, false, List.of(field, entrance), 3);
        assertNull(queue.offerMealResupply(WORLD, "courier", "mine", 4, "minecraft:bread", position, held, true));
        queue.recordMealNeed("mine", "ore", miner.lease(), true, false, List.of(field, middle, bend, entrance), 5);
        assertNull(queue.offerMealResupply(WORLD, "courier", "mine", 6, "minecraft:bread", position, held, false));
        var delivery = queue.offerMealResupply(WORLD, "courier", "mine", 7, "minecraft:bread", position, held, true).job();
        assertEquals(List.of(entrance, bend, middle, field),
                queue.routeToolDelivery("mine", delivery.work().id(), delivery.lease(), 8).waypoints());
        assertEquals(miner.lease(), queue.snapshot(9).get(0).jobs().get(0).lease());
    }

    @Test void oneFoodAnimalCanSupplyOnlyOneActiveCourierEvenAcrossProjects() throws Exception {
        var queue = new ProjectQueue(directory.resolve("food-animal.json"));
        var gather = new ProjectQueue.Work("ore", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":4}",
                List.of(), Set.of());
        var field = new ProjectQueue.ResourceSite(8, 24, 0);
        var animal = new FieldForage.Source(java.util.UUID.randomUUID(), field, "minecraft:beef");
        for (var suffix : List.of("a", "b")) {
            String project = "mine-" + suffix;
            queue.submit(project, WORLD, List.of(gather));
            var miner = queue.claim(WORLD, "miner-" + suffix, Set.of(GATHER), project, 1, field).job();
            queue.recordMealNeed(project, "ore", miner.lease(), true, false, 2);
            assertNull(queue.claim(WORLD, "courier-" + suffix, Set.of(RESUPPLY), project, 2, field));
        }
        var delivery = queue.offerMealResupply(WORLD, "courier-a", "mine-a", 3, animal.item(),
                name -> field, (name, item) -> 0, true, 0, animal).job();
        assertEquals(animal, queue.routeToolDelivery("mine-a", delivery.work().id(), delivery.lease(), 4).forage());
        assertNull(queue.offerMealResupply(WORLD, "courier-b", "mine-b", 4, animal.item(),
                name -> field, (name, item) -> 0, true, 0, animal));
    }

    @Test void spareSticksCanBeDeliveredOnceWithoutMovingTheToolLease() throws Exception {
        var journal = directory.resolve("stick-resupply.json");
        var queue = new ProjectQueue(journal);
        queue.submit("tools", WORLD, pickaxeStickPreparation());
        var miner = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        var field = new ProjectQueue.ResourceSite(8, 64, 0);
        assertNull(queue.claim(WORLD, "courier", Set.of(RESUPPLY), "tools", 2, field));
        assertNull(queue.offerStickResupply(WORLD, "courier", "tools", 3,
                name -> name.equals("miner") ? new ProjectQueue.ResourceSite(8, 44, 0) : field,
                (name, item) -> name.equals("courier") ? 4 : 0));
        assertNull(queue.offerStickResupply(WORLD, "courier", "tools", 4, name -> field,
                (name, item) -> name.equals("courier") ? 3 : 0));
        var loan = queue.offerStickResupply(WORLD, "courier", "tools", 5, name -> field,
                (name, item) -> name.equals("courier") ? 4 : 0).job();
        assertEquals("miner", queue.routeToolDelivery("tools", loan.work().id(), loan.lease(), 6).recipient());
        assertEquals("miner", queue.routeResupplyRecipient(WORLD, "tools", loan.work().id(), loan.lease(), 6));
        assertNull(queue.routeResupplyRecipient("other|overworld", "tools", loan.work().id(), loan.lease(), 6));
        assertNull(queue.routeResupplyRecipient(WORLD, "tools", loan.work().id(), java.util.UUID.randomUUID(), 6));
        assertNotNull(queue.routeToolDelivery("tools", loan.work().id(), loan.lease(), miner.expiresAt()));
        assertNull(queue.routeResupplyRecipient(WORLD, "tools", loan.work().id(), loan.lease(), miner.expiresAt()));
        assertEquals(2, queue.routeToolDelivery("tools", loan.work().id(), loan.lease(), 6).quantity());
        assertNull(queue.claim(WORLD, "alternate", Set.of(RESUPPLY), "tools", 7, field));
        assertNull(queue.offerStickResupply(WORLD, "alternate", "tools", 8, name -> field,
                (name, item) -> name.equals("alternate") ? 4 : 0));
        queue = new ProjectQueue(journal);
        assertTrue(queue.finish("tools", "log", miner.lease(), true, ProjectQueue.PREPARED_COMPATIBLE_STICKS, 9));
        assertEquals(ProjectQueue.PREPARED_REQUIRED_STICKS, queue.snapshot(10).get(0).jobs().get(0).evidence());
        assertTrue(queue.finish("tools", "log", miner.lease(), true, ProjectQueue.PREPARED_COMPATIBLE_STICKS, 10));
        assertNull(queue.routeToolDelivery("tools", loan.work().id(), loan.lease(), 10));
        assertNull(queue.routeResupplyRecipient(WORLD, "tools", loan.work().id(), loan.lease(), 10));
        assertEquals("pickaxe", queue.claim(WORLD, "miner", Set.of(CRAFT), 11).job().work().id());
    }

    @Test void aCourierCanCraftMissingSticksFromCarriedPlanksAndRetainItsOwnPair() throws Exception {
        var queue = new ProjectQueue(directory.resolve("crafting-stick-courier.json"));
        queue.submit("tools", WORLD, pickaxeStickPreparation());
        var mining = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        var local = new ProjectQueue.ResourceSite(8, 64, 0);
        String planks = "minecraft:oak_planks";
        assertNull(queue.claim(WORLD, "courier", Set.of(RESUPPLY), "tools", 2, local));
        assertNull(queue.offerStickResupply(WORLD, "courier", "tools", 3, name -> local,
                (name, item) -> name.equals("courier") && item.equals(planks) ? 1 : 0, Set.of(planks)));
        var loan = queue.offerStickResupply(WORLD, "courier", "tools", 4, name -> local,
                (name, item) -> name.equals("courier") && item.equals(planks) ? 2 : 0, Set.of(planks)).job();
        var delivery = queue.routeToolDelivery("tools", loan.work().id(), loan.lease(), 5);
        assertEquals(4, delivery.craftTarget());
        assertEquals(2, delivery.quantity());
        assertEquals("miner", delivery.recipient());
        assertEquals(mining.lease(), queue.claim(WORLD, "miner", Set.of(GATHER), "tools", 6).job().lease());
    }

    private static List<ProjectQueue.Work> pickaxeStickPreparation() {
        return List.of(
                new ProjectQueue.Work("log", GATHER,
                        "{\"resource\":\"block:minecraft:oak_log\",\"variant\":\"both\",\"item\":\"minecraft:oak_log\",\"count\":1}",
                        List.of(), Set.of()),
                new ProjectQueue.Work("planks", CRAFT, "{\"recipe\":\"minecraft:oak_planks\",\"inventory\":true,\"count\":2}",
                        List.of("log"), Set.of(), "log"),
                new ProjectQueue.Work("sticks", CRAFT, "{\"recipe\":\"minecraft:stick\",\"inventory\":true,\"count\":2}",
                        List.of("planks"), Set.of(), "planks"),
                new ProjectQueue.Work("pickaxe", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\",\"count\":1}",
                        List.of("sticks"), Set.of(), "sticks"));
    }

    @Test void compatibleSticksCanSatisfyOnlyAStandaloneWoodPreparationChain() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            boolean eligible = mode == 0;
            int planks = mode == 2 ? 4 : 2;
            var journal = directory.resolve("stick-prep-" + mode + ".json");
            var queue = new ProjectQueue(journal);
            var work = new ArrayList<>(List.of(
                    new ProjectQueue.Work("log", GATHER,
                            "{\"resource\":\"block:minecraft:oak_log\",\"variant\":\"both\",\"item\":\"minecraft:oak_log\",\"count\":1}",
                            List.of(), Set.of()),
                    new ProjectQueue.Work("planks", CRAFT, "{\"recipe\":\"minecraft:oak_planks\",\"inventory\":true,\"count\":" + planks + "}",
                            List.of("log"), Set.of(), "log"),
                    new ProjectQueue.Work("sticks", CRAFT, "{\"recipe\":\"minecraft:stick\",\"inventory\":true,\"count\":2}",
                            List.of("planks"), Set.of(), "planks"),
                    new ProjectQueue.Work("pickaxe", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\",\"count\":1}",
                            List.of("sticks"), Set.of(), "sticks")));
            if (mode == 1) work.add(job("other-consumer", BUILD, List.of("planks")));
            queue.submit("tools", WORLD, work);
            var leased = queue.claim(WORLD, "miner", Set.of(GATHER), 1);
            assertEquals(eligible ? 2 : 0, leased.flexibleStickCount());
            queue = new ProjectQueue(journal);
            assertEquals(eligible, queue.finish("tools", "log", leased.job().lease(), true,
                    ProjectQueue.PREPARED_COMPATIBLE_STICKS, 2));
            if (eligible) {
                queue = new ProjectQueue(journal);
                assertEquals("pickaxe", queue.claim(WORLD, "miner", Set.of(CRAFT), 3).job().work().id());
                assertNull(queue.claim(WORLD, "other", Set.of(CRAFT), 3));
            }
        }
    }

    @Test void inventoryCraftCanProceedWhileAnotherWorkerOwnsTheBench() throws Exception {
        var path = directory.resolve("portable.json");
        var queue = new ProjectQueue(path);
        String station = """
                {"target":{"x":5,"y":64,"z":2},"stand":{"x":5,"y":64,"z":3},"block":"minecraft:crafting_table",
                 "recipe":"minecraft:%s","count":1}
                """;
        var cells = Set.of("block:5,64,2", "block:5,64,3", "block:5,63,3", "block:5,65,3");
        var bench = new ProjectQueue.Work("tool", CRAFT, station.formatted("stone_pickaxe"), List.of(), cells);
        var locks = new java.util.HashSet<>(cells);
        locks.add("reserved-cargo");
        var sticks = new ProjectQueue.Work("sticks", CRAFT, station.formatted("stick"), List.of(), locks);
        queue.submit("crew", WORLD, List.of(bench, sticks));
        var leased = queue.claim(WORLD, "Alpha", Set.of(CRAFT), 1).job();
        assertEquals("tool", leased.work().id());
        assertNull(queue.claim(WORLD, "Beta", Set.of(CRAFT), 2));
        queue.releasePortableCraftStations(WORLD, "minecraft:stick"::equals);
        queue = new ProjectQueue(path);
        assertEquals(leased, queue.claim(WORLD, "Alpha", Set.of(CRAFT), 3).job());
        var portable = queue.claim(WORLD, "Beta", Set.of(CRAFT), 4).job();
        assertEquals("sticks", portable.work().id());
        assertEquals(Set.of("reserved-cargo"), portable.work().locks());
        assertTrue(portable.work().payload().contains("\"inventory\":true"));
        assertFalse(portable.work().payload().contains("stand"));
    }

    @Test void stockBatchSizeUsesAvailableWorkersAndPreservesSmallOrders() {
        assertEquals(13, OutputManifest.batchLimit(128, 10));
        assertEquals(64, OutputManifest.batchLimit(128, 1));
        assertEquals(64, OutputManifest.batchLimit(128, 0));
        assertEquals(8, OutputManifest.batchLimit(16, 10));
        assertEquals(3, OutputManifest.batchLimit(3, 10));
    }

    @Test void savedManifestStartsDifferentMaterialsBeforeMoreBatchesOfTheFirstItem() throws Exception {
        var journal = directory.resolve("ready-materials.json");
        var queue = new ProjectQueue(journal);
        String project = "manifest-materials";
        String chest = "block:6,64,0";
        String dirt = """
                {"resource":"block:minecraft:dirt","variant":"both","item":"minecraft:dirt","count":32}
                """;
        String logs = """
                {"resource":"any_logs","variant":"both","item":"any_logs","count":13}
                """;
        var first = job("stock-0-0-produce-0", GATHER, List.of()).withPayload(dirt);
        var second = job("stock-0-1-produce-0", GATHER, List.of()).withPayload(dirt);
        var wood = job("stock-1-0-gather", GATHER, List.of()).withPayload(logs);
        var bank = new ProjectQueue.Work("stock-0-0-deposit", TRANSFER,
                """
                {"direction":"DEPOSIT","item":"minecraft:dirt","stock":32}
                """, List.of(first.id()), Set.of(chest), first.id());
        queue.submitSupplied(project, WORLD, List.of(first, bank, second, wood), chest);

        queue = new ProjectQueue(journal);
        var gathering = queue.claim(WORLD, "Alpha", Set.of(GATHER, TRANSFER), project, 1).job();
        assertEquals(first.id(), gathering.work().id());
        assertEquals(wood.id(), queue.claim(WORLD, "Beta", Set.of(GATHER, TRANSFER), project, 2).job().work().id());
        assertTrue(queue.finish(project, first.id(), gathering.lease(), true,
                "Observed 32 minecraft:dirt in worker inventory", 3));
        assertEquals(bank.id(), queue.claim(WORLD, "Alpha", Set.of(GATHER, TRANSFER), project, 4).job().work().id());
        assertEquals(second.id(), queue.claim(WORLD, "Gamma", Set.of(GATHER, TRANSFER), project, 5).job().work().id());
    }

    @Test void idleWorkerCanShareOnlyTheOutstandingStockAcrossIndependentToolChains() throws Exception {
        var path = directory.resolve("shared.json");
        var queue = new ProjectQueue(path);
        String project = "manifest-example";
        String chest = "block:6,64,0";
        String stone = "{\"resource\":\"stone\",\"variant\":\"both\",\"item\":\"minecraft:cobblestone\",\"count\":3}";
        String ore = "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":57}";
        String bank = "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":64}";
        var root = new ProjectQueue.Work("materials", GATHER, stone, List.of(), Set.of());
        var craft = new ProjectQueue.Work("pickaxe", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\",\"count\":1}",
                List.of(root.id()), Set.of("bench"), root.id());
        var gather = new ProjectQueue.Work("ore", GATHER, ore, List.of(craft.id()), Set.of(), craft.id());
        var deposit = new ProjectQueue.Work("stock-0-0-deposit", TRANSFER, bank, List.of(gather.id()), Set.of(chest), gather.id());
        queue.submitSupplied(project, WORLD, List.of(root, craft, gather, deposit), chest);
        long now = 1;
        for (int attempt = 0; attempt < 3; attempt++) {
            var job = queue.claim(WORLD, "Alpha", Set.of(GATHER), project, now++).job();
            assertNull(queue.claim(WORLD, "Beta", Set.of(GATHER), project, now++));
            assertFalse(queue.shareBlockedStock(WORLD, "Beta", project, now++));
            assertTrue(queue.finish(project, job.work().id(), job.lease(), false, "Materials unavailable", now++));
        }
        assertTrue(queue.shareBlockedStock(WORLD, "Beta", project, now++));
        var first = queue.claim(WORLD, "Alpha", Set.of(GATHER), project, now++).job();
        var second = queue.claim(WORLD, "Beta", Set.of(GATHER), project, now++).job();
        assertNotEquals(first.work().id(), second.work().id());
        queue = new ProjectQueue(path);
        var jobs = queue.snapshot(now++).get(0).jobs();
        assertEquals(8, jobs.size());
        assertEquals(2, jobs.stream().filter(j -> j.state() == ProjectQueue.State.LEASED).count());
        var gson = new com.google.gson.Gson();
        var quotas = jobs.stream().map(ProjectQueue.Job::work).filter(w -> w.kind() == GATHER && w.payload().contains("diamond"))
                .map(w -> gson.fromJson(w.payload(), com.google.gson.JsonObject.class).get("count").getAsInt()).sorted().toList();
        assertEquals(List.of(28, 29), quotas);
        var deposits = jobs.stream().map(ProjectQueue.Job::work).filter(w -> w.kind() == TRANSFER).toList();
        assertEquals(35, gson.fromJson(deposits.get(0).payload(), com.google.gson.JsonObject.class).get("stock").getAsInt());
        assertEquals(deposit.id(), deposits.get(1).id());
        assertTrue(deposits.get(1).needs().contains(deposits.get(0).id()));
        assertEquals(Set.of(chest), deposits.get(0).locks());
        assertFalse(queue.shareBlockedStock(WORLD, "Beta", project, now));
    }

    @Test void repairsLostSchematicSupportAndReopensItsBlockedDependent() throws Exception {
        var journal = directory.resolve("destroyed-wall.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        queue.submitSupplied("house", WORLD, List.of(job("gather", GATHER, List.of()),
                new ProjectQueue.Work("pickup", TRANSFER, "{\"direction\":\"TAKE\",\"item\":\"minecraft:oak_log\",\"stock\":2}",
                        List.of("gather"), Set.of(chest), null, null, "minecraft:oak_log"),
                new ProjectQueue.Work("place-0", BUILD, "", List.of("pickup"), Set.of("lower"), "pickup"),
                new ProjectQueue.Work("place-support-1", BUILD, "", List.of("pickup"), Set.of("temporary"), "pickup"),
                new ProjectQueue.Work("place-1", BUILD, "", List.of("place-0", "pickup"), Set.of("upper"), "pickup")), chest);
        long now = 1;
        var gather = queue.claim(WORLD, "builder", Set.of(GATHER), now++).job();
        assertTrue(queue.finish("house", "gather", gather.lease(), true, "Collected", now++));
        var pickup = queue.claim(WORLD, "builder", Set.of(TRANSFER), now++).job();
        assertTrue(queue.finish("house", "pickup", pickup.lease(), true, "Collected",
                new ProjectQueue.ChestReport(chest, java.util.Map.of()), now++));
        for (String id : List.of("place-0", "place-support-1")) {
            var placed = queue.claim(WORLD, "builder", Set.of(BUILD), now++).job();
            assertEquals(id, placed.work().id());
            assertTrue(queue.finish("house", id, placed.lease(), true, "Placed", now++));
        }
        for (int retry = 0; retry < 3; retry++) {
            var upper = queue.claim(WORLD, "builder", Set.of(BUILD), now++).job();
            assertEquals("place-1", upper.work().id());
            assertFalse(queue.repairMissingPlacement(WORLD, "house", "place-0"));
            assertTrue(queue.finish("house", "place-1", upper.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
            now += ProjectQueue.PLACEMENT_HANDOFF_WINDOW_MS;
        }
        assertEquals(List.of("place-0"), queue.ownedPlacements(WORLD).stream().map(ProjectQueue.OwnedPlacement::job).toList());
        assertTrue(queue.repairMissingPlacement(WORLD, "house", "place-0"));
        var restored = new ProjectQueue(journal);
        var lower = restored.claim(WORLD, "builder", Set.of(BUILD), now++).job();
        assertEquals("place-0", lower.work().id());
        assertTrue(restored.finish("house", "place-0", lower.lease(), true, "Rebuilt lower support", now++));
        assertEquals("place-1", restored.claim(WORLD, "builder", Set.of(BUILD), now).job().work().id());
        assertEquals(ProjectQueue.State.DONE, restored.snapshot(now).get(0).jobs().get(3).state());
    }

    @Test void savedManifestGathersInParallelAndKeepsCumulativeDepositsOrdered() throws Exception {
        var journal = directory.resolve("parallel-manifest.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String stock = "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:dirt\",\"stock\":";
        queue.submitSupplied("manifest-test", WORLD, List.of(job("first", GATHER, List.of()),
                new ProjectQueue.Work("deliver-first", TRANSFER, stock + "64}", List.of("first"), Set.of(chest), "first"),
                job("second", GATHER, List.of("deliver-first")),
                new ProjectQueue.Work("deliver-second", TRANSFER, stock + "128}", List.of("second"), Set.of(chest), "second"),
                job("other-work", EXCAVATE, List.of())), chest);
        var first = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        var restored = new ProjectQueue(journal);
        var second = restored.claim(WORLD, "hauler", Set.of(GATHER), 2).job();
        assertEquals("second", second.work().id());
        assertTrue(restored.finish("manifest-test", "second", second.lease(), true, "Gathered", 3));
        assertNull(restored.claim(WORLD, "hauler", Set.of(TRANSFER, EXCAVATE), 4));
        assertTrue(restored.finish("manifest-test", "first", first.lease(), true, "Gathered", 5));
        var receipt = new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:dirt", 64));
        var firstDeposit = restored.claim(WORLD, "miner", Set.of(TRANSFER), 6).job();
        assertEquals("deliver-first", firstDeposit.work().id());
        assertTrue(restored.finish("manifest-test", "deliver-first", firstDeposit.lease(), true, "Deposited", receipt, 7));
        var secondDeposit = restored.claim(WORLD, "hauler", Set.of(TRANSFER), 8).job();
        assertEquals("deliver-second", secondDeposit.work().id());
        assertTrue(restored.finish("manifest-test", "deliver-second", secondDeposit.lease(), false,
                "Missing cargo", receipt, null, true, 9));
        var jobs = new ProjectQueue(journal).snapshot(10).get(0).jobs();
        assertEquals(ProjectQueue.State.DONE, jobs.get(1).state());
        assertEquals(ProjectQueue.State.WAITING, jobs.get(2).state());
        assertEquals(List.of("second", "deliver-first"), jobs.get(3).work().needs());
    }

    @Test void aUsableCarriedPickaxeSatisfiesOnlyPrivateGatherPreparation() throws Exception {
        for (boolean stockOutput : new boolean[]{false, true}) {
            var queue = new ProjectQueue(directory.resolve("existing-pickaxe-" + stockOutput + ".json"));
            String chest = "block:3,64,0";
            var wood = job("wood", GATHER, List.of());
            var craft = new ProjectQueue.Work("pick", CRAFT,
                    "{\"recipe\":\"minecraft:stone_pickaxe\",\"count\":1}", List.of(wood.id()), Set.of("block:4,64,0"), wood.id());
            var next = stockOutput ? new ProjectQueue.Work("next", TRANSFER,
                    "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:stone_pickaxe\",\"stock\":1}",
                    List.of(craft.id()), Set.of(chest), craft.id()) : new ProjectQueue.Work("next", GATHER,
                    "{\"resource\":\"iron\",\"variant\":\"both\",\"item\":\"minecraft:raw_iron\",\"count\":3}",
                    List.of(craft.id()), Set.of(), craft.id());
            queue.submitSupplied("tools", WORLD, List.of(wood, craft, next), chest);
            var prep = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
            assertTrue(queue.finish("tools", wood.id(), prep.lease(), true, "Collected", 2));
            var first = queue.claim(WORLD, "miner", Set.of(CRAFT, GATHER, TRANSFER), null, 3, null,
                    item -> false, null, java.util.Map.of("minecraft:stone_pickaxe", 31)).job();
            assertEquals(craft.id(), first.work().id());
            var work = queue.claim(WORLD, "miner", Set.of(CRAFT, GATHER, TRANSFER), null, 4, null,
                    item -> false, null, java.util.Map.of("minecraft:stone_pickaxe", 32)).job();
            assertEquals(stockOutput ? craft.id() : next.id(), work.work().id());
            if (!stockOutput) assertEquals("Gathering tool already carried: minecraft:stone_pickaxe",
                    queue.snapshot(4).get(0).jobs().get(1).evidence());
        }
    }

    @Test void aCarriedPickaxeReplacesOnlyPrivateActivePreparation() throws Exception {
        for (boolean shared : List.of(false, true)) {
            var queue = new ProjectQueue(directory.resolve("active-tool-" + shared + ".json"));
            var jobs = new java.util.ArrayList<>(List.of(
                    job("wood", GATHER, List.of()),
                    new ProjectQueue.Work("smelt", SMELT, "{\"recipe\":\"minecraft:iron_ingot_from_smelting_raw_iron\"}",
                            List.of("wood"), Set.of(), "wood"),
                    new ProjectQueue.Work("pickaxe", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\",\"count\":1}",
                            List.of("smelt"), Set.of(), "smelt"),
                    new ProjectQueue.Work("diamonds", GATHER,
                            "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":1}",
                            List.of("pickaxe"), Set.of(), "pickaxe")));
            if (shared) jobs.add(job("public-stock", GATHER, List.of("smelt")));
            queue.submitSupplied("stock", WORLD, jobs, "block:3,64,0");
            var wood = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
            assertFalse(queue.reuseToolDuringGathering("stock", "wood", wood.lease(),
                    java.util.Map.of("minecraft:stone_pickaxe", 100), 2));
            assertFalse(queue.reuseToolDuringGathering("stock", "wood", wood.lease(),
                    java.util.Map.of("minecraft:iron_pickaxe", 31), 2));
            assertEquals(!shared, queue.reuseToolDuringGathering("stock", "wood", wood.lease(),
                    java.util.Map.of("minecraft:iron_pickaxe", 64), 3));
            assertEquals(shared ? "wood" : "diamonds", queue.claim(WORLD, "miner", Set.of(GATHER), 4).job().work().id());
        }
    }

    @Test void aHarvestPickaxeAloneCannotReplaceAProspectingKit() throws Exception {
        var queue = new ProjectQueue(directory.resolve("prospecting-kit.json"));
        queue.submitSupplied("stock", WORLD, List.of(
                job("wood", GATHER, List.of()),
                new ProjectQueue.Work("route-tools", CRAFT, """
                        {"recipe":"minecraft:stone_pickaxe","count":4}
                        """, List.of("wood"), Set.of(), "wood"),
                new ProjectQueue.Work("portable-table", CRAFT, """
                        {"recipe":"minecraft:crafting_table","count":1,"inventory":true}
                        """, List.of("route-tools"), Set.of(), "route-tools"),
                new ProjectQueue.Work("spare-sticks", CRAFT, """
                        {"recipe":"minecraft:stick","count":8,"inventory":true}
                        """, List.of("portable-table"), Set.of(), "portable-table"),
                new ProjectQueue.Work("harvest-tool", CRAFT, """
                        {"recipe":"minecraft:iron_pickaxe","count":1}
                        """, List.of("spare-sticks"), Set.of(), "spare-sticks"),
                new ProjectQueue.Work("ore", GATHER, """
                        {"resource":"diamond","variant":"both","item":"minecraft:diamond","count":6}
                        """, List.of("harvest-tool"), Set.of(), "harvest-tool")),
                "block:3,64,0");
        var wood = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        assertFalse(queue.reuseToolDuringGathering("stock", "wood", wood.lease(),
                java.util.Map.of("minecraft:iron_pickaxe", 50), 2));
        var stillActive = queue.claim(WORLD, "miner", Set.of(GATHER), 3).job();
        assertEquals(wood.lease(), stillActive.lease());
        assertNull(queue.claim(WORLD, "other", Set.of(GATHER, CRAFT), 4));

        var picks = java.util.Map.of("minecraft:iron_pickaxe", 50, "minecraft:stone_pickaxe", 512);
        assertFalse(queue.reuseToolDuringGathering("stock", "wood", wood.lease(), picks,
                item -> 0, 5));
        assertFalse(queue.reuseToolDuringGathering("stock", "wood", wood.lease(), picks,
                item -> item.equals("minecraft:crafting_table") ? 1 : 0, 6));
        var supplies = java.util.Map.of("minecraft:crafting_table", 1, "minecraft:stick", 6);
        assertTrue(queue.reuseToolDuringGathering("stock", "wood", wood.lease(), picks,
                item -> supplies.getOrDefault(item, 0), 7));
        assertEquals("ore", queue.claim(WORLD, "miner", Set.of(GATHER), 8).job().work().id());
        assertFalse(queue.finish("stock", "wood", wood.lease(), true, "Late gather report", 9));
    }

    @Test void aWornPickaxeCanReachAnExistingMiningCheckpoint() throws Exception {
        var queue = new ProjectQueue(directory.resolve("mining-checkpoint-tool.json"));
        String chest = "block:3,64,0";
        queue.submitSupplied("stock", WORLD, List.of(
                job("wood", GATHER, List.of()),
                new ProjectQueue.Work("pickaxe", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\",\"count\":1}",
                        List.of("wood"), Set.of(), "wood"),
                new ProjectQueue.Work("diamonds", GATHER,
                        "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":8}",
                        List.of("pickaxe"), Set.of(), "pickaxe"),
                new ProjectQueue.Work("bank", TRANSFER,
                        "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":8}",
                        List.of("diamonds"), Set.of(chest), "diamonds")), chest);
        var wood = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        assertFalse(queue.reuseToolDuringGathering("stock", "wood", wood.lease(),
                java.util.Map.of("minecraft:iron_pickaxe", 24), 2));
        assertTrue(queue.reuseToolDuringGathering("stock", "wood", wood.lease(),
                java.util.Map.of("minecraft:iron_pickaxe", 25), 3));
        var mining = queue.claim(WORLD, "miner", Set.of(GATHER), 4);
        assertEquals("diamonds", mining.job().work().id());
        assertEquals(4, mining.cargoCheckpoint());
    }

    @Test void exhaustedMiningCanResumeWithNewApproachRulesOrBetterEquipment() throws Exception {
        var journal = directory.resolve("equipped-checkpoint-recovery.json");
        var queue = exhaustedDiamondQueue(journal);
        var footing = new ProjectQueue.ResourceSite(4, -52, 4);
        var worn = java.util.Map.of("minecraft:iron_pickaxe", 31);
        assertNull(queue.offerEquippedMiner(WORLD, "miner", "stock", worn, 2, footing));
        var attempt = queue.offerEquippedMiner(WORLD, "miner", "stock", worn, 3, footing, true);
        assertEquals(footing, attempt.job().resourceSite());
        assertEquals(4, attempt.cargoCheckpoint());
        assertTrue(queue.finish("stock", "ore", attempt.job().lease(), false,
                ProjectQueue.GATHER_TOOLS_NEEDED, null, footing, 4));

        queue = new ProjectQueue(journal);
        assertNull(queue.offerEquippedMiner(WORLD, "miner", "stock", worn, 5, footing, true));
        var renewed = java.util.Map.of("minecraft:iron_pickaxe", 40);
        assertEquals("ore", queue.offerEquippedMiner(WORLD, "miner", "stock", renewed, 6, footing, true).job().work().id());
    }

    @Test void exhaustedMiningCanUseAWornPickaxeAtAConfirmedReachableVein() throws Exception {
        var journal = directory.resolve("observed-ore-after-exhaustion.json");
        var queue = exhaustedDiamondQueue(journal);
        var footing = new ProjectQueue.ResourceSite(4, -52, 4);
        var ore = new ProjectQueue.ReachableOre("minecraft:deepslate_diamond_ore", new ProjectQueue.ResourceSite(6, -52, 4));
        var worn = java.util.Map.of("minecraft:iron_pickaxe", 4);
        queue.claim(WORLD, "miner", Set.of(VERIFY), 2);
        assertNull(queue.offerEquippedMiner(WORLD, "miner", "stock", worn, 3, footing, true));
        assertNull(queue.offerObservedOreRecovery(WORLD, "miner", "stock", footing,
                java.util.Map.of("minecraft:iron_pickaxe", 3), List.of(ore), 3));
        assertNull(queue.offerObservedOreRecovery(WORLD, "miner", "stock", footing,
                java.util.Map.of("minecraft:stone_pickaxe", 100), List.of(ore), 3));
        var attempt = queue.offerObservedOreRecovery(WORLD, "miner", "stock", footing, worn, List.of(ore), 3);
        assertEquals("ore", attempt.job().work().id());
        assertEquals(footing, attempt.job().resourceSite());
        assertEquals(4, attempt.cargoCheckpoint());
        assertTrue(queue.finish("stock", "ore", attempt.job().lease(), false,
                ProjectQueue.GATHER_TOOLS_NEEDED, null, footing, 4));
        queue = new ProjectQueue(journal);
        queue.claim(WORLD, "miner", Set.of(VERIFY), 5);
        assertNull(queue.offerObservedOreRecovery(WORLD, "miner", "stock", footing, worn, List.of(ore), 6));
    }

    @Test void aWorkerWithSmeltedToolMaterialsOnlyRepeatsTheRemainingPreparation() throws Exception {
        var journal = directory.resolve("retained-tool-materials.json");
        String chest = "block:3,64,0";
        var raw = job("raw", GATHER, List.of());
        var smelt = new ProjectQueue.Work("smelt", SMELT, "{\"count\":3}", List.of("raw"), Set.of(), "raw");
        var wood = new ProjectQueue.Work("wood", GATHER,
                "{\"resource\":\"any_logs\",\"item\":\"any_logs\",\"variant\":\"both\",\"count\":1}",
                List.of("smelt"), Set.of(), "smelt");
        var sticks = new ProjectQueue.Work("sticks", CRAFT, "{\"recipe\":\"minecraft:stick\",\"count\":2}",
                List.of("wood"), Set.of(), "wood");
        var pickaxe = new ProjectQueue.Work("pickaxe", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\",\"count\":1}",
                List.of("sticks"), Set.of(), "sticks");
        var ore = new ProjectQueue.Work("ore", GATHER,
                "{\"resource\":\"diamond\",\"item\":\"minecraft:diamond\",\"count\":8}",
                List.of("pickaxe"), Set.of(), "pickaxe");
        var bank = new ProjectQueue.Work("bank", TRANSFER, "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":8}",
                List.of("ore"), Set.of(chest), "ore");
        var completed = List.of(raw, smelt, wood, sticks, pickaxe).stream().map(work ->
                new ProjectQueue.Job(work, ProjectQueue.State.DONE, 1, "old", null, 0, "Completed", null)).toList();
        var jobs = new ArrayList<>(completed);
        var oldSite = new ProjectQueue.ResourceSite(7, -51, 9);
        jobs.add(new ProjectQueue.Job(ore, ProjectQueue.State.FAILED, 3, null, null, 0,
                ProjectQueue.GATHER_TOOLS_NEEDED + " after 2 supply recoveries", null, oldSite));
        jobs.add(new ProjectQueue.Job(bank, ProjectQueue.State.WAITING, 0, null, null, 0, null, null));
        var saved = new ProjectQueue.Project("stock", WORLD, jobs, null, chest, java.util.Map.of("raw", 2));
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of(saved.id(), saved))));

        var queue = new ProjectQueue(journal);
        queue.claim(WORLD, "helper", Set.of(VERIFY), "stock", 10);
        var here = new ProjectQueue.ResourceSite(8, 64, 8);
        var material = "minecraft:iron_ingot";
        assertFalse(queue.reuseRetainedToolMaterials(WORLD, "helper", here, java.util.Map.of(material, 2),
                11, (tool, furnace) -> material));
        assertTrue(queue.reuseRetainedToolMaterials(WORLD, "helper", here, java.util.Map.of(material, 3),
                11, (tool, furnace) -> tool.equals(pickaxe) && furnace.equals(smelt) ? material : null));
        queue = new ProjectQueue(journal);
        assertEquals(completed.get(0), queue.snapshot(12).get(0).jobs().get(0));
        assertEquals("helper", queue.snapshot(12).get(0).jobs().get(1).worker());
        assertNull(queue.claim(WORLD, "old", Set.of(GATHER), "stock", 12));
        var gathering = queue.claim(WORLD, "helper", Set.of(GATHER), "stock", 12);
        assertEquals(wood, gathering.job().work());
        assertEquals(here, gathering.job().resourceSite());
        assertFalse(queue.reuseToolDuringGathering("stock", "wood", gathering.job().lease(),
                java.util.Map.of("minecraft:iron_pickaxe", 25), 13));
        var highFooting = new ProjectQueue.ResourceSite(8, 74, 9);
        for (int retry = 0; retry < 3; retry++) {
            assertTrue(queue.finish("stock", "wood", gathering.job().lease(), false, ProjectQueue.GATHER_STALLED,
                    null, highFooting, 14 + retry * 2));
            if (retry < 2) gathering = queue.claim(WORLD, "helper", Set.of(GATHER), "stock", 15 + retry * 2);
        }
        // Old journals used a different receipt, but the worker still has the confirmed material.
        var persisted = com.google.gson.JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
        persisted.getAsJsonObject("projects").getAsJsonObject("stock").getAsJsonArray("jobs").get(1).getAsJsonObject()
                .addProperty("evidence", "Observed 3 minecraft:iron_ingot carried for replacement tools");
        Files.writeString(journal, persisted.toString());
        queue = new ProjectQueue(journal);
        long resumedAt = 30_019;
        var restarted = queue.claim(WORLD, "helper", Set.of(GATHER), "stock", resumedAt, here,
                ignored -> true, (worker, item) -> worker.equals("helper") && item.equals(material) ? 3 : 0, null);
        assertEquals(wood, restarted.job().work());
        assertEquals(highFooting, restarted.job().resourceSite());
        assertEquals(ProjectQueue.State.DONE, queue.snapshot(resumedAt).get(0).jobs().get(1).state());
        assertTrue(queue.finish("stock", "wood", restarted.job().lease(), true, "Collected a log", resumedAt + 1));
        var crafting = queue.claim(WORLD, "helper", Set.of(CRAFT), "stock", resumedAt + 2);
        assertEquals(sticks, crafting.job().work());
        assertTrue(queue.finish("stock", "sticks", crafting.job().lease(), true, "Crafted sticks", resumedAt + 3));
        var replacement = queue.claim(WORLD, "helper", Set.of(CRAFT, GATHER), "stock", resumedAt + 4, here, ignored -> true,
                (worker, item) -> 0, java.util.Map.of("minecraft:iron_pickaxe", 25));
        assertEquals(pickaxe, replacement.job().work());
        assertTrue(queue.finish("stock", "pickaxe", replacement.job().lease(), true, "Crafted a fresh pickaxe", resumedAt + 5));
        assertEquals(ore, queue.claim(WORLD, "helper", Set.of(GATHER), "stock", resumedAt + 6).job().work());
    }

    private ProjectQueue exhaustedDiamondQueue(java.nio.file.Path journal) throws Exception {
        String chest = "block:3,64,0";
        var wood = job("wood", GATHER, List.of());
        var pickaxe = new ProjectQueue.Work("pickaxe", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\",\"count\":1}",
                List.of("wood"), Set.of(), "wood");
        var ore = new ProjectQueue.Work("ore", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":8}",
                List.of("pickaxe"), Set.of(), "pickaxe");
        var bank = new ProjectQueue.Work("bank", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":8}",
                List.of("ore"), Set.of(chest), "ore");
        var oldSite = new ProjectQueue.ResourceSite(101, -52, 31);
        var jobs = List.of(
                new ProjectQueue.Job(wood, ProjectQueue.State.DONE, 1, "miner", null, 0, "Found", null),
                new ProjectQueue.Job(pickaxe, ProjectQueue.State.DONE, 1, "miner", null, 0, "Crafted", null),
                new ProjectQueue.Job(ore, ProjectQueue.State.FAILED, 3, null, null, 0,
                        ProjectQueue.GATHER_TOOLS_NEEDED + " after 2 supply recoveries", null, oldSite),
                new ProjectQueue.Job(bank, ProjectQueue.State.WAITING, 0, null, null, 0, null, null));
        var saved = new ProjectQueue.Project("stock", WORLD, jobs, null, chest,
                java.util.Map.of("wood", 2, "tool-depth:ore", -52, "tool-stock:ore", 8, "equipped-miner:ore", 1));
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of(saved.id(), saved))));
        return new ProjectQueue(journal);
    }

    @Test void equippedMinerCanResumeAStalledPrivateToolChainAndStillRestock() throws Exception {
        var journal = directory.resolve("equipped-miner.json");
        var queue = new ProjectQueue(journal);
        queue.submitSupplied("stock", WORLD, List.of(job("ingredients", GATHER, List.of()),
                new ProjectQueue.Work("tools", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\"}",
                        List.of("ingredients"), Set.of(), "ingredients"),
                new ProjectQueue.Work("ore", GATHER,
                        "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\"}",
                        List.of("tools"), Set.of(), "tools")), "block:3,64,0");
        for (long now = 1; now <= 5; now += 2) {
            var lease = queue.claim(WORLD, "supplier", Set.of(GATHER), now).job();
            assertNull(queue.offerEquippedMiner(WORLD, "miner", null, java.util.Map.of("minecraft:iron_pickaxe", 200), now));
            assertTrue(queue.finish("stock", "ingredients", lease.lease(), false, "Stalled", now + 1));
        }
        assertNull(queue.offerEquippedMiner(WORLD, "miner", null, java.util.Map.of("minecraft:iron_pickaxe", 1), 7));
        var assignment = queue.offerEquippedMiner(WORLD, "miner", null, java.util.Map.of("minecraft:iron_pickaxe", 200), 7);
        assertEquals("ore", assignment.job().work().id());
        var restored = new ProjectQueue(journal);
        assertTrue(restored.finish("stock", "ore", assignment.job().lease(), false, ProjectQueue.GATHER_TOOLS_NEEDED, 8));
        assertEquals("ingredients", restored.claim(WORLD, "miner", Set.of(GATHER), 9).job().work().id());
    }

    @Test void excavationToolFailuresWaitForEquippedWorkersAndLegacyFailuresGetOneRetry() throws Exception {
        var file = directory.resolve("excavation-tools.json");
        var queue = new ProjectQueue(file);
        var work = job("clear", EXCAVATE, List.of());
        queue.submit("scaffold", WORLD, List.of(work));
        String missing = ProjectQueue.EXCAVATION_TOOL_NEEDED + "minecraft:cobblestone";
        var first = queue.claim(WORLD, "unequipped", Set.of(EXCAVATE), 1).job();
        assertTrue(queue.finish("scaffold", "clear", first.lease(), false, missing, 2));
        queue = new ProjectQueue(file);

        assertNull(queue.claim(WORLD, "unequipped", Set.of(EXCAVATE), 3));
        assertNull(queue.claim(WORLD, "wrong-tool", Set.of(EXCAVATE), null, 4, null,
                "minecraft:diamond_ore"::equals));
        var second = queue.claim(WORLD, "equipped", Set.of(EXCAVATE), null, 5, null,
                "minecraft:cobblestone"::equals).job();
        assertEquals(2, second.attempts());
        assertTrue(queue.finish("scaffold", "clear", second.lease(), false, missing, 6));
        var third = queue.claim(WORLD, "equipped", Set.of(EXCAVATE), null, 7, null, material -> true).job();
        assertTrue(queue.finish("scaffold", "clear", third.lease(), false, missing, 8));
        assertEquals(ProjectQueue.State.FAILED, queue.snapshot(9).get(0).jobs().get(0).state());
        queue = new ProjectQueue(file);

        assertNull(queue.claim(WORLD, "unequipped", Set.of(EXCAVATE), 10));
        var last = queue.claim(WORLD, "replacement", Set.of(EXCAVATE), null, 11, null, material -> true).job();
        assertEquals(4, last.attempts());
        assertTrue(queue.finish("scaffold", "clear", last.lease(), false, missing, 12));
        assertNull(queue.claim(WORLD, "replacement", Set.of(EXCAVATE), null, 13, null, material -> true));
    }

    @Test void wornToolsReopenOnlyTheGatherersProductionChain() throws Exception {
        var journal = directory.resolve("mining-tools.json");
        var queue = new ProjectQueue(journal);
        queue.submitSupplied("stock", WORLD, List.of(job("previous", EXCAVATE, List.of()),
                job("ingredients", GATHER, List.of("previous")),
                new ProjectQueue.Work("tools", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\"}",
                        List.of("ingredients"), Set.of(), "ingredients"),
                new ProjectQueue.Work("ore", GATHER, "", List.of("tools"), Set.of(), "tools")), "block:3,64,0");
        long now = 1;
        for (var kind : List.of(EXCAVATE, GATHER, CRAFT)) {
            var assigned = queue.claim(WORLD, "miner", Set.of(kind), now++).job();
            assertTrue(queue.finish("stock", assigned.work().id(), assigned.lease(), true, "Done", now++));
        }
        var ore = queue.claim(WORLD, "miner", Set.of(GATHER), now++).job();
        var site = new ProjectQueue.ResourceSite(6, -50, 8);
        assertTrue(queue.finish("stock", "ore", ore.lease(), false, ProjectQueue.GATHER_TOOLS_NEEDED, null, site, now++));
        var restored = new ProjectQueue(journal);
        var jobs = restored.snapshot(now).get(0).jobs();
        assertEquals(ProjectQueue.State.DONE, jobs.get(0).state());
        assertEquals(ProjectQueue.State.WAITING, jobs.get(2).state());
        assertEquals(site, jobs.get(3).resourceSite());
        assertNull(restored.claim(WORLD, "other", Set.of(GATHER), now++));
        assertEquals("ingredients", restored.claim(WORLD, "miner", Set.of(GATHER), now).job().work().id());
    }

    @Test void deeperProspectingRenewsTheToolBudgetWhileSameDepthRemainsBounded() throws Exception {
        var journal = directory.resolve("prospecting-tools.json");
        var queue = new ProjectQueue(journal);
        queue.submitSupplied("ore-stock", WORLD, List.of(job("ingredients", GATHER, List.of()),
                new ProjectQueue.Work("tools", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\"}",
                        List.of("ingredients"), Set.of(), "ingredients"),
                new ProjectQueue.Work("ore", GATHER,
                        "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\"}",
                        List.of("tools"), Set.of(), "tools")), "block:3,64,0");
        long now = 1;
        for (int depth : new int[]{8, 8, 0, 0, 0}) {
            for (var kind : List.of(GATHER, CRAFT)) {
                var supply = queue.claim(WORLD, "miner", Set.of(kind), now++).job();
                assertTrue(queue.finish("ore-stock", supply.work().id(), supply.lease(), true, "Done", now++));
            }
            var ore = queue.claim(WORLD, "miner", Set.of(GATHER), now++).job();
            assertTrue(queue.finish("ore-stock", "ore", ore.lease(), false, ProjectQueue.GATHER_TOOLS_NEEDED,
                    null, new ProjectQueue.ResourceSite(6, depth, 8), now++));
            queue = new ProjectQueue(journal);
        }
        var exhausted = queue.snapshot(now).get(0).jobs().get(2);
        assertEquals(ProjectQueue.State.FAILED, exhausted.state());
        assertEquals(new ProjectQueue.ResourceSite(6, 0, 8), exhausted.resourceSite());
        assertEquals(1, queue.retryInterruptedGathering(WORLD, "ore-stock"));
        assertEquals("ingredients", queue.claim(WORLD, "replacement", Set.of(GATHER), now).job().work().id());
    }

    @Test void anExhaustedMineCanBankCargoAndResumeWithFreshGearAtTheSameDepth() throws Exception {
        var journal = directory.resolve("equipped-mine-recovery.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String diamond = "minecraft:diamond";
        queue.submitSupplied("stock", WORLD, List.of(job("ingredients", GATHER, List.of()),
                new ProjectQueue.Work("tools", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\"}",
                        List.of("ingredients"), Set.of(), "ingredients"),
                new ProjectQueue.Work("ore", GATHER,
                        "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":64}",
                        List.of("tools"), Set.of(), "tools"),
                new ProjectQueue.Work("deposit", TRANSFER,
                        "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":64}",
                        List.of("ore"), Set.of(chest), "ore")), chest);
        long now = 1;
        var site = new ProjectQueue.ResourceSite(6, -48, 8);
        for (int i = 0; i < 3; i++) {
            for (var kind : List.of(GATHER, CRAFT)) {
                var supply = queue.claim(WORLD, "miner", Set.of(kind), now++).job();
                assertTrue(queue.finish("stock", supply.work().id(), supply.lease(), true, "Done", now++));
            }
            var ore = queue.claim(WORLD, "miner", Set.of(GATHER), now++).job();
            assertTrue(queue.finish("stock", "ore", ore.lease(), false, ProjectQueue.GATHER_TOOLS_NEEDED, null, site, now++));
        }
        var iron = java.util.Map.of("minecraft:iron_pickaxe", 150);
        var stone = java.util.Map.of("minecraft:stone_pickaxe", 100);
        assertNull(queue.offerEquippedMiner(WORLD, "helper", null, stone, now++));
        queue = new ProjectQueue(journal);
        var failure = queue.snapshot(now).get(0).jobs().stream()
                .filter(job -> job.work().id().equals("ore")).findFirst().orElseThrow();
        assertEquals(ProjectQueue.State.FAILED, failure.state());
        assertEquals("miner", failure.worker());
        assertNull(failure.lease());
        var rescue = queue.offerEquippedMiner(WORLD, "helper", null, iron, now++).job();
        assertEquals(site, rescue.resourceSite());
        assertEquals("helper", rescue.worker());
        assertTrue(queue.finish("stock", "ore", rescue.lease(), false, ProjectQueue.GATHER_TOOLS_NEEDED, null, site, now++));
        queue = new ProjectQueue(journal);
        assertNull(queue.offerEquippedMiner(WORLD, "helper", null, iron, now++));

        queue.submit("scaffold", WORLD, List.of(job("cleanup", EXCAVATE, List.of())));
        var kinds = Set.of(EXCAVATE, GATHER, TRANSFER);
        var delivery = queue.claim(WORLD, "helper", kinds, null, now++, site,
                material -> true, (bot, item) -> 4, stone).job();
        assertEquals("deposit", delivery.work().id());
        assertEquals(delivery.lease(), queue.claim(WORLD, "helper", kinds, null, now++, site,
                material -> true, (bot, item) -> 4, stone).job().lease());
        var cleanup = queue.claim(WORLD, "builder", kinds, null, now++, site,
                material -> true, (bot, item) -> 0, stone).job();
        assertEquals("cleanup", cleanup.work().id());
        assertTrue(queue.finish("scaffold", "cleanup", cleanup.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
        assertTrue(queue.finish("stock", "deposit", delivery.lease(), false, "Delivery is short by 60",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(diamond, 4)), null, true, now++));
        queue = new ProjectQueue(journal);
        rescue = queue.claim(WORLD, "replacement", kinds, null, now++, site, material -> true,
                (bot, item) -> 0, java.util.Map.of("minecraft:diamond_pickaxe", 200)).job();
        assertEquals("ore", rescue.work().id());
        assertEquals(site, rescue.resourceSite());
        assertEquals(60, com.google.gson.JsonParser.parseString(rescue.work().payload()).getAsJsonObject().get("count").getAsInt());
        assertTrue(queue.finish("stock", "ore", rescue.lease(), false, ProjectQueue.GATHER_TOOLS_NEEDED, null, site, now++));
        assertEquals("ingredients", queue.claim(WORLD, "replacement", Set.of(GATHER), now).job().work().id());
    }

    @Test void scaffoldBatchKeepsOneLeaseAndNeverReplaysACompletedStep() throws Exception {
        var path = directory.resolve("scaffold-batch.json");
        var queue = new ProjectQueue(path);
        var work = new ArrayList<ProjectQueue.Work>();
        for (int index = 0; index < 3; index++) {
            int x = 8 + index;
            String cell = "block:" + x + ",116,2";
            String payload = """
                    {"target":{"x":%d,"y":116,"z":2},"stand":{"x":%d,"y":116,"z":3},"block":"minecraft:cobblestone"}
                    """.formatted(x, x);
            String placement = "place-" + index;
            work.add(new ProjectQueue.Work(placement, BUILD, payload, List.of(), Set.of(cell)));
            work.add(new ProjectQueue.Work("remove-" + index, EXCAVATE, payload, List.of(placement), Set.of(cell),
                    null, new ProjectQueue.Cleanup(cell, placement)));
        }
        queue.submit("house", WORLD, work);
        long now = 1;
        for (int i = 0; i < 3; i++) {
            var placement = queue.claim(WORLD, "writer", Set.of(BUILD), now++).job();
            assertTrue(queue.finish("house", placement.work().id(), placement.lease(), true, "Placed", now++));
        }
        for (int i = 0; i < 9; i++) {
            var removal = queue.claim(WORLD, "writer", Set.of(EXCAVATE), now++).job();
            assertTrue(queue.finish("house", removal.work().id(), removal.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
        }
        assertNull(queue.claim(WORLD, "cleaner", Set.of(EXCAVATE), now++));
        var peerStand = new ProjectQueue.ResourceSite(20, 116, 3);
        var peerWork = new ProjectQueue.Work("nearby", BUILD,
                "{\"stand\":{\"x\":20,\"y\":116,\"z\":3}}", List.of(), Set.of("block:20,116,3"));
        queue.submit("nearby-house", WORLD, List.of(peerWork));
        assertNotNull(queue.claim(WORLD, "neighbor", Set.of(BUILD), "nearby-house", now++));
        var steps = queue.failedScaffoldCleanups(WORLD).stream()
                .map(job -> new ProjectQueue.CleanupStep(job, new ProjectQueue.ResourceSite(9, 116, 3))).toList();
        assertEquals(3, queue.recoverScaffoldBatch(WORLD, steps, "cleaner", now++));
        var assigned = queue.claim(WORLD, "cleaner", Set.of(EXCAVATE), now++);
        assertEquals(List.of(peerStand), assigned.peerStands());
        var head = assigned.job();
        assertEquals("remove-0", head.work().id());
        assertNull(queue.claim(WORLD, "other", Set.of(EXCAVATE), now++));
        assertFalse(queue.finish("house", "remove-1", head.lease(), true, "Air", now++));
        assertTrue(queue.finish("house", "remove-0", head.lease(), true, "Air", now++));
        queue = new ProjectQueue(path);
        assertTrue(queue.finish("house", "remove-0", head.lease(), true, "Air", now++));
        var next = queue.batchContinuation("house", head.lease(), now++).job();
        assertEquals("remove-1", next.work().id());
        assertEquals(head.lease(), next.lease());
        queue.snapshot(now + 20_000);
        var restored = queue.snapshot(now + 20_001).get(0).jobs();
        assertEquals(ProjectQueue.State.DONE, restored.get(1).state());
        assertEquals(ProjectQueue.State.FAILED, restored.get(3).state());
        assertEquals(3, restored.get(5).attempts());
    }

    @Test void recoveryKeepsScaffoldOwnershipAndRestoresTheNewCleanerLease() throws Exception {
        var path = directory.resolve("cleanup-pose.json");
        var queue = new ProjectQueue(path);
        String cell = "block:8,116,2";
        String payload = """
                {"target":{"x":8,"y":116,"z":2},"stand":{"x":8,"y":116,"z":3},"block":"minecraft:cobblestone"}
                """;
        var place = new ProjectQueue.Work("scaffold", BUILD, payload, List.of(), Set.of(cell));
        var clean = new ProjectQueue.Work("clear", EXCAVATE, payload, List.of("scaffold"), Set.of(cell),
                null, new ProjectQueue.Cleanup(cell, "scaffold"));
        queue.submit("house", WORLD, List.of(place, clean));
        var builder = queue.claim(WORLD, "builder", Set.of(BUILD), 1).job();
        assertTrue(queue.finish("house", "scaffold", builder.lease(), true, "Placed", 2));
        for (int attempt = 0; attempt < 3; attempt++) {
            long now = 3 + attempt * 2;
            var old = queue.claim(WORLD, "builder", Set.of(EXCAVATE), now).job();
            assertTrue(queue.finish("house", "clear", old.lease(), false, ProjectQueue.TARGET_TIMEOUT, now + 1));
        }
        assertNull(queue.claim(WORLD, "nearby", Set.of(EXCAVATE), 10));
        var request = queue.failedScaffoldCleanups(WORLD).get(0);
        assertFalse(queue.recoverScaffoldCleanup(WORLD, request, new ProjectQueue.ResourceSite(12, 115, 2), "nearby", 11));
        assertTrue(queue.recoverScaffoldCleanup(WORLD, request, new ProjectQueue.ResourceSite(10, 116, 2), "nearby", 11));
        assertFalse(queue.recoverScaffoldCleanup(WORLD, request, new ProjectQueue.ResourceSite(11, 116, 2), "builder", 12));
        queue = new ProjectQueue(path);
        var restored = queue.claim(WORLD, "nearby", Set.of(EXCAVATE), 13).job();
        assertEquals("clear", restored.work().id());
        assertEquals(clean.cleanup(), restored.work().cleanup());
        assertTrue(restored.work().locks().contains("block:10,115,2"));
        assertTrue(queue.finish("house", "clear", restored.lease(), true, "Observed air", 14));
        assertTrue(queue.failedScaffoldCleanups(WORLD).isEmpty());
    }

    @Test void onlyIdleWorkersIntersectingALivePlacementAreAskedToClearIt() throws Exception {
        var queue = new ProjectQueue(directory.resolve("idle-clearance.json"));
        var target = new ProjectBlock.Position(8, 116, 2);
        var cells = List.of(new ProjectQueue.ResourceSite(8, 115, 2), new ProjectQueue.ResourceSite(8, 116, 2));
        var place = new ProjectQueue.Work("place", BUILD,
                "{\"target\":{\"x\":8,\"y\":116,\"z\":2},\"block\":\"minecraft:oak_planks\"}",
                List.of(), Set.of("block:8,116,2"));
        var supply = new ProjectQueue.Work("supply", GATHER, "{}", List.of(), Set.of());
        queue.submit("house", WORLD, List.of(place, supply));
        assertNull(queue.obstructedPlacement(WORLD, "idle", cells, 1));
        var lease = queue.claim(WORLD, "builder", Set.of(BUILD), 2).job();
        queue.claim(WORLD, "collector", Set.of(GATHER), 3);
        assertEquals(target, queue.obstructedPlacement(WORLD, "idle", cells, 4));
        assertNull(queue.obstructedPlacement(WORLD, "builder", cells, 4));
        assertNull(queue.obstructedPlacement(WORLD, "collector", cells, 4));
        assertNull(queue.obstructedPlacement("another-world", "idle", cells, 4));
        assertNull(queue.obstructedPlacement(WORLD, "idle", cells.subList(0, 1), 4));
        assertNull(queue.obstructedPlacement(WORLD, "idle", cells, lease.expiresAt()));
        assertTrue(queue.finish("house", "place", lease.lease(), true, "Placed", 5));
        assertNull(queue.obstructedPlacement(WORLD, "idle", cells, 6));
    }

    @Test void coordinatorInspectionRequiresCompletedMutationsAndRetiresAStaleWorkerLease() throws Exception {
        var path = directory.resolve("local-inspection.json");
        var queue = new ProjectQueue(path);
        String cell = "block:8,116,2";
        var build = new ProjectQueue.Work("place", BUILD, "{}", List.of(), Set.of(cell));
        var read = new ProjectQueue.Work("scan", INSPECT, "{}", List.of("place"), Set.of(cell));
        queue.submit("house", WORLD, List.of(build, read));
        assertTrue(queue.localInspections(WORLD).isEmpty());
        var placement = queue.claim(WORLD, "builder", Set.of(BUILD), 1).job();
        assertTrue(queue.localInspections(WORLD).isEmpty());
        assertTrue(queue.finish("house", "place", placement.lease(), true, "Placed", 2));
        var old = queue.claim(WORLD, "walker", Set.of(INSPECT), 3).job();
        var observed = queue.localInspections(WORLD).get(0);
        assertFalse(queue.confirmLocalInspection("another-world", observed, "Verified"));
        assertTrue(queue.confirmLocalInspection(WORLD, observed, "Verified loaded cells"));
        assertNull(queue.renew("house", "scan", old.lease(), 4));
        queue = new ProjectQueue(path);
        assertTrue(queue.localInspections(WORLD).isEmpty());
        assertEquals(ProjectQueue.State.DONE, queue.snapshot(5).get(0).jobs().get(1).state());
    }

    @Test void coordinatorCanRecoverTimedOutOriginalScaffoldClearanceAcrossRestart() throws Exception {
        var journal = directory.resolve("planned-scaffold.json");
        var queue = new ProjectQueue(journal);
        String cell = "block:8,100,2";
        String target = "{\"x\":8,\"y\":100,\"z\":2}";
        String gate = "check-access-2";
        var check = new ProjectQueue.Work(gate, ProjectQueue.Kind.VERIFY,
                "{\"target\":" + target + ",\"block\":\"minecraft:air\"}", List.of(), Set.of(cell));
        var place = new ProjectQueue.Work("place-access-2", BUILD,
                "{\"target\":" + target + ",\"block\":\"minecraft:cobblestone\"}", List.of(gate), Set.of(cell));
        queue.submit("house", WORLD, List.of(check, place));
        long now = 1;
        for (int attempt = 0; attempt < 3; attempt++) {
            var lease = queue.claim(WORLD, "checker", Set.of(ProjectQueue.Kind.VERIFY), now++).job();
            assertTrue(queue.finish("house", gate, lease.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
        }
        queue = new ProjectQueue(journal);
        assertEquals(ProjectQueue.State.FAILED, queue.snapshot(now).get(0).jobs().get(0).state());
        assertNull(queue.claim(WORLD, "builder", Set.of(BUILD), now++));
        var observed = queue.scaffoldClearances(WORLD).get(0);
        assertTrue(queue.confirmScaffoldClearance(WORLD, observed, "Observed clear scaffold cell"));
        assertEquals(place.id(), queue.claim(WORLD, "builder", Set.of(BUILD), now++).job().work().id());
    }

    @Test void equippedReplacementRescuesStalledPinnedGatheringAcrossRestarts() throws Exception {
        var journal = directory.resolve("stalled-equipped.json");
        var queue = prepareStalledSupply(journal, false);
        var stone = java.util.Map.of("minecraft:stone_pickaxe", 75);
        assertNull(queue.offerEquippedMiner(WORLD, "original", null, stone, 15));
        assertNull(queue.offerEquippedMiner(WORLD, "replacement", null, java.util.Map.of("minecraft:wooden_pickaxe", 75), 15));
        var iron = queue.offerEquippedMiner(WORLD, "replacement", null, stone, 15).job();
        assertEquals("iron", iron.work().id());
        assertNull(iron.resourceSite());
        assertNull(queue.offerEquippedMiner(WORLD, "third", null, stone, 16));
        queue = new ProjectQueue(journal);
        assertTrue(queue.finish("stock", "iron", iron.lease(), true, "Observed raw iron", 17));
        assertNull(queue.claim(WORLD, "original", Set.of(SMELT), 18));
        assertEquals("smelt", queue.claim(WORLD, "replacement", Set.of(SMELT), 18).job().work().id());
        var jobs = queue.snapshot(19).get(0).jobs();
        assertEquals(ProjectQueue.State.DONE, jobs.get(0).state());
        assertEquals("original", jobs.get(2).worker());
    }

    @Test void completedDefensePreparationDoesNotHideTheMiningToolTier() throws Exception {
        var tools = new ProjectQueue.Work("tools", CRAFT, """
                {"recipe":"minecraft:stone_pickaxe"}
                """, List.of(), Set.of());
        var sword = new ProjectQueue.Work("sword", CRAFT, """
                {"recipe":"minecraft:stone_sword"}
                """, List.of(tools.id()), Set.of(), tools.id());
        var ore = new ProjectQueue.Work("ore", GATHER, """
                {"resource":"iron","variant":"both","item":"minecraft:raw_iron","count":3}
                """, List.of(sword.id()), Set.of(), sword.id());
        var from = new ProjectQueue.ResourceSite(4, 20, 4);
        var position = new ProjectQueue.ResourceSite(50, 34, 4);
        var vein = new ProjectQueue.ReachableOre("minecraft:iron_ore", new ProjectQueue.ResourceSite(52, 34, 4));
        long now = 40_000;
        for (String recovery : List.of("equipped", "observed", "relocated")) {
            var journal = directory.resolve("defense-before-ore-" + recovery + ".json");
            String failure = recovery.equals("equipped") ? ProjectQueue.GATHER_TOOLS_NEEDED + " after 2 supply recoveries"
                    : ProjectQueue.GATHER_STALLED;
            var jobs = List.of(new ProjectQueue.Job(tools, ProjectQueue.State.DONE, 1, "original", null, 0, "Crafted", null),
                    new ProjectQueue.Job(sword, ProjectQueue.State.DONE, 1, "original", null, 0, "Crafted", null),
                    new ProjectQueue.Job(ore, ProjectQueue.State.FAILED, 3, "original", null, 1, failure, null, from));
            var plan = new ProjectQueue.Project("stock", WORLD, jobs, null, "block:3,64,0", java.util.Map.of("tools", 2));
            Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                    "projects", java.util.Map.of(plan.id(), plan))));
            var queue = new ProjectQueue(journal);
            String worker = recovery.equals("relocated") ? "original" : "helper";
            var wood = java.util.Map.of("minecraft:wooden_pickaxe", 50);
            var stone = java.util.Map.of("minecraft:stone_pickaxe", 120);
            queue.claim(WORLD, worker, Set.of(VERIFY), now);
            assertNull(queue.offerEquippedMiner(WORLD, worker, "stock", wood, now, position));
            var lease = switch (recovery) {
                case "observed" -> queue.offerObservedOreRecovery(WORLD, worker, "stock", position,
                        java.util.Map.of("minecraft:stone_pickaxe", 6), List.of(vein), now);
                case "relocated" -> queue.offerRelocatedMiner(WORLD, worker, "stock", position, stone, now);
                default -> queue.offerEquippedMiner(WORLD, worker, "stock", stone, now, position);
            };
            assertNotNull(lease, recovery);
            assertEquals(ore, lease.job().work());
            assertEquals(worker, lease.job().worker());
            var after = queue.snapshot(now).get(0).jobs();
            assertEquals(jobs.subList(0, 2), after.subList(0, 2));
        }
    }

    @Test void stalledOreCanUseDistinctEquippedWorkersButCannotCycleThroughThem() throws Exception {
        var journal = directory.resolve("distinct-equipped-workers.json");
        var queue = prepareStalledSupply(journal, false);
        var stone = java.util.Map.of("minecraft:stone_pickaxe", 75);
        long now = 20;
        for (String worker : List.of("first", "second", "third")) {
            var retry = queue.offerEquippedMiner(WORLD, worker, null, stone, now++).job();
            assertEquals("iron", retry.work().id());
            assertTrue(queue.finish("stock", "iron", retry.lease(), false, ProjectQueue.GATHER_STALLED, now++));
            queue = new ProjectQueue(journal);
            assertNull(queue.offerEquippedMiner(WORLD, "first", null, stone, now++));
        }
        assertNull(queue.offerEquippedMiner(WORLD, "fourth", null, stone, now));
    }

    @Test void aFreshReachableVeinLetsTheSameEquippedWorkerResumeOnlyOnce() throws Exception {
        var journal = directory.resolve("observed-ore.json");
        var queue = prepareStalledSupply(journal, false);
        long now = 40_000;
        var worker = new ProjectQueue.ResourceSite(4, 20, 4);
        var ore = new ProjectQueue.ReachableOre("minecraft:iron_ore", new ProjectQueue.ResourceSite(7, 20, 4));
        var distant = new ProjectQueue.ReachableOre("minecraft:iron_ore", new ProjectQueue.ResourceSite(34, 20, 4));
        var stone = java.util.Map.of("minecraft:stone_pickaxe", 8);
        queue.claim(WORLD, "original", Set.of(VERIFY), now);
        assertNull(queue.offerObservedOreRecovery(WORLD, "original", "stock", worker, stone, List.of(distant), now));
        assertNull(queue.offerObservedOreRecovery(WORLD, "original", "stock", worker,
                java.util.Map.of("minecraft:wooden_pickaxe", 60), List.of(ore), now));
        var result = queue.offerObservedOreRecovery(WORLD, "original", "stock", worker, stone, List.of(ore), now);
        assertEquals("iron", result.job().work().id());
        assertEquals(worker, result.job().resourceSite());
        assertTrue(queue.finish("stock", "iron", result.job().lease(), false, ProjectQueue.GATHER_STALLED, now + 1));
        queue = new ProjectQueue(journal);
        now += 40_000;
        queue.claim(WORLD, "original", Set.of(VERIFY), now);
        assertNull(queue.offerObservedOreRecovery(WORLD, "original", "stock", worker, stone, List.of(ore), now));
    }

    @Test void aWalkableNearbyStandCanReopenAnOreJobBeyondImmediateReach() throws Exception {
        var queue = prepareStalledSupply(directory.resolve("walkable-ore-stand.json"), false);
        long now = 40_000;
        var position = new ProjectQueue.ResourceSite(4, 20, 4);
        var ore = new ProjectQueue.ResourceSite(12, 20, 4);
        var stand = new ProjectQueue.ResourceSite(9, 20, 4);
        var tools = java.util.Map.of("minecraft:stone_pickaxe", 8);
        queue.claim(WORLD, "original", Set.of(VERIFY), now);
        assertNull(queue.offerObservedOreRecovery(WORLD, "original", "stock", position, tools,
                List.of(new ProjectQueue.ReachableOre("minecraft:iron_ore", ore)), now));
        assertNull(queue.offerObservedOreRecovery(WORLD, "original", "stock", position, tools,
                List.of(new ProjectQueue.ReachableOre("minecraft:iron_ore", ore,
                        new ProjectQueue.ResourceSite(9, 24, 4))), now));
        var retry = queue.offerObservedOreRecovery(WORLD, "original", "stock", position, tools,
                List.of(new ProjectQueue.ReachableOre("minecraft:iron_ore", ore, stand)), now);
        assertEquals("iron", retry.job().work().id());
        assertEquals(stand, retry.job().resourceSite());
    }

    @Test void aStalledOreJobWithoutAnOldLocationGetsOneRetryNearItsOreLayer() throws Exception {
        var journal = directory.resolve("legacy-ore-retry.json");
        prepareStalledSupply(journal, false);
        var document = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(journal)).getAsJsonObject();
        var jobs = document.getAsJsonObject("projects").getAsJsonObject("stock").getAsJsonArray("jobs");
        for (var entry : jobs) {
            var job = entry.getAsJsonObject();
            if (job.getAsJsonObject("work").get("id").getAsString().equals("iron")) job.remove("resourceSite");
        }
        java.nio.file.Files.writeString(journal, document.toString());
        var queue = new ProjectQueue(journal);
        long now = 40_000;
        var stone = java.util.Map.of("minecraft:stone_pickaxe", 32);
        var position = new ProjectQueue.ResourceSite(8, 43, 9);
        queue.claim(WORLD, "original", Set.of(VERIFY), now);
        queue.claim(WORLD, "other", Set.of(VERIFY), now);
        assertNull(queue.offerRelocatedMiner(WORLD, "original", "stock", new ProjectQueue.ResourceSite(8, 80, 9), stone, now));
        assertNull(queue.offerRelocatedMiner(WORLD, "other", "stock", position, stone, now));
        assertNull(queue.offerRelocatedMiner(WORLD, "original", "stock", position,
                java.util.Map.of("minecraft:stone_pickaxe", 31), now));
        var retry = queue.offerRelocatedMiner(WORLD, "original", "stock", position, stone, now).job();
        assertEquals("iron", retry.work().id());
        assertEquals(position, retry.resourceSite());
        assertTrue(queue.finish("stock", "iron", retry.lease(), false, ProjectQueue.GATHER_STALLED, null, position, now + 1));
        queue = new ProjectQueue(journal);
        now += 40_000;
        queue.claim(WORLD, "original", Set.of(VERIFY), now);
        assertNull(queue.offerRelocatedMiner(WORLD, "original", "stock", position, stone, now));
    }

    @Test void relocatedMinerRetriesFromNewGroundWithoutCyclingThroughOldAreas() throws Exception {
        var journal = directory.resolve("relocated-miner.json");
        var queue = prepareStalledSupply(journal, false);
        var nearby = new ProjectQueue.ResourceSite(12, 22, 13);
        var surface = new ProjectQueue.ResourceSite(8, 60, 9);
        var stone = java.util.Map.of("minecraft:stone_pickaxe", 75);
        long now = 40_000;
        queue.claim(WORLD, "original", Set.of(VERIFY), now);
        queue.claim(WORLD, "other", Set.of(VERIFY), now);
        assertNull(queue.offerRelocatedMiner(WORLD, "original", "stock", nearby, stone, now));
        assertNull(queue.offerRelocatedMiner(WORLD, "other", "stock", surface, stone, now));
        assertNull(queue.offerRelocatedMiner(WORLD, "original", "stock", surface,
                java.util.Map.of("minecraft:stone_pickaxe", 1), now));
        assertNull(queue.offerRelocatedMiner(WORLD, "original", "stock", surface,
                java.util.Map.of("minecraft:wooden_pickaxe", 59), now));

        var first = queue.offerRelocatedMiner(WORLD, "original", "stock", surface, stone, now).job();
        assertEquals("iron", first.work().id());
        assertEquals(surface, first.resourceSite());
        assertTrue(queue.finish("stock", "iron", first.lease(), false, ProjectQueue.GATHER_STALLED, null,
                new ProjectQueue.ResourceSite(8, 20, 9), now + 1));

        queue = new ProjectQueue(journal);
        now += 40_000;
        queue.claim(WORLD, "original", Set.of(VERIFY), now);
        assertNull(queue.offerRelocatedMiner(WORLD, "original", "stock", surface, stone, now));
        var across = new ProjectQueue.ResourceSite(56, 20, 9);
        var second = queue.offerRelocatedMiner(WORLD, "original", "stock", across, stone, now).job();
        assertEquals(across, second.resourceSite());
        assertTrue(queue.finish("stock", "iron", second.lease(), false, ProjectQueue.GATHER_STALLED, now + 1));
        now += 40_000;
        queue.claim(WORLD, "original", Set.of(VERIFY), now);
        assertNull(queue.offerRelocatedMiner(WORLD, "original", "stock",
                new ProjectQueue.ResourceSite(110, 90, 9), stone, now));
    }

    @Test void aReplacementWorkerCanRecoverAfterThePreviousWorkerUsedBothRelocationAttempts() throws Exception {
        var journal = directory.resolve("replacement-relocated-miner.json");
        prepareStalledSupply(journal, false);
        var document = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(journal)).getAsJsonObject();
        var project = document.getAsJsonObject("projects").getAsJsonObject("stock");
        var budget = project.getAsJsonObject("supplyRecoveries");
        budget.addProperty("relocated-miner:iron", 2);
        budget.addProperty("relocated-miner:iron:worker:previous", 2);
        java.nio.file.Files.writeString(journal, document.toString());

        var queue = new ProjectQueue(journal);
        long now = 40_000;
        var stone = java.util.Map.of("minecraft:stone_pickaxe", 75);
        queue.claim(WORLD, "original", Set.of(VERIFY), now);
        assertNull(queue.offerRelocatedMiner(WORLD, "original", "stock",
                new ProjectQueue.ResourceSite(8, 32, 9), stone, now));
        var newLayer = new ProjectQueue.ResourceSite(8, 33, 9);
        var retry = queue.offerRelocatedMiner(WORLD, "original", "stock", newLayer, stone, now);
        assertEquals(newLayer, retry.job().resourceSite());
        var saved = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(journal)).getAsJsonObject()
                .getAsJsonObject("projects").getAsJsonObject("stock").getAsJsonObject("supplyRecoveries");
        assertEquals(3, saved.get("relocated-miner:iron").getAsInt());
        assertEquals(1, saved.get("relocated-miner:iron:worker:original").getAsInt());
        assertEquals(2, saved.get("relocated-miner:iron:worker:previous").getAsInt());
    }

    @Test void aSurfaceWorkerRestartsOnlyAnUnsharedChainWithCompatibleTimber() throws Exception {
        for (boolean shared : List.of(false, true)) {
            var journal = directory.resolve("surface-starter-" + shared + ".json");
            prepareStalledSupply(journal, shared);
            var doc = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(journal)).getAsJsonObject();
            var jobs = doc.getAsJsonObject("projects").getAsJsonObject("stock").getAsJsonArray("jobs");
            for (var row : jobs) {
                var work = row.getAsJsonObject().getAsJsonObject("work");
                if (!work.get("id").getAsString().equals("logs")) continue;
                work.addProperty("payload", "{\"resource\":\"block:minecraft:oak_log\",\"variant\":\"both\","
                        + "\"item\":\"minecraft:oak_log\",\"count\":2}");
            }
            java.nio.file.Files.writeString(journal, doc.toString());
            var queue = new ProjectQueue(journal);
            var position = new ProjectQueue.ResourceSite(8, 65, 9);
            long now = 40_000;
            queue.claim(WORLD, "volunteer", Set.of(VERIFY), now);
            assertNull(queue.offerStarterMiner(WORLD, "volunteer", "stock", position,
                    List.of("minecraft:birch_log"), work -> false, now));
            var result = queue.offerStarterMiner(WORLD, "volunteer", "stock", position,
                    List.of("minecraft:oak_log"), work -> false, now);
            if (shared) assertNull(result);
            else {
                assertNotNull(result);
                assertEquals("logs", result.job().work().id());
                assertEquals("volunteer", result.job().worker());
                assertEquals(position, result.job().resourceSite());
                assertNull(queue.offerStarterMiner(WORLD, "volunteer", "stock", position,
                        List.of("minecraft:oak_log"), work -> false, now));
            }
        }
    }

    @Test void timedOutCarriedCraftRestartsPrivatePreparationAtTheWorkbench() throws Exception {
        var journal = directory.resolve("stranded-craft.json");
        var logs = new ProjectQueue.Work("logs", GATHER,
                "{\"resource\":\"block:minecraft:oak_log\",\"variant\":\"both\",\"item\":\"minecraft:oak_log\",\"count\":2}",
                List.of(), Set.of());
        var ingredients = new ProjectQueue.Work("ingredients", CRAFT, "{}", List.of(logs.id()), Set.of(), logs.id());
        var craft = new ProjectQueue.Work("tools", CRAFT, "{\"recipe\":\"minecraft:stone_pickaxe\"}",
                List.of(ingredients.id()), Set.of("block:7,64,0"), ingredients.id());
        var jobs = List.of(
                new ProjectQueue.Job(logs, ProjectQueue.State.DONE, 1, "previous", null, 0, null, null),
                new ProjectQueue.Job(ingredients, ProjectQueue.State.DONE, 1, "previous", null, 0, null, null),
                new ProjectQueue.Job(craft, ProjectQueue.State.FAILED, 3, null, null, 0, ProjectQueue.TARGET_TIMEOUT, null));
        var project = new ProjectQueue.Project("stock", WORLD, jobs, null, "block:9,64,0",
                java.util.Map.of("carried-craft:tools", 1));
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of("stock", project))));
        var queue = new ProjectQueue(journal);
        var surface = new ProjectQueue.ResourceSite(8, 65, 0);
        var timber = List.of("minecraft:oak_log");
        assertNull(queue.claim(WORLD, "helper", Set.of(VERIFY), 1));
        assertNull(queue.claim(WORLD, "previous", Set.of(VERIFY), 1));
        assertNull(queue.offerStarterMiner(WORLD, "helper", "stock", surface, timber, work -> false, 1));
        assertNull(queue.offerStarterMiner(WORLD, "previous", "stock", surface, timber, work -> true, 2));
        var result = queue.offerStarterMiner(WORLD, "helper", "stock", surface, timber, craft::equals, 3);
        assertNotNull(result);
        assertEquals(logs, result.job().work());
        assertEquals("helper", result.job().worker());
        assertNull(queue.claim(WORLD, "another", Set.of(GATHER, CRAFT), 4));
        assertTrue(queue.finish("stock", logs.id(), result.job().lease(), true, "Found timber", 5));
        assertEquals(ingredients, queue.claim(WORLD, "helper", Set.of(CRAFT), 6).job().work());
    }

    @Test void stalledSupplyRewindsOnlyWhenItsPreparationIsPrivate() throws Exception {
        for (boolean shared : List.of(false, true)) {
            var journal = directory.resolve("stalled-fallback-" + shared + ".json");
            var queue = prepareStalledSupply(journal, shared);
            assertNull(queue.claim(WORLD, "helper", Set.of(GATHER), 15));
            queue = new ProjectQueue(journal);
            var replacement = queue.claim(WORLD, "helper", Set.of(GATHER), 40_000);
            if (shared) {
                assertNull(replacement);
                assertEquals(ProjectQueue.State.FAILED, queue.snapshot(40_001).get(0).jobs().get(3).state());
            } else {
                assertEquals("logs", replacement.job().work().id());
                var project = queue.snapshot(40_001).get(0);
                assertEquals(ProjectQueue.State.DONE, project.jobs().get(0).state());
                assertEquals(ProjectQueue.State.WAITING, project.jobs().get(2).state());
                assertEquals(1, project.supplyRecoveries().get("stalled-supply:iron"));
                assertNull(queue.claim(WORLD, "third", Set.of(GATHER), 40_001));
            }
        }
    }

    @Test void stalledGatherRetriesWithTheSmelterOnlyWhileItsOutputIsStillHeld() throws Exception {
        for (int held : List.of(2, 3)) {
            var journal = directory.resolve("retained-smelt-" + held + ".json");
            var queue = new ProjectQueue(journal);
            var raw = job("raw", GATHER, List.of());
            var smelt = new ProjectQueue.Work("smelt", SMELT,
                    "{\"recipe\":\"minecraft:iron_ingot_from_smelting_raw_iron\",\"count\":3}",
                    List.of("raw"), Set.of(), "raw");
            var gather = new ProjectQueue.Work("wood", GATHER,
                    "{\"resource\":\"block:minecraft:oak_log\",\"item\":\"minecraft:oak_log\",\"count\":1}",
                    List.of("smelt"), Set.of(), "smelt");
            queue.submitSupplied("stock", WORLD, List.of(raw, smelt, gather), "block:7,64,0");
            var rawLease = queue.claim(WORLD, "smelter", Set.of(GATHER), 1).job();
            assertTrue(queue.finish("stock", "raw", rawLease.lease(), true, "Collected", 2));
            var smeltLease = queue.claim(WORLD, "smelter", Set.of(SMELT), 3).job();
            assertTrue(queue.finish("stock", "smelt", smeltLease.lease(), true,
                    "Observed 3 minecraft:iron_ingot in worker inventory", 4));
            for (int tick = 5; tick < 11; tick += 2) {
                var lease = queue.claim(WORLD, "smelter", Set.of(GATHER), tick).job();
                assertTrue(queue.finish("stock", "wood", lease.lease(), false, ProjectQueue.GATHER_STALLED, tick + 1));
            }
            queue = new ProjectQueue(journal);
            assertNull(queue.claim(WORLD, "smelter", Set.of(CRAFT), 39_999));
            java.util.function.ToIntBiFunction<String, String> inventory = (worker, item) ->
                    worker.equals("smelter") && item.equals("minecraft:iron_ingot") ? held : 0;
            var helper = queue.claim(WORLD, "helper", Set.of(GATHER), null, 40_000, null,
                    material -> false, inventory, null);
            if (held < 3) {
                assertEquals("raw", helper.job().work().id());
                continue;
            }
            assertNull(helper);
            assertEquals(ProjectQueue.State.DONE, queue.snapshot(40_001).get(0).jobs().get(1).state());
            queue = new ProjectQueue(journal);
            assertNull(queue.claim(WORLD, "helper", Set.of(GATHER), 40_002));
            var retry = queue.claim(WORLD, "smelter", Set.of(GATHER), 40_003).job();
            assertEquals("wood", retry.work().id());
            assertEquals(3, retry.attempts());
            assertTrue(queue.finish("stock", "wood", retry.lease(), false, ProjectQueue.GATHER_STALLED, 40_004));
            helper = queue.claim(WORLD, "helper", Set.of(GATHER), null, 80_000, null,
                    material -> false, inventory, null);
            assertEquals("raw", helper.job().work().id());
        }
    }

    private ProjectQueue prepareStalledSupply(Path journal, boolean shared) throws Exception {
        var queue = new ProjectQueue(journal);
        var work = new java.util.ArrayList<>(List.of(
                job("previous", VERIFY, List.of()),
                job("logs", GATHER, List.of("previous")),
                new ProjectQueue.Work("tools", CRAFT, "{\"recipe\":\"minecraft:stone_pickaxe\"}",
                        List.of("logs"), Set.of(), "logs"),
                new ProjectQueue.Work("iron", GATHER,
                        "{\"resource\":\"iron\",\"variant\":\"both\",\"item\":\"minecraft:raw_iron\",\"count\":3}",
                        List.of("tools"), Set.of(), "tools"),
                new ProjectQueue.Work("smelt", SMELT, "{}",
                        List.of("iron"), Set.of(), "iron")));
        if (shared) work.add(job("shared-consumer", VERIFY, List.of("tools")));
        queue.submitSupplied("stock", WORLD, work, "block:7,64,0");
        long now = 1;
        for (var kind : List.of(VERIFY, GATHER, CRAFT)) {
            var stage = queue.claim(WORLD, "original", Set.of(kind), now++).job();
            assertTrue(queue.finish("stock", stage.work().id(), stage.lease(), true, "Done", now++));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            var lease = queue.claim(WORLD, "original", Set.of(GATHER), now++).job();
            assertTrue(queue.finish("stock", "iron", lease.lease(), false, ProjectQueue.GATHER_STALLED, null,
                    new ProjectQueue.ResourceSite(8, 21, 9), now++));
        }
        return queue;
    }

    @Test void bankedOreRestartsExhaustedToolProductionOnceEvenWithoutAnEquippedMiner() throws Exception {
        var journal = directory.resolve("banked-ore-tool-rearm.json");
        String chest = "block:3,64,0";
        var root = job("ingredients", GATHER, List.of());
        var tools = new ProjectQueue.Work("tools", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\"}",
                List.of("ingredients"), Set.of(), "ingredients");
        var ore = new ProjectQueue.Work("ore", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":61}",
                List.of("tools"), Set.of(), "tools");
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":64}",
                List.of("ore"), Set.of(chest), "ore");
        var receipt = new ProjectQueue.ChestObservation(
                new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:diamond", 3)), 1);
        var site = new ProjectQueue.ResourceSite(8, -51, 2);
        var jobs = List.of(new ProjectQueue.Job(root, ProjectQueue.State.DONE, 1, "prior", null, 0, "Found", null),
                new ProjectQueue.Job(tools, ProjectQueue.State.DONE, 1, "prior", null, 0, "Crafted", null),
                new ProjectQueue.Job(ore, ProjectQueue.State.FAILED, 3, null, null, 0,
                        ProjectQueue.GATHER_TOOLS_NEEDED + " after 2 supply recoveries", null, site),
                new ProjectQueue.Job(deposit, ProjectQueue.State.WAITING, 1, null, null, 0,
                        "Partial stock contribution settled", receipt));
        var saved = new ProjectQueue.Project("stock", WORLD, jobs, null, chest,
                java.util.Map.of("ingredients", 2, "tool-depth:ore", -51, "tool-stock:ore", 64));
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of(saved.id(), saved))));
        var queue = new ProjectQueue(journal);
        long now = 2;
        var claim = queue.claim(WORLD, "worker", Set.of(GATHER, CRAFT), "stock", now++, site,
                material -> false, (bot, item) -> 0, java.util.Map.of()).job();
        assertEquals("ingredients", claim.work().id());
        queue = new ProjectQueue(journal);
        var rearmed = queue.snapshot(now).get(0);
        assertEquals(61, rearmed.supplyRecoveries().get("tool-stock:ore"));
        assertEquals(1, rearmed.supplyRecoveries().get("ingredients"));
        assertEquals(receipt, rearmed.jobs().get(3).chest());
        assertEquals(ore, rearmed.jobs().get(2).work());
        assertEquals(site, rearmed.jobs().get(2).resourceSite());

        for (int cycle = 0; cycle < 2; cycle++) {
            if (cycle == 1) claim = queue.claim(WORLD, "worker", Set.of(GATHER), now++).job();
            assertTrue(queue.finish("stock", "ingredients", claim.lease(), true, "Found", now++));
            var crafted = queue.claim(WORLD, "worker", Set.of(CRAFT), now++).job();
            assertTrue(queue.finish("stock", "tools", crafted.lease(), true, "Crafted", now++));
            var mining = queue.claim(WORLD, "worker", Set.of(GATHER), now++).job();
            assertTrue(queue.finish("stock", "ore", mining.lease(), false, ProjectQueue.GATHER_TOOLS_NEEDED, null, site, now++));
        }
        queue = new ProjectQueue(journal);
        assertNull(queue.claim(WORLD, "other", Set.of(GATHER, CRAFT), "stock", now, site,
                material -> false, (bot, item) -> 0, java.util.Map.of()));
    }

    @Test void routeResupplyKeepsTheOriginalLeaseAndCannotDuplicateAcrossRestart() throws Exception {
        var journal = directory.resolve("route-tool-support.json");
        var queue = new ProjectQueue(journal);
        var deposit = new ProjectQueue.Work("return", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":3}", List.of(), Set.of("block:0,64,0"));
        queue.submit("order", WORLD, List.of(deposit));
        var returnLease = queue.claim(WORLD, "miner", Set.of(TRANSFER), 1).job();
        var tool = java.util.Map.of("minecraft:stone_pickaxe", 180);
        var mine = new ProjectQueue.ResourceSite(0, -30, 0);
        assertNull(queue.offerRouteTool(WORLD, "helper", null, 2, tool, 0, false, name -> mine, (bot, item) -> 2));
        assertNotNull(queue.renew("order", "return", returnLease.lease(), null, 3, true));
        assertNull(queue.offerRouteTool(WORLD, "helper", null, 4, tool, 0, false, name -> mine, (bot, item) -> 1));
        var help = queue.offerRouteTool(WORLD, "helper", null, 5, tool, 0, false, name -> mine, (bot, item) -> 2).job();
        assertEquals(ProjectQueue.Kind.RESUPPLY, help.work().kind());
        var delivery = new com.google.gson.Gson().fromJson(help.work().payload(), ProjectQueue.RouteToolDelivery.class);
        assertEquals("miner", delivery.recipient());
        assertEquals(mine, delivery.position());
        queue = new ProjectQueue(journal);
        assertNotNull(queue.renew("order", "return", returnLease.lease(), null, 6, true));
        assertNull(queue.offerRouteTool(WORLD, "second", null, 7, tool, 0, false, name -> mine, (bot, item) -> 2));
        assertTrue(queue.finish("order", help.work().id(), help.lease(), false, "Could not deliver", 8));
        assertNull(queue.offerRouteTool(WORLD, "second", null, 9, tool, 0, false, name -> mine, (bot, item) -> 2));
        assertEquals(returnLease.lease(), queue.claim(WORLD, "miner", Set.of(TRANSFER), 10).job().lease());
        var newJournal = directory.resolve("route-tool-crafting.json");
        var craftQueue = new ProjectQueue(newJournal);
        craftQueue.submit("order", WORLD, List.of(deposit));
        var returned = craftQueue.claim(WORLD, "miner", Set.of(TRANSFER), 1).job();
        assertNotNull(craftQueue.renew("order", "return", returned.lease(), null, 2, true));
        var made = craftQueue.offerRouteTool(WORLD, "crafter", null, 3, null, 1, false,
                name -> mine, (bot, item) -> 0).job();
        var spec = new com.google.gson.Gson().fromJson(made.work().payload(), ProjectQueue.RouteToolDelivery.class);
        assertEquals(1, spec.craftTarget());
        assertEquals("minecraft:stone_pickaxe", spec.item());
    }

    @Test void aCraftablePickaxeCanRequestADeliveredWorkbench() throws Exception {
        var journal = directory.resolve("route-workbench.json");
        var queue = new ProjectQueue(journal);
        var deposit = new ProjectQueue.Work("return", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":3}", List.of(), Set.of("block:0,64,0"));
        queue.submit("order", WORLD, List.of(deposit));
        var original = queue.claim(WORLD, "miner", Set.of(TRANSFER), 1).job();
        var site = new ProjectQueue.ResourceSite(0, -20, 0);
        assertNotNull(queue.renew("order", "return", original.lease(), null, 2, true));
        assertNull(queue.offerRouteTool(WORLD, "helper", null, 3, null, 0, true, name -> site, (bot, item) -> 0));
        assertNotNull(queue.renew("order", "return", original.lease(), null, 4, true, true));
        var supply = queue.offerRouteTool(WORLD, "helper", null, 5, null, 0, true, name -> site, (bot, item) -> 0).job();
        assertEquals("minecraft:crafting_table", queue.routeToolDelivery("order", supply.work().id(), supply.lease(), 6).item());
        queue.recordRouteBenchReceipt("order", "return", java.util.UUID.randomUUID(), 7);
        assertNotNull(queue.routeToolDelivery("order", supply.work().id(), supply.lease(), 8));
        queue.recordRouteBenchReceipt("order", "return", original.lease(), 9);
        queue = new ProjectQueue(journal);
        assertNull(queue.routeToolDelivery("order", supply.work().id(), supply.lease(), 10));
        assertTrue(queue.finish("order", supply.work().id(), supply.lease(), true, "Delivered workbench", 11));
    }

    @Test void routeWorkbenchCanUseOnlyUnreservedConstructionStock() throws Exception {
        var journal = directory.resolve("stock-route-workbench.json");
        String item = "minecraft:oak_planks";
        String storage = "block:1,64,0";
        var queue = new ProjectQueue(journal);
        var ready = job("ready", ProjectQueue.Kind.BARRIER, List.of());
        var pickup = new ProjectQueue.Work("pickup", TRANSFER,
                "{\"target\":{\"x\":1,\"y\":64,\"z\":0},\"direction\":\"TAKE\",\"item\":\"" + item + "\",\"stock\":3}",
                List.of("ready"), Set.of(storage), null, null, item);
        var construction = new ArrayList<>(List.of(ready, pickup));
        for (int n = 0; n < 3; n++) construction.add(new ProjectQueue.Work("place-" + n, BUILD,
                "{}", List.of("pickup"), Set.of("wall-" + n), "pickup"));
        queue.submitSupplied("house", WORLD, construction, storage);
        var materialClaim = queue.claim(WORLD, "builder", Set.of(TRANSFER), "house", 1).job();
        var seeded = new ProjectQueue.ChestReport(storage, java.util.Map.of(item, 7));
        assertTrue(queue.finish("house", "pickup", materialClaim.lease(), true, "Stock available", seeded, 2));
        var deposit = new ProjectQueue.Work("return", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":3}", List.of(), Set.of("block:0,64,0"));
        queue.submit("mine", WORLD, List.of(deposit));
        var receiver = queue.claim(WORLD, "miner", Set.of(TRANSFER), "mine", 3).job();
        assertNotNull(queue.renew("mine", "return", receiver.lease(), null, 4, true, true));
        java.util.function.Function<String, ProjectQueue.ResourceSite> position = worker -> worker.equals("miner")
                ? new ProjectQueue.ResourceSite(0, 13, 0) : new ProjectQueue.ResourceSite(0, 63, 0);
        assertNull(queue.offerRouteTool(WORLD, "helper", "mine", 5, null, 0, false, position, (bot, key) -> 0));
        assertNull(queue.offerRouteTool(WORLD, "builder", "mine", 5, null, 0, true, position, (bot, key) -> 0, true));
        var supply = queue.offerRouteTool(WORLD, "helper", "mine", 6, null, 0, false, position, (bot, key) -> 0, true).job();
        var spec = queue.routeToolDelivery("mine", supply.work().id(), supply.lease(), 7);
        assertEquals(3, spec.benchStock().reserve());
        assertEquals(storage, spec.benchStock().location());
        assertTrue(supply.work().locks().contains(storage));

        var observed = new ProjectQueue.ChestReport(storage, java.util.Map.of(item, 3));
        queue.recordRouteStock("mine", supply.work().id(), supply.lease(), 8, observed);
        queue = new ProjectQueue(journal);
        assertEquals(observed, queue.chestStock(WORLD).get(storage).report());
        assertTrue(queue.finish("mine", supply.work().id(), supply.lease(), false,
                ProjectQueue.ROUTE_PREPARATION_FAILED + "test handoff", 9));
        assertNotNull(queue.renew("mine", "return", receiver.lease(), null, 10, true, true));
        assertNull(queue.offerRouteTool(WORLD, "other", "mine", 11, null, 0, false, position, (bot, key) -> 0, true));
        assertEquals(observed, queue.chestStock(WORLD).get(storage).report());
    }

    @Test void emergencyWorkbenchUsesAnUnreservedTableOrLogFromObservedStock() throws Exception {
        String storage = "block:1,64,0";
        String log = "minecraft:oak_log";
        String table = "minecraft:crafting_table";
        for (var item : List.of(log, table)) {
            var queue = new ProjectQueue(directory.resolve(item.substring("minecraft:".length()) + "-resupply.json"));
            var pickup = new ProjectQueue.Work("pickup", TRANSFER,
                    "{\"target\":{\"x\":1,\"y\":64,\"z\":0},\"direction\":\"TAKE\",\"item\":\"" + item + "\",\"stock\":2}",
                    List.of("ready"), Set.of(storage), null, null, item);
            queue.submitSupplied("house", WORLD, List.of(job("ready", ProjectQueue.Kind.BARRIER, List.of()), pickup,
                    new ProjectQueue.Work("place-0", BUILD, "{}", List.of("pickup"), Set.of("wall"), "pickup")), storage);
            var materialClaim = queue.claim(WORLD, "builder", Set.of(TRANSFER), "house", 1).job();
            var stock = new ProjectQueue.ChestReport(storage, java.util.Map.of(item, 2));
            assertTrue(queue.finish("house", "pickup", materialClaim.lease(), true, "Stock observed", stock, 2));
            queue.submit("tools", WORLD, List.of(job("craft", CRAFT, List.of())));
            var receiver = queue.claim(WORLD, "crafter", Set.of(CRAFT), "tools", 3).job();
            assertNotNull(queue.renew("tools", "craft", receiver.lease(), null, 4, false, true));
            var position = new ProjectQueue.ResourceSite(0, 63, 0);
            var supply = queue.offerRouteTool(WORLD, "courier", "tools", 5, null, 0, false,
                    name -> position, (bot, key) -> 0, true).job();
            var spec = queue.routeToolDelivery("tools", supply.work().id(), supply.lease(), 6);
            assertEquals(table, spec.item());
            assertEquals(1, spec.benchStock().reserve());
            var payload = com.google.gson.JsonParser.parseString(spec.benchStock().payload()).getAsJsonObject();
            assertEquals(item, payload.get("item").getAsString());
            assertEquals(1, payload.get("stock").getAsInt());
            assertTrue(supply.work().locks().contains(storage));
        }
    }

    @Test void anotherHelperCanTakeOverFailedPreparationAfterRestart() throws Exception {
        var journal = directory.resolve("route-supply-handoff.json");
        var queue = new ProjectQueue(journal);
        var deposit = new ProjectQueue.Work("return", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":3}", List.of(), Set.of("block:0,64,0"));
        queue.submit("order", WORLD, List.of(deposit));
        var receiver = queue.claim(WORLD, "miner", Set.of(TRANSFER), 1).job();
        var site = new ProjectQueue.ResourceSite(0, -20, 0);
        assertNotNull(queue.renew("order", "return", receiver.lease(), null, 2, true, true));
        var first = queue.offerRouteTool(WORLD, "gatherer", null, 3, null, 0, true,
                name -> site, (bot, item) -> 0).job();
        assertTrue(queue.finish("order", first.work().id(), first.lease(), false,
                ProjectQueue.ROUTE_PREPARATION_FAILED + "could not reach wood", 4));

        queue = new ProjectQueue(journal);
        assertNotNull(queue.renew("order", "return", receiver.lease(), null, 5, true, true));
        assertNull(queue.offerRouteTool(WORLD, "gatherer", null, 6, null, 0, true, name -> site, (bot, item) -> 0));
        var second = queue.offerRouteTool(WORLD, "crafter", null, 7, null, 0, true,
                name -> site, (bot, item) -> 0).job();
        assertNotEquals(first.work().id(), second.work().id());
        assertNull(queue.offerRouteTool(WORLD, "third", null, 8, null, 0, true, name -> site, (bot, item) -> 0));
        assertNotNull(queue.routeToolDelivery("order", second.work().id(), second.lease(), 9));
        queue.recordRouteBenchReceipt("order", "return", receiver.lease(), 10);
        assertNull(queue.routeToolDelivery("order", second.work().id(), second.lease(), 11));
        assertEquals(receiver.lease(), queue.claim(WORLD, "miner", Set.of(TRANSFER), 12).job().lease());
    }

    @Test void anExpiredWorkbenchDeliveryCanReuseAWorkbenchObservedInTheSuppliersInventory() throws Exception {
        var queue = new ProjectQueue(directory.resolve("retry-retained-bench.json"));
        queue.submit("tools", WORLD, List.of(job("craft", CRAFT, List.of())));
        var receiver = queue.claim(WORLD, "crafter", Set.of(CRAFT), 1).job();
        assertNotNull(queue.renew("tools", "craft", receiver.lease(), null, 2, false, true));
        var firstPosition = new ProjectQueue.ResourceSite(5, 64, 4);
        var first = queue.offerRouteTool(WORLD, "helper", null, 3, null, 0, true,
                name -> firstPosition, (bot, item) -> 0).job();
        long now = first.expiresAt();
        assertNotNull(queue.renew("tools", "craft", receiver.lease(), null, 10, false, true));
        assertNull(queue.claim(WORLD, "helper", Set.of(RESUPPLY), now));
        var nextPosition = new ProjectQueue.ResourceSite(7, 65, 4);
        assertNull(queue.offerRouteTool(WORLD, "helper", null, now, null, 0, true,
                name -> nextPosition, (bot, item) -> 0));
        assertNull(queue.offerRouteTool(WORLD, "helper", null, now, null, 0, true,
                name -> nextPosition, (bot, item) -> 1));
        var retry = queue.offerRouteTool(WORLD, "helper", null, now, null, 0, true,
                name -> nextPosition, (bot, item) -> bot.equals("helper") ? 1 : 0).job();
        assertEquals(first.work().id() + "-retry", retry.work().id());
        var delivery = queue.routeToolDelivery("tools", retry.work().id(), retry.lease(), now);
        assertEquals("minecraft:crafting_table", delivery.item());
        assertEquals(nextPosition, delivery.position());
        assertNull(delivery.benchStock());
    }

    @Test void productionCanRequestAndAcknowledgeAWorkbenchWithoutRequestingAPickaxe() throws Exception {
        for (var kind : List.of(CRAFT, SMELT)) {
            var queue = new ProjectQueue(directory.resolve(kind.name() + "-bench-delivery.json"));
            queue.submit("tools", WORLD, List.of(job("craft", kind, List.of())));
            var receiver = queue.claim(WORLD, "crafter", Set.of(kind), 1).job();
            var site = new ProjectQueue.ResourceSite(5, 64, 4);
            assertNotNull(queue.renew("tools", "craft", receiver.lease(), null, 2, false, true));
            var spare = java.util.Map.of("minecraft:stone_pickaxe", 100);
            assertNull(queue.offerRouteTool(WORLD, "helper", null, 3, spare, 0, false, name -> site, (bot, item) -> 2));
            var delivery = queue.offerRouteTool(WORLD, "helper", null, 4, spare, 0, true,
                    name -> site, (bot, item) -> item.endsWith("pickaxe") ? 2 : 0).job();
            assertEquals("minecraft:crafting_table", queue.routeToolDelivery("tools", delivery.work().id(), delivery.lease(), 5).item());
            queue.recordRouteBenchReceipt("tools", "craft", receiver.lease(), 6);
            assertNull(queue.routeToolDelivery("tools", delivery.work().id(), delivery.lease(), 6));
        }
    }

    @Test void aWorkbenchReceiptCompletesTheCourierJobEvenAfterLeaseExpiryAndRestart() throws Exception {
        for (boolean expired : new boolean[]{false, true}) {
            var journal = directory.resolve("workbench-receipt-" + expired + ".json");
            var queue = new ProjectQueue(journal);
            queue.submit("tools", WORLD, List.of(job("craft", CRAFT, List.of())));
            var receiver = queue.claim(WORLD, "crafter", Set.of(CRAFT), 1).job();
            assertNotNull(queue.renew("tools", "craft", receiver.lease(), null, 2, false, true));
            var site = new ProjectQueue.ResourceSite(5, 64, 4);
            var courier = queue.offerRouteTool(WORLD, "courier", "tools", 3, null, 0, true,
                    name -> site, (name, item) -> 0).job();
            long now = expired ? courier.expiresAt() : 6;
            assertNotNull(queue.renew("tools", "craft", receiver.lease(), null, 5, false, true));
            if (expired) {
                assertEquals(ProjectQueue.State.FAILED, queue.snapshot(now).get(0).jobs().get(1).state());
                queue = new ProjectQueue(journal);
            }
            queue.recordRouteBenchReceipt("tools", "craft", receiver.lease(), now);
            queue = new ProjectQueue(journal);
            var delivery = queue.snapshot(now + 1).get(0).jobs().get(1);
            assertEquals(ProjectQueue.State.DONE, delivery.state());
            assertEquals("courier", delivery.worker());
            assertEquals(courier.lease(), delivery.lease());
            assertTrue(queue.finish("tools", courier.work().id(), courier.lease(), true, "Delivered workbench", now + 2));
        }
    }

    @Test void aNewReturnRouteGetsOneRetryWithTheSameCargoAndPosition() throws Exception {
        for (boolean observedStock : new boolean[]{false, true}) {
            var journal = directory.resolve("return-upgrade-" + observedStock + ".json");
            String chest = "block:3,64,0";
            String item = "minecraft:diamond";
            String key = "contribution:deposit:carrier";
            String project = "mine";
            var cave = new ProjectQueue.ResourceSite(3, -10, 0);
            var gather = new ProjectQueue.Work("gather", GATHER,
                    "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":64}",
                    List.of(), Set.of());
            var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                    "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":64,\"target\":{\"x\":3,\"y\":64,\"z\":0}}",
                    List.of("gather"), Set.of(chest), "gather");
            var observation = observedStock ? new ProjectQueue.ChestObservation(
                    new ProjectQueue.ChestReport(chest, java.util.Map.of()), 1) : null;
            var jobs = List.of(new ProjectQueue.Job(gather, ProjectQueue.State.FAILED, 3, null, null, 0,
                            ProjectQueue.GATHER_TOOLS_NEEDED + " after 2 supply recoveries", null, cave),
                    new ProjectQueue.Job(deposit, ProjectQueue.State.WAITING, 1, null, null, 0,
                            "Partial stock contribution settled", observation));
            var seeded = new ProjectQueue.Project(project, WORLD, jobs, null, chest,
                    java.util.Map.of(key, 3), java.util.Map.of(key, cave));
            Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                    "projects", java.util.Map.of(project, seeded))));
            var queue = new ProjectQueue(journal);
            assertNull(queue.offerInterruptedDeposit(WORLD, "carrier", null, 2, cave, (bot, material) -> 3));
            assertNull(queue.offerInterruptedDeposit(WORLD, "empty", null, 3, cave, (bot, material) -> 0, 1));
            assertNotNull(queue.offerInterruptedDeposit(WORLD, "carrier", null, 4, cave, (bot, material) -> 3, 1));
            var lease = queue.claim(WORLD, "carrier", Set.of(TRANSFER), 5).job();
            assertTrue(queue.finish(project, "deposit", lease.lease(), false, ProjectQueue.TARGET_TIMEOUT, 6));
            queue = new ProjectQueue(journal);
            assertNull(queue.offerInterruptedDeposit(WORLD, "carrier", null, 7, cave, (bot, material) -> 3, 1));
        }
    }

    @Test void aCarrierReachingChestRangeCanRetryAnUnchangedLoadOnce() throws Exception {
        var journal = directory.resolve("close-range-deposit-retry.json");
        String chest = "block:-8,124,10";
        String diamond = "minecraft:diamond";
        var gather = new ProjectQueue.Work("ore", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":19}",
                List.of(), Set.of());
        var deposit = new ProjectQueue.Work("bank", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":35,\"target\":{\"x\":-8,\"y\":124,\"z\":10}}",
                List.of(gather.id()), Set.of(chest), gather.id());
        var origin = new ProjectQueue.ResourceSite(1, 121, 2);
        var near = new ProjectQueue.ResourceSite(-5, 125, 9);
        var receipt = new ProjectQueue.ChestObservation(new ProjectQueue.ChestReport(chest, java.util.Map.of(diamond, 16)), 1);
        var jobs = List.of(
                new ProjectQueue.Job(gather, ProjectQueue.State.FAILED, 3, null, null, 0, ProjectQueue.GATHER_STALLED, null, origin),
                new ProjectQueue.Job(deposit, ProjectQueue.State.WAITING, 1, null, null, 0, "Partial stock contribution settled", receipt));
        String key = "contribution:bank:carrier";
        var project = new ProjectQueue.Project("mine", WORLD, jobs, null, chest,
                java.util.Map.of(key, 2, key + ":stock", 16), java.util.Map.of(key, origin));
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1, "projects", java.util.Map.of("mine", project))));

        var queue = new ProjectQueue(journal);
        assertNull(queue.offerInterruptedDeposit(WORLD, "carrier", "mine", 2, origin, (worker, item) -> 2));
        var retry = queue.offerInterruptedDeposit(WORLD, "carrier", "mine", 3, near, (worker, item) -> 2).job();
        assertEquals(deposit.id(), retry.work().id());
        assertTrue(queue.finish("mine", deposit.id(), retry.lease(), false, ProjectQueue.TARGET_TIMEOUT, 4));
        var restored = new ProjectQueue(journal);
        assertNull(restored.offerInterruptedDeposit(WORLD, "carrier", "mine", 5, near, (worker, item) -> 2));
    }

    @Test void aBystanderCanFinishTheChestOrderWhileTheOriginalMinerHasALease() throws Exception {
        var journal = directory.resolve("mining-drop-bystander.json");
        String world = WORLD;
        String chest = "block:3,64,0";
        String diamond = "minecraft:diamond";
        String project = "mine";
        var craftWork = new ProjectQueue.Work("tool", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\"}", List.of(), Set.of());
        var gatherWork = new ProjectQueue.Work("gather", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":4}",
                List.of("tool"), Set.of(), "tool");
        var depositWork = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":4}",
                List.of("gather"), Set.of(chest), "gather");
        var site = new ProjectQueue.ResourceSite(4, -20, 0);
        var jobs = List.of(new ProjectQueue.Job(craftWork, ProjectQueue.State.DONE, 1, "old-miner", null,
                        0, "Crafted", null),
                new ProjectQueue.Job(gatherWork, ProjectQueue.State.FAILED, 3, null, null, 0,
                        ProjectQueue.GATHER_TOOLS_NEEDED + " after 2 supply recoveries", null, site),
                new ProjectQueue.Job(depositWork, ProjectQueue.State.WAITING, 0, null, null, 0, null, null));
        var seeded = new ProjectQueue.Project(project, world, jobs, null, chest, java.util.Map.of());
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of(project, seeded))));
        var queue = new ProjectQueue(journal);
        var bank = queue.offerInterruptedDeposit(world, "carrier", null, 1, site, (bot, item) -> 3).job();
        assertTrue(queue.finish(project, "deposit", bank.lease(), false, "Delivery is short by 1",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(diamond, 3)), null, true, 2));
        var ore = queue.offerEquippedMiner(world, "replacement", null,
                java.util.Map.of("minecraft:iron_pickaxe", 200), 3).job();
        assertNull(queue.claim(world, "carrier", Set.of(TRANSFER), null, 4, site, material -> false,
                (bot, item) -> 0, null));
        assertNull(queue.claim(world, "carrier", Set.of(TRANSFER), null, 5, site, material -> false,
                (bot, item) -> 1, null));
        bank = queue.claim(world, "carrier", Set.of(TRANSFER), null, 6, site, material -> false,
                (bot, item) -> bot.equals("carrier") ? 1 : 0, null).job();
        assertEquals("deposit", bank.work().id());
        assertTrue(queue.finish(project, "deposit", bank.lease(), true, "Delivered",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(diamond, 4)), null, 7));
        queue = new ProjectQueue(journal);
        assertNull(queue.renew(project, "gather", ore.lease(), 8));
        assertEquals(ProjectQueue.State.LEASED, queue.snapshot(8).get(0).jobs().get(1).state());
        var settled = queue.snapshot(ore.expiresAt()).get(0).jobs().get(1);
        assertEquals(ProjectQueue.State.DONE, settled.state());
        assertEquals("carrier", settled.worker());
        assertEquals(site, settled.resourceSite());
        assertEquals(4, queue.chestStock(world).get(chest).report().items().get(diamond));
    }

    @Test void exhaustedSurfaceLeaseCanRecoverOnceFromAConfirmedNewArea() throws Exception {
        var queue = new ProjectQueue(directory.resolve("expired-surface-gather.json"));
        String chest = "block:3,64,0";
        var gather = new ProjectQueue.Work("cut", GATHER,
                "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\",\"count\":9}",
                List.of(), Set.of());
        var deposit = new ProjectQueue.Work("bank", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"any_logs\",\"stock\":9}",
                List.of(gather.id()), Set.of(chest), gather.id());
        queue.submitSupplied("logs", WORLD, List.of(gather, deposit), chest);
        long next = 1;
        for (int attempt = 1; attempt <= 3; attempt++) {
            var lease = queue.claim(WORLD, "first", Set.of(GATHER), next).job();
            assertEquals(attempt, lease.attempts());
            next = lease.expiresAt() + 1;
        }
        assertNull(queue.claim(WORLD, "replacement", Set.of(GATHER), next));
        var request = queue.exhaustedSurfaceGatherings(WORLD, next).get(0);
        var surface = new ProjectQueue.ResourceSite(19, 84, 7);
        assertTrue(queue.recoverObservedGather(WORLD, request, "replacement", surface, next, (worker, item) -> 0));
        var resumed = queue.claim(WORLD, "replacement", Set.of(GATHER), next + 1).job();
        assertEquals(surface, resumed.resourceSite());
        assertNull(queue.claim(WORLD, "third", Set.of(GATHER), resumed.expiresAt() + 1));
        assertTrue(queue.exhaustedSurfaceGatherings(WORLD, resumed.expiresAt() + 1).isEmpty());
    }

    @Test void observedWoodReopensOnlyTheWorkerWithTheSmeltedIngredients() throws Exception {
        var queue = new ProjectQueue(directory.resolve("observed-stick-preparation.json"));
        String chest = "block:3,64,0";
        var smelt = new ProjectQueue.Work("smelt", SMELT, "{\"count\":3}", List.of(), Set.of());
        var gather = new ProjectQueue.Work("wood", GATHER,
                "{\"resource\":\"block:minecraft:oak_log\",\"variant\":\"both\",\"item\":\"minecraft:oak_log\",\"count\":1}",
                List.of("smelt"), Set.of(), "smelt");
        var planks = new ProjectQueue.Work("planks", CRAFT,
                "{\"recipe\":\"minecraft:oak_planks\",\"inventory\":true,\"count\":2}",
                List.of("wood"), Set.of(), "wood");
        var sticks = new ProjectQueue.Work("sticks", CRAFT,
                "{\"recipe\":\"minecraft:stick\",\"inventory\":true,\"count\":2}",
                List.of("planks"), Set.of(), "planks");
        var tool = new ProjectQueue.Work("tool", CRAFT,
                "{\"recipe\":\"minecraft:iron_pickaxe\",\"count\":1}", List.of("sticks"), Set.of(), "sticks");
        queue.submitSupplied("tools", WORLD, List.of(smelt, gather, planks, sticks, tool), chest);
        var original = queue.claim(WORLD, "miner", Set.of(SMELT), 1).job();
        assertTrue(queue.finish("tools", "smelt", original.lease(), true, "Observed 3 minecraft:iron_ingot already carried by the worker", 2));
        long now = 3;
        for (int attempt = 1; attempt <= 3; attempt++) {
            var lease = queue.claim(WORLD, "miner", Set.of(GATHER), now).job();
            now = lease.expiresAt() + 2;
        }
        assertNull(queue.claim(WORLD, "miner", Set.of(GATHER), now));
        assertNull(queue.claim(WORLD, "helper", Set.of(GATHER), now));
        var observed = queue.exhaustedSurfaceGatherings(WORLD, now).get(0);
        var surface = new ProjectQueue.ResourceSite(28, 75, 2);
        assertEquals("miner", observed.pinnedWorker());
        assertFalse(queue.recoverObservedGather(WORLD, observed, "helper", surface, now, (worker, item) -> 3));
        assertFalse(queue.recoverObservedGather(WORLD, observed, "miner", surface, now, (worker, item) -> 2));
        assertTrue(queue.recoverObservedGather(WORLD, observed, "miner", surface, now, (worker, item) -> 3));
        var resumed = queue.claim(WORLD, "miner", Set.of(GATHER), now + 1);
        assertEquals(2, resumed.flexibleStickCount());
        assertEquals(surface, resumed.job().resourceSite());
        assertTrue(queue.finish("tools", "wood", resumed.job().lease(), true, ProjectQueue.PREPARED_COMPATIBLE_STICKS, now + 2));
        assertEquals("tool", queue.claim(WORLD, "miner", Set.of(CRAFT), now + 3).job().work().id());
        assertTrue(queue.exhaustedSurfaceGatherings(WORLD, now + 3).isEmpty());
    }

    @Test void exhaustedMiningLeaseRebuildsPrivateToolsOnlyAfterObservedInventoryLoss() throws Exception {
        var journal = directory.resolve("expired-private-mining-tools.json");
        String chest = "block:3,64,0";
        var supplies = job("supplies", GATHER, List.of());
        var tool = new ProjectQueue.Work("tool", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\",\"count\":1}",
                List.of(supplies.id()), Set.of(), supplies.id());
        var mining = new ProjectQueue.Work("mining", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":8}",
                List.of(tool.id()), Set.of(), tool.id());
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":8}",
                List.of(mining.id()), Set.of(chest), mining.id());
        var site = new ProjectQueue.ResourceSite(17, -54, 2);
        var jobs = List.of(
                new ProjectQueue.Job(supplies, ProjectQueue.State.DONE, 1, "original", null, 0, "Observed supplies", null),
                new ProjectQueue.Job(tool, ProjectQueue.State.DONE, 1, "original", null, 0, "Observed pickaxe", null),
                new ProjectQueue.Job(mining, ProjectQueue.State.FAILED, 3, null, null, 0, "Worker lease expired", null, site),
                new ProjectQueue.Job(deposit, ProjectQueue.State.WAITING, 0, null, null, 0, null, null));
        var project = new ProjectQueue.Project("stock", WORLD, jobs, null, chest, java.util.Map.of());
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1, "projects", java.util.Map.of("stock", project))));
        var queue = new ProjectQueue(journal);
        assertNull(queue.claim(WORLD, "replacement", Set.of(GATHER), "stock", 1, site, item -> false,
                (worker, item) -> -1, null));
        assertNull(queue.claim(WORLD, "replacement", Set.of(GATHER), "stock", 2, site, item -> false,
                (worker, item) -> item.equals("minecraft:diamond_pickaxe") ? 1 : 0, null));
        assertNull(queue.claim(WORLD, "replacement", Set.of(GATHER), "stock", 3, site, item -> false,
                (worker, item) -> item.equals("minecraft:diamond") ? 1 : 0, null));
        var prep = queue.claim(WORLD, "replacement", Set.of(GATHER), "stock", 4, site, item -> false,
                (worker, item) -> 0, null).job();
        assertEquals(supplies.id(), prep.work().id());

        var recovered = new ProjectQueue(journal).snapshot(5).get(0);
        assertEquals(1, recovered.supplyRecoveries().get("expired-miner-tools:mining"));
        assertEquals(ProjectQueue.State.WAITING, recovered.jobs().get(2).state());
        assertEquals(site, recovered.jobs().get(2).resourceSite());
    }

    @Test void expiredGatheringWithASavedSiteAllowsTheOriginalWorkerToReconnect() throws Exception {
        for (boolean coordinatorRestart : new boolean[]{false, true}) {
            var journal = directory.resolve("gather-reconnect-" + coordinatorRestart + ".json");
            var queue = new ProjectQueue(journal);
            queue.submit("timber", WORLD, List.of(new ProjectQueue.Work("logs", GATHER,
                    "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\"}", List.of(), Set.of(), null)));
            var lease = queue.claim(WORLD, "gatherer", Set.of(GATHER), 1).job();
            var site = new ProjectQueue.ResourceSite(16, 100, 42);
            var renewed = queue.renew("timber", "logs", lease.lease(), site, 2);
            if (coordinatorRestart) queue = new ProjectQueue(journal);
            assertNull(queue.claim(WORLD, "replacement", Set.of(GATHER), renewed.expiresAt()));
            var restored = new ProjectQueue(journal);
            var resumed = restored.claim(WORLD, "gatherer", Set.of(GATHER), renewed.expiresAt() + 1).job();
            assertEquals(site, resumed.resourceSite());
            assertEquals(coordinatorRestart ? 1 : 2, resumed.attempts());
            assertNotEquals(lease.lease(), resumed.lease());
            resumed = restored.renew("timber", "logs", resumed.lease(), site, renewed.expiresAt() + 2);
            assertNull(restored.claim(WORLD, "replacement", Set.of(GATHER), resumed.expiresAt() + 29_999));
            assertEquals("replacement", restored.claim(WORLD, "replacement", Set.of(GATHER),
                    resumed.expiresAt() + 30_000).job().worker());
        }
    }

    @Test void observedTimberRestartsAnExhaustedCraftingChainWithANewWorker() throws Exception {
        var journal = directory.resolve("observed-crafting-input.json");
        var timber = new ProjectQueue.Work("timber", GATHER,
                """
                {"resource":"block:minecraft:oak_log","variant":"both","item":"minecraft:oak_log","count":2}
                """, List.of(), Set.of());
        var planks = new ProjectQueue.Work("planks", CRAFT,
                """
                {"recipe":"minecraft:oak_planks","count":8,"inventory":true}
                """, List.of(timber.id()), Set.of(), timber.id());
        var queue = new ProjectQueue(journal);
        queue.submitSupplied("stock", WORLD, List.of(timber, planks), "block:3,64,0");
        for (int attempt = 0; attempt < 3; attempt++) {
            long now = 1 + attempt * 31_000L;
            var lease = queue.claim(WORLD, "original", Set.of(GATHER), now).job();
            assertTrue(queue.finish("stock", timber.id(), lease.lease(), false, ProjectQueue.GATHER_STALLED,
                    null, new ProjectQueue.ResourceSite(12, 64, 0), now + 1));
        }

        var restored = new ProjectQueue(journal);
        assertTrue(restored.exhaustedSurfaceGatherings(WORLD, 92_001).isEmpty());
        assertNull(restored.claim(WORLD, "nearby", Set.of(GATHER, CRAFT), 93_000));
        var observation = restored.exhaustedSurfaceGatherings(WORLD, 93_000).get(0);
        var treeStand = new ProjectQueue.ResourceSite(5, 70, 0);
        assertTrue(restored.recoverObservedGather(WORLD, observation, "nearby", treeStand, 93_001, (worker, item) -> 0));
        assertFalse(restored.recoverObservedGather(WORLD, observation, "nearby", treeStand, 93_002, (worker, item) -> 0));
        var retry = restored.claim(WORLD, "nearby", Set.of(GATHER), 93_003).job();
        assertEquals(treeStand, retry.resourceSite());
        assertTrue(restored.finish("stock", timber.id(), retry.lease(), true, "Observed 2 minecraft:oak_log in worker inventory",
                null, treeStand, 93_004));
        assertNull(restored.claim(WORLD, "original", Set.of(CRAFT), 93_005));
        assertEquals(planks.id(), restored.claim(WORLD, "nearby", Set.of(CRAFT), 93_006).job().work().id());
        assertTrue(new ProjectQueue(journal).exhaustedSurfaceGatherings(WORLD, 125_000).isEmpty());
    }

    @Test void stalledIndependentStockGatherGetsOneFreshAttemptAcrossRestarts() throws Exception {
        var journal = directory.resolve("stalled-independent-stock.json");
        String chest = "block:3,64,0";
        var failedSite = new ProjectQueue.ResourceSite(12, 64, 0);
        var gather = new ProjectQueue.Work("gather", GATHER,
                "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\",\"count\":4}", List.of(), Set.of());
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"any_logs\",\"stock\":4}", List.of(gather.id()), Set.of(chest), gather.id());
        var concurrentDeposit = new ProjectQueue.Work("bank-stone", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:cobblestone\",\"stock\":4}", List.of(), Set.of(chest));
        var queue = new ProjectQueue(journal);
        queue.submitSupplied("stock", WORLD, List.of(gather, deposit, concurrentDeposit), chest);

        for (int attempt = 0; attempt < 3; attempt++) {
            long now = 1 + attempt * 31_000L;
            var lease = queue.claim(WORLD, "initial", Set.of(GATHER), now).job();
            assertTrue(queue.finish("stock", gather.id(), lease.lease(), false, ProjectQueue.GATHER_STALLED,
                    null, failedSite, now + 1));
        }

        var restored = new ProjectQueue(journal);
        assertNull(restored.claim(WORLD, "replacement", Set.of(GATHER), 92_001));
        var stoneTransfer = restored.claim(WORLD, "carrier", Set.of(TRANSFER), 92_002).job();
        assertEquals(concurrentDeposit.id(), stoneTransfer.work().id());
        var retry = restored.claim(WORLD, "replacement", Set.of(GATHER), 93_000).job();
        assertEquals(gather.id(), retry.work().id());
        assertNull(retry.resourceSite());
        assertEquals(1, restored.snapshot(93_000).get(0).supplyRecoveries().get("stalled-stock-gather:gather"));
        assertTrue(restored.finish("stock", gather.id(), retry.lease(), false, ProjectQueue.GATHER_STALLED,
                null, failedSite, 93_001));
        assertTrue(restored.finish("stock", concurrentDeposit.id(), stoneTransfer.lease(), true, "Banked stone",
                new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:cobblestone", 4)), 93_002));

        var exhausted = new ProjectQueue(journal);
        assertNull(exhausted.claim(WORLD, "third", Set.of(GATHER, TRANSFER), 125_000));
        assertEquals(ProjectQueue.State.FAILED, exhausted.snapshot(125_000).get(0).jobs().get(0).state());
        var observation = exhausted.exhaustedSurfaceGatherings(WORLD, 125_000).get(0);
        var feet = new ProjectQueue.ResourceSite(3, 70, 0);
        assertFalse(exhausted.recoverObservedGather(WORLD, observation, "unenrolled", feet, 125_000, (worker, item) -> 0));
        assertTrue(exhausted.recoverObservedGather(WORLD, observation, "third", feet, 125_001, (worker, item) -> 0));
        assertFalse(exhausted.recoverObservedGather(WORLD, observation, "third", feet, 125_002, (worker, item) -> 0));
        var observedRetry = exhausted.claim(WORLD, "third", Set.of(GATHER), 125_003).job();
        assertEquals(feet, observedRetry.resourceSite());
        assertTrue(exhausted.finish("stock", gather.id(), observedRetry.lease(), false, ProjectQueue.GATHER_STALLED,
                null, feet, 125_004));

        var reloaded = new ProjectQueue(journal);
        assertTrue(reloaded.exhaustedSurfaceGatherings(WORLD, 160_000).isEmpty());
        assertNull(reloaded.claim(WORLD, "fourth", Set.of(GATHER), 160_000));
    }

    @Test void twoWorkersCanCompleteOneStockBatchFromTheirPartialInventories() throws Exception {
        var journal = directory.resolve("partial-stock.json");
        var queue = new ProjectQueue(journal);
        String project = "stock";
        String chest = "block:3,64,0";
        queue.submitSupplied(project, WORLD, List.of(new ProjectQueue.Work("gather", GATHER,
                "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\",\"count\":64}", List.of(), Set.of(), null),
                new ProjectQueue.Work("deposit", TRANSFER, "{\"direction\":\"DEPOSIT\",\"item\":\"any_logs\",\"stock\":128}",
                        List.of("gather"), Set.of(chest), "gather")), chest);
        var active = queue.claim(WORLD, "gatherer", Set.of(GATHER), 1).job();
        var part = queue.offerGatheringHandoff(WORLD, "hauler", null, 2, (name, item) -> name.equals("hauler") ? 44 : 32).job();
        assertEquals("deposit", part.work().id());
        var restored = new ProjectQueue(journal);
        part = restored.renew(project, "deposit", part.lease(), 3);
        assertTrue(restored.finish(project, "deposit", part.lease(), false, "Partial delivery",
                new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:oak_log", 102, "minecraft:birch_log", 6)),
                null, true, 4));
        assertNull(restored.renew(project, "gather", active.lease(), 5));
        var job = restored.claim(WORLD, "gatherer", Set.of(GATHER), active.expiresAt()).job();
        assertEquals(20, com.google.gson.JsonParser.parseString(job.work().payload()).getAsJsonObject().get("count").getAsInt());
        assertTrue(restored.finish(project, "gather", job.lease(), true, "Gathered", active.expiresAt() + 1));
        var deposit = restored.claim(WORLD, "gatherer", Set.of(TRANSFER), active.expiresAt() + 2).job();
        assertTrue(restored.finish(project, "deposit", deposit.lease(), true, "Delivered",
                new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:oak_log", 122, "minecraft:birch_log", 6)),
                active.expiresAt() + 3));
    }

    @Test void gatheringHandoffWaitsForTheOldLeaseAndKeepsTheSavedSite() throws Exception {
        var journal = directory.resolve("gather-cargo-handoff.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        queue.submitSupplied("timber", WORLD, List.of(new ProjectQueue.Work("logs", GATHER,
                "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\"}", List.of(), Set.of(), null),
                new ProjectQueue.Work("deposit", TRANSFER, "{\"direction\":\"DEPOSIT\",\"item\":\"any_logs\"}",
                        List.of("logs"), Set.of(chest), "logs")), chest);
        var lease = queue.claim(WORLD, "new-miner", Set.of(GATHER), 1).job();
        var site = new ProjectQueue.ResourceSite(16, 100, 42);
        lease = queue.renew("timber", "logs", lease.lease(), site, 2);
        queue.offerGatheringHandoff(WORLD, "old-miner", null, 3, (name, item) -> name.equals("old-miner") ? 44 : 2);
        var restored = new ProjectQueue(journal);
        assertNull(restored.renew("timber", "logs", lease.lease(), site, 4));
        assertNull(restored.claim(WORLD, "old-miner", Set.of(GATHER), lease.expiresAt() - 1));
        assertNull(restored.claim(WORLD, "new-miner", Set.of(GATHER), lease.expiresAt()));
        var assigned = restored.claim(WORLD, "old-miner", Set.of(GATHER), lease.expiresAt()).job();
        assertEquals(site, assigned.resourceSite());
        assertEquals(1, assigned.attempts());
        assertTrue(restored.finish("timber", "logs", assigned.lease(), true, "Gathered", lease.expiresAt() + 1));
        assertEquals("deposit", restored.claim(WORLD, "old-miner", Set.of(TRANSFER),
                lease.expiresAt() + 2).job().work().id());
    }

    @Test void exhaustedToolsCanBankCarriedLootBeforeResumingTheRemainingGather() throws Exception {
        var journal = directory.resolve("interrupted-loot.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String project = "stock";
        String item = "minecraft:diamond";
        var gather = new ProjectQueue.Work("dig", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":64}",
                List.of("craft-pickaxe"), Set.of(), "craft-pickaxe");
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":64,\"target\":{\"x\":3,\"y\":64,\"z\":0}}",
                List.of("dig"), Set.of(chest), "dig");
        queue.submitSupplied(project, WORLD, List.of(
                job("find-tool-materials", GATHER, List.of()),
                new ProjectQueue.Work("craft-pickaxe", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\"}",
                        List.of("find-tool-materials"), Set.of(), "find-tool-materials"),
                gather, deposit), chest);
        var raw = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        assertTrue(queue.finish(project, raw.work().id(), raw.lease(), true, "Stocked tool supplies", 2));
        var pick = queue.claim(WORLD, "miner", Set.of(CRAFT), 3).job();
        assertTrue(queue.finish(project, pick.work().id(), pick.lease(), true, "Crafted", 4));
        var dig = queue.claim(WORLD, "miner", Set.of(GATHER), 5).job();
        assertNull(queue.offerInterruptedDeposit(WORLD, "miner", null, 6, (bot, material) -> 7));
        var site = new ProjectQueue.ResourceSite(10, -53, 4);
        assertTrue(queue.finish(project, "dig", dig.lease(), false, ProjectQueue.GATHER_TOOLS_NEEDED, null, site, 7));
        var restarted = new ProjectQueue(journal);
        var recovery = restarted.offerInterruptedDeposit(WORLD, "miner", null, 8, (bot, material) -> 7).job();
        assertEquals("deposit", recovery.work().id());
        assertNull(restarted.offerInterruptedDeposit(WORLD, "helper", null, 9, (bot, material) -> 7));
        assertTrue(restarted.finish(project, "deposit", recovery.lease(), false, ProjectQueue.TARGET_TIMEOUT, 10));
        var restored = new ProjectQueue(journal);
        assertNull(restored.offerInterruptedDeposit(WORLD, "miner", null, 11, (bot, material) -> 7));
        var eligibleAt = restored.snapshot(11).get(0).jobs().get(3).expiresAt();
        recovery = restored.offerInterruptedDeposit(WORLD, "miner", null, eligibleAt, (bot, material) -> 7).job();
        assertEquals(1, recovery.attempts());
        var chestReport = new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 7));
        assertTrue(restored.finish(project, "deposit", recovery.lease(), false, "Delivery is short by 57",
                chestReport, null, true, eligibleAt + 1));
        var jobs = restored.snapshot(eligibleAt + 2).get(0).jobs();
        var remaining = jobs.get(2);
        assertEquals(ProjectQueue.State.WAITING, remaining.state());
        assertEquals(site, remaining.resourceSite());
        assertEquals(57, com.google.gson.JsonParser.parseString(remaining.work().payload()).getAsJsonObject().get("count").getAsInt());
        assertEquals(ProjectQueue.State.WAITING, jobs.get(3).state());
        assertEquals(chestReport, restored.chestStock(WORLD).get(chest).report());
        assertNull(restored.offerInterruptedDeposit(WORLD, "miner", null, eligibleAt + 3, (bot, material) -> 7));
        var legacy = restored.offerInterruptedDeposit(WORLD, "miner", null, eligibleAt + 4, site,
                (bot, material) -> 7).job();
        assertTrue(restored.finish(project, "deposit", legacy.lease(), false, ProjectQueue.TARGET_TIMEOUT, eligibleAt + 5));
        assertNull(restored.offerInterruptedDeposit(WORLD, "miner", null, eligibleAt + 6, site, (bot, material) -> 7));
        eligibleAt += 3;
        var loaded = new ProjectQueue(journal);
        var additional = loaded.offerInterruptedDeposit(WORLD, "helper", null, eligibleAt + 4, site,
                (bot, material) -> 3).job();
        assertEquals(chestReport, additional.chest().report());
        var combined = new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 10));
        assertTrue(loaded.finish(project, "deposit", additional.lease(), false, "Delivery is short by 54", combined,
                null, true, eligibleAt + 5));
        assertEquals(combined, loaded.chestStock(WORLD).get(chest).report());
        var outstanding = loaded.snapshot(eligibleAt + 6).get(0).jobs().get(2);
        assertEquals(54, com.google.gson.JsonParser.parseString(outstanding.work().payload()).getAsJsonObject().get("count").getAsInt());
        var reopened = new ProjectQueue(journal);
        assertNull(reopened.offerInterruptedDeposit(WORLD, "helper", null, eligibleAt + 7, site, (bot, material) -> 3));
        var minorMove = new ProjectQueue.ResourceSite(12, -52, 4);
        assertNull(reopened.offerInterruptedDeposit(WORLD, "helper", null, eligibleAt + 8, minorMove, (bot, material) -> 3));
        var ascent = new ProjectQueue.ResourceSite(10, -40, 4);
        var newRoute = reopened.offerInterruptedDeposit(WORLD, "helper", null, eligibleAt + 9, ascent,
                (bot, material) -> 3).job();
        assertTrue(reopened.finish(project, "deposit", newRoute.lease(), false, ProjectQueue.TARGET_TIMEOUT, eligibleAt + 10));
        assertNull(reopened.offerInterruptedDeposit(WORLD, "helper", null, eligibleAt + 11, ascent, (bot, material) -> 3));
        assertNull(reopened.offerInterruptedDeposit(WORLD, "helper", null, eligibleAt + 12, ascent, (bot, material) -> 0));
        assertNotNull(reopened.offerInterruptedDeposit(WORLD, "helper", null, eligibleAt + 13, ascent, (bot, material) -> 2));
    }

    @Test void stationaryGathererYieldsClaimWithoutAnchoringReplacementToTheOldSite() throws Exception {
        var journal = directory.resolve("stalled-gather.json");
        var queue = new ProjectQueue(journal);
        queue.submit("gathering", WORLD, List.of(job("gather", GATHER, List.of()),
                new ProjectQueue.Work("refine", CRAFT, "", List.of("gather"), Set.of(), "gather")));
        var stalled = queue.claim(WORLD, "first", Set.of(GATHER), 1).job();
        var site = new ProjectQueue.ResourceSite(5, 68, -3);
        assertTrue(queue.finish("gathering", "gather", stalled.lease(), false, ProjectQueue.GATHER_STALLED, null, site, 2));
        var restored = new ProjectQueue(journal);
        assertNull(restored.claim(WORLD, "first", Set.of(GATHER), 3));
        var retry = restored.claim(WORLD, "second", Set.of(GATHER), 4).job();
        assertNull(retry.resourceSite());
        assertNull(restored.claim(WORLD, "first", Set.of(GATHER), 5));
        var newSite = new ProjectQueue.ResourceSite(32, 68, -10);
        assertTrue(restored.finish("gathering", "gather", retry.lease(), true, "Collected", null, newSite, 6));
        assertEquals(newSite, restored.snapshot(6).get(0).jobs().get(0).resourceSite());
        assertEquals("refine", restored.claim(WORLD, "second", Set.of(CRAFT), 7).job().work().id());
    }

    @Test void returningCargoCanBankWhileAnotherWorkerIsGathering() throws Exception {
        var queue = new ProjectQueue(directory.resolve("parallel-retained-cargo.json"));
        String chest = "block:3,64,0";
        var gather = new ProjectQueue.Work("dig", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":64}",
                List.of(), Set.of());
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":64}",
                List.of("dig"), Set.of(chest), "dig");
        queue.submitSupplied("stock", WORLD, List.of(gather, deposit), chest);
        var failed = queue.claim(WORLD, "first", Set.of(GATHER), 1).job();
        assertTrue(queue.finish("stock", "dig", failed.lease(), false, ProjectQueue.GATHER_STALLED, 2));
        var earlier = queue.offerInterruptedDeposit(WORLD, "first", null, 3, (worker, item) -> 2).job();
        assertTrue(queue.finish("stock", "deposit", earlier.lease(), false, "Partial delivery",
                new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:diamond", 2)), null, true, 4));
        var miner = queue.claim(WORLD, "miner", Set.of(GATHER), 5).job();
        var delivery = queue.offerInterruptedDeposit(WORLD, "carrier", null, 6, (worker, item) -> 3).job();
        assertNotNull(queue.renew("stock", "dig", miner.lease(), 7));
        assertTrue(queue.finish("stock", "deposit", delivery.lease(), false, "Partial delivery",
                new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:diamond", 5)), null, true, 8));
        assertEquals(59, com.google.gson.JsonParser.parseString(queue.snapshot(9).get(0).jobs().get(0).work().payload())
                .getAsJsonObject().get("count").getAsInt());
    }

    @Test void interruptedMinerBanksAgainstTheEarliestUnmetQuotaOfTheSameChest() throws Exception {
        String chest = "block:3,64,0";
        String item = "minecraft:diamond";
        var journal = directory.resolve("ordered-mining-contributions.json");
        var work = new ArrayList<ProjectQueue.Work>();
        for (int lane = 0; lane < 3; lane++) {
            String gather = "gather-" + lane;
            work.add(new ProjectQueue.Work(gather, GATHER,
                    "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":32}",
                    List.of(), Set.of()));
            var needs = lane == 0 ? List.of(gather) : List.of(gather, "bank-" + (lane - 1));
            work.add(new ProjectQueue.Work("bank-" + lane, TRANSFER,
                    "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":" + ((lane + 1) * 32) + "}",
                    needs, Set.of(chest), gather));
        }
        var waiting = work.stream().map(stage -> new ProjectQueue.Job(stage, ProjectQueue.State.WAITING,
                0, null, null, 0, stage.id().equals("gather-2")
                ? ProjectQueue.GATHER_TOOLS_NEEDED + "; reacquiring materials" : null, null)).toList();
        var project = new ProjectQueue.Project("stock", WORLD, waiting, null, chest, java.util.Map.of());
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of("stock", project))));
        var queue = new ProjectQueue(journal);
        var position = new ProjectQueue.ResourceSite(3, -50, 0);
        var carrier = queue.offerUrgentMiningDeposit(WORLD, "later-miner", null, 10, position,
                (worker, material) -> item.equals(material) ? 8 : 0, 8).job();
        assertEquals("bank-0", carrier.work().id());
        assertNull(queue.offerUrgentMiningDeposit(WORLD, "another-miner", null, 11, position,
                (worker, material) -> 8, 8));
        queue = new ProjectQueue(journal);
        assertTrue(queue.finish("stock", "bank-0", carrier.lease(), false, "Partial delivery",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 8)), null, true, 12));
        var jobs = queue.snapshot(13).get(0).jobs();
        assertEquals(24, com.google.gson.JsonParser.parseString(jobs.get(0).work().payload())
                .getAsJsonObject().get("count").getAsInt());
        assertEquals(ProjectQueue.State.WAITING, jobs.get(1).state());
        assertEquals(ProjectQueue.State.WAITING, jobs.get(5).state());
        assertEquals(32, com.google.gson.JsonParser.parseString(jobs.get(4).work().payload())
                .getAsJsonObject().get("count").getAsInt());
    }

    @Test void laterCheckpointBanksAgainstAnActiveEarlierMinerAndUpdatesOnlyVerifiedStock() throws Exception {
        String chest = "block:3,64,0";
        String item = "minecraft:diamond";
        var cave = new ProjectQueue.ResourceSite(12, -54, 23);
        var journal = directory.resolve("active-mining-contribution.json");
        var queue = new ProjectQueue(journal);
        var first = job("first", GATHER, List.of()).withPayload("""
                {"resource":"diamond","variant":"both","item":"minecraft:diamond","count":13}
                """);
        var later = job("later", GATHER, List.of()).withPayload(first.payload());
        var firstBank = new ProjectQueue.Work("first-bank", TRANSFER,
                """
                {"direction":"DEPOSIT","item":"minecraft:diamond","stock":13}
                """, List.of(first.id()), Set.of(chest), first.id());
        var laterBank = new ProjectQueue.Work("later-bank", TRANSFER,
                """
                {"direction":"DEPOSIT","item":"minecraft:diamond","stock":26}
                """, List.of(later.id(), firstBank.id()), Set.of(chest), later.id());
        queue.submitSupplied("stock", WORLD, List.of(first, firstBank, later, laterBank), chest);
        var early = queue.claim(WORLD, "early-miner", Set.of(GATHER), 1).job();
        var checkpoint = queue.claim(WORLD, "later-miner", Set.of(GATHER), 2).job();
        assertTrue(queue.finish("stock", later.id(), checkpoint.lease(), false,
                ProjectQueue.CARGO_CHECKPOINT, null, cave, 3));

        var deposit = queue.offerUrgentMiningDeposit(WORLD, "later-miner", null, 4, cave,
                (worker, material) -> item.equals(material) && worker.equals("later-miner") ? 4 : 0, 8).job();
        assertEquals(firstBank.id(), deposit.work().id());
        assertNotNull(queue.renew("stock", first.id(), early.lease(), cave, 5));
        assertNull(queue.offerUrgentMiningDeposit(WORLD, "later-miner", null, 6, cave,
                (worker, material) -> 4, 8));
        queue = new ProjectQueue(journal);
        assertTrue(queue.finish("stock", firstBank.id(), deposit.lease(), false, "Observed part of target stock",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 4)), null, true, 7));
        var jobs = queue.snapshot(8).get(0).jobs();
        assertTrue(jobs.get(0).work().payload().contains("\"count\":9"));
        assertEquals(ProjectQueue.State.WAITING, jobs.get(1).state());
        assertEquals(4, jobs.get(1).chest().report().items().get(item));
        assertEquals(later.payload(), jobs.get(2).work().payload());
        assertNull(jobs.get(3).chest());
        assertNull(queue.renew("stock", first.id(), early.lease(), cave, 9));
    }

    @Test void carrierCanRebankAgainstARebuiltChestAfterItsOldReceiptWasCleared() throws Exception {
        var journal = directory.resolve("rebank-after-owned-storage-loss.json");
        String chest = "block:3,64,0";
        String item = "minecraft:diamond";
        var wood = job("wood", GATHER, List.of());
        var craft = new ProjectQueue.Work("craft-chest", CRAFT, "", List.of("wood"), Set.of(), "wood");
        var station = new ProjectQueue.Work("setup-storage", BUILD,
                "{\"target\":{\"x\":3,\"y\":64,\"z\":0},\"block\":\"minecraft:chest\"}",
                List.of("craft-chest"), Set.of(chest), "craft-chest");
        var gather = new ProjectQueue.Work("dig", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":12}",
                List.of("setup-storage"), Set.of());
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"target\":{\"x\":3,\"y\":64,\"z\":0},\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":32}",
                List.of("dig"), Set.of(chest), "dig");
        var jobs = new ArrayList<ProjectQueue.Job>();
        for (var work : List.of(wood, craft, station)) {
            jobs.add(new ProjectQueue.Job(work, ProjectQueue.State.DONE, 1, "builder", null, 0, "Observed", null));
        }
        jobs.add(new ProjectQueue.Job(gather, ProjectQueue.State.WAITING, 0, null, null, 0,
                ProjectQueue.GATHER_TOOLS_NEEDED + "; reacquiring materials", null));
        jobs.add(new ProjectQueue.Job(deposit, ProjectQueue.State.WAITING, 1, null, null, 0,
                "Partial stock contribution settled", null));
        String cargoKey = "contribution:deposit:carrier";
        var position = new ProjectQueue.ResourceSite(3, 64, 1);
        var project = new ProjectQueue.Project("stock", WORLD, jobs, null, chest,
                java.util.Map.of("station-repair:setup-storage", 1, cargoKey, 8, cargoKey + ":stock", 20),
                java.util.Map.of(cargoKey, position));
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of("stock", project))));

        var queue = new ProjectQueue(journal);
        var carrier = queue.offerUrgentMiningDeposit(WORLD, "carrier", null, 1, position,
                (worker, material) -> 8, 8).job();
        assertEquals("deposit", carrier.work().id());
        var restored = new ProjectQueue(journal);
        assertNull(restored.offerUrgentMiningDeposit(WORLD, "another", null, 2, position,
                (worker, material) -> 8, 8));
        assertTrue(restored.finish("stock", "deposit", carrier.lease(), false, "Partial delivery",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 8)), null, true, 3));
        var observed = restored.snapshot(4).get(0).jobs();
        assertEquals(24, com.google.gson.JsonParser.parseString(observed.get(3).work().payload())
                .getAsJsonObject().get("count").getAsInt());
        assertEquals(8, observed.get(4).chest().report().items().get(item));
        assertNull(restored.offerUrgentMiningDeposit(WORLD, "carrier", null, 5, position,
                (worker, material) -> 8, 8));
    }

    @Test void retainsOnlyTheCarrierWithCargoUntilItsDepositCanRetry() throws Exception {
        var journal = directory.resolve("keep-carrier-through-deposit-cooldown.json");
        String chest = "block:3,64,0";
        String item = "minecraft:diamond";
        var dig = new ProjectQueue.Work("dig", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":32}",
                List.of(), Set.of());
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":32}",
                List.of("dig"), Set.of(chest), "dig");
        var resupply = new ProjectQueue.Work("route-tool", ProjectQueue.Kind.RESUPPLY, "{}", List.of(), Set.of());
        var jobs = List.of(new ProjectQueue.Job(dig, ProjectQueue.State.WAITING, 0, null, null, 0,
                        ProjectQueue.GATHER_TOOLS_NEEDED + "; reacquiring materials", null),
                new ProjectQueue.Job(deposit, ProjectQueue.State.WAITING, 1, null, null, 100,
                        "Partial deposit ended before chest observation", null),
                new ProjectQueue.Job(resupply, ProjectQueue.State.LEASED, 1, "carrier", java.util.UUID.randomUUID(),
                        99, null, null));
        var project = new ProjectQueue.Project("stock", WORLD, jobs, null, chest,
                java.util.Map.of("contribution:deposit:carrier", 8));
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of("stock", project))));
        var queue = new ProjectQueue(journal);
        java.util.function.ToIntBiFunction<String, String> held =
                (worker, material) -> worker.equals("carrier") && material.equals(item) ? 8 : 0;

        assertTrue(queue.awaitingContributionRetry(WORLD, "carrier", null, 75, held));
        assertFalse(queue.awaitingContributionRetry(WORLD, "helper", null, 75, (worker, material) -> 8));
        assertFalse(queue.awaitingContributionRetry(WORLD, "carrier", null, 75, (worker, material) -> 0));
        assertFalse(queue.awaitingContributionRetry(WORLD, "carrier", null, 100, held));
        var assignment = queue.offerUrgentMiningDeposit(WORLD, "carrier", null, 100,
                new ProjectQueue.ResourceSite(3, 64, 0), held, 8);
        assertNotNull(assignment);
        assertEquals("deposit", assignment.job().work().id());
    }

    @Test void deepMinerBanksACheckpointBeforeResumingItsOutstandingOrder() throws Exception {
        var journal = directory.resolve("mining-checkpoint.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String item = "minecraft:diamond";
        var gather = new ProjectQueue.Work("dig", GATHER,
                "{\"resource\":\"diamond\",\"variant\":\"both\",\"item\":\"minecraft:diamond\",\"count\":64}",
                List.of(), Set.of());
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:diamond\",\"stock\":64}",
                List.of("dig"), Set.of(chest), "dig");
        queue.submitSupplied("stock", WORLD, List.of(gather, deposit), chest);
        var assignment = queue.claim(WORLD, "miner", Set.of(GATHER), 1);
        assertEquals(4, assignment.cargoCheckpoint());
        var cave = new ProjectQueue.ResourceSite(12, -54, 23);
        assertTrue(queue.finish("stock", "dig", assignment.job().lease(), false, ProjectQueue.CARGO_CHECKPOINT,
                null, cave, 2));

        var restored = new ProjectQueue(journal);
        var paused = restored.snapshot(3).get(0).jobs().get(0);
        assertEquals(0, paused.attempts());
        assertNull(restored.claim(WORLD, "replacement", Set.of(GATHER), 3));
        long nearExpiry = paused.expiresAt() - 1;
        var held = (java.util.function.ToIntBiFunction<String, String>) (worker, material) ->
                worker.equals("miner") && material.equals(item) ? 4 : 0;
        assertTrue(restored.awaitingMiningCheckpoint(WORLD, "miner", null, nearExpiry, held));
        assertFalse(restored.awaitingMiningCheckpoint(WORLD, "replacement", null, nearExpiry, (worker, material) -> 4));
        assertFalse(restored.awaitingMiningCheckpoint(WORLD, "miner", "another-project", nearExpiry, held));
        assertFalse(restored.awaitingMiningCheckpoint(WORLD, "miner", null, nearExpiry, (worker, material) -> 0));
        assertFalse(restored.awaitingMiningCheckpoint(WORLD, "miner", null, paused.expiresAt(), held));
        var carrier = restored.offerCheckpointDeposit(WORLD, "miner", null, nearExpiry, cave, (name, material) -> 4).job();
        assertEquals("deposit", carrier.work().id());
        assertNull(restored.claim(WORLD, "replacement", Set.of(GATHER), nearExpiry + 2));
        assertTrue(restored.finish("stock", "deposit", carrier.lease(), false, "Partial delivery",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 4)), null, true, nearExpiry + 3));

        var resumed = restored.claim(WORLD, "miner", Set.of(GATHER), nearExpiry + 4);
        assertEquals(cave, resumed.job().resourceSite());
        assertEquals(1, resumed.job().attempts());
        assertEquals(60, com.google.gson.JsonParser.parseString(resumed.job().work().payload())
                .getAsJsonObject().get("count").getAsInt());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {64, -54})
    void returningMinerAcquiresBothChestLocksOnlyAfterArriving(Integer claimHeight) throws Exception {
        var path = directory.resolve("staged-mining-return-" + claimHeight + ".json");
        var queue = new ProjectQueue(path);
        var cave = new ProjectQueue.ResourceSite(12, -54, 23);
        var near = new ProjectQueue.ResourceSite(3, 64, 2);
        String chest = "block:3,64,0";
        String partner = "block:4,64,0";
        String item = "minecraft:diamond";
        var gather = job("dig", GATHER, List.of()).withPayload("""
                {"resource":"diamond","variant":"both","item":"minecraft:diamond","count":12}
                """);
        var deposit = new ProjectQueue.Work("bank", TRANSFER, """
                {"direction":"DEPOSIT","item":"minecraft:diamond","stock":12,"target":{"x":3,"y":64,"z":0}}
                """, List.of(gather.id()), Set.of(chest, partner), gather.id());
        var inspect = new ProjectQueue.Work("verify", TRANSFER, """
                {"direction":"VERIFY_STOCK","item":"minecraft:dirt","stock":1}
                """, List.of(), Set.of(partner));
        queue.submitSupplied("stock", WORLD, List.of(gather, deposit), chest);
        queue.submit("other", WORLD, List.of(inspect));
        var mining = queue.claim(WORLD, "miner", Set.of(GATHER), 1).job();
        assertTrue(queue.finish("stock", "dig", mining.lease(), false, ProjectQueue.CARGO_CHECKPOINT, null, cave, 2));
        int revision = ProjectQueue.STAGED_STOCK_RETURN_REVISION;
        var claimPosition = claimHeight == null ? null : new ProjectQueue.ResourceSite(3, claimHeight, 2);
        var inbound = queue.offerUrgentMiningDeposit(WORLD, "miner", null, 3, claimPosition,
                (name, material) -> 1, revision).job();
        assertTrue(ProjectQueue.travelingContribution(inbound));
        var check = queue.claim(WORLD, "keeper", Set.of(TRANSFER), "other", 4).job();

        var restored = new ProjectQueue(path);
        assertNull(restored.claim(WORLD, "miner", Set.of(TRANSFER), "stock", 5));
        var resumed = restored.offerUrgentMiningDeposit(WORLD, "miner", null, 5, cave,
                (name, material) -> 1, revision).job();
        assertEquals(inbound.lease(), resumed.lease());
        assertTrue(ProjectQueue.travelingContribution(restored.renew("stock", "bank", inbound.lease(),
                null, 6, false, false, name -> near)));
        var receipt = new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 1));
        assertThrows(IllegalArgumentException.class, () -> restored.finish("stock", "bank", inbound.lease(),
                true, "Unreserved deposit", receipt, 7));
        assertTrue(restored.finish("other", "verify", check.lease(), true, "Verified stock",
                new ProjectQueue.ChestReport(partner, java.util.Map.of("minecraft:dirt", 1)), 8));
        assertTrue(ProjectQueue.travelingContribution(restored.renew("stock", "bank", inbound.lease(), 9)));
        assertTrue(ProjectQueue.travelingContribution(restored.renew("stock", "bank", inbound.lease(),
                null, 9, false, false, name -> cave)));
        var authorized = restored.renew("stock", "bank", inbound.lease(), null, 10, false, false, name -> near);
        assertFalse(ProjectQueue.travelingContribution(authorized));
        assertEquals(inbound.lease(), authorized.lease());
        assertTrue(restored.finish("stock", "bank", inbound.lease(), false, "Partial delivery",
                receipt, null, true, 11));
        assertEquals(receipt, restored.snapshot(12).get(0).jobs().get(1).chest().report());
    }

    @Test void surfaceLogsBankMixedCargoAndResumeOnlyTheMissingCount() throws Exception {
        var queue = new ProjectQueue(directory.resolve("wood-checkpoint.json"));
        String chest = "block:3,64,0";
        var gather = new ProjectQueue.Work("cut", GATHER,
                "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\",\"count\":20}",
                List.of(), Set.of());
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"any_logs\",\"stock\":20}", List.of(gather.id()), Set.of(chest), gather.id());
        queue.submitSupplied("stock", WORLD, List.of(gather, deposit), chest);
        var assignment = queue.claim(WORLD, "worker", Set.of(GATHER), 1);
        assertEquals(16, assignment.cargoCheckpoint());
        var woods = new ProjectQueue.ResourceSite(11, 123, 8);
        assertTrue(queue.finish("stock", gather.id(), assignment.job().lease(), false, ProjectQueue.CARGO_CHECKPOINT, null, woods, 2));
        var carrier = queue.offerCheckpointDeposit(WORLD, "worker", null, 3, woods, (name, item) -> 16).job();
        var receipt = new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:birch_log", 6, "minecraft:oak_log", 10));
        assertTrue(queue.finish("stock", deposit.id(), carrier.lease(), false, "Partial delivery", receipt, null, true, 4));

        var resumed = queue.claim(WORLD, "worker", Set.of(GATHER), 5);
        assertEquals(woods, resumed.job().resourceSite());
        assertEquals(0, resumed.cargoCheckpoint());
        assertEquals(4, com.google.gson.JsonParser.parseString(resumed.job().work().payload()).getAsJsonObject().get("count").getAsInt());
    }

    @Test void aResumedCheckpointDistinguishesBankedCargoFromAnUnchangedAttempt() throws Exception {
        for (boolean banked : new boolean[]{false, true}) {
            var queue = new ProjectQueue(directory.resolve("checkpoint-reconnect-" + banked + ".json"));
            String chest = "block:3,64,0";
            var gather = new ProjectQueue.Work("cut", GATHER,
                    "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\",\"count\":64}",
                    List.of(), Set.of());
            var deposit = new ProjectQueue.Work("bank", TRANSFER,
                    "{\"direction\":\"DEPOSIT\",\"item\":\"any_logs\",\"stock\":64}",
                    List.of(gather.id()), Set.of(chest), gather.id());
            queue.submitSupplied("wood", WORLD, List.of(gather, deposit), chest);
            var work = queue.claim(WORLD, "worker", Set.of(GATHER), 1);
            assertEquals(0, work.previousCheckpointCargo());
            var site = new ProjectQueue.ResourceSite(11, 123, 8);
            assertTrue(queue.finish("wood", gather.id(), work.job().lease(), false, ProjectQueue.CARGO_CHECKPOINT, null, site, 2));
            var carrier = queue.offerCheckpointDeposit(WORLD, "worker", null, 3, site, (name, item) -> 16).job();
            var stock = banked ? java.util.Map.of("minecraft:oak_log", 16) : java.util.Map.<String,Integer>of();
            assertTrue(queue.finish("wood", deposit.id(), carrier.lease(), false, "Partial delivery",
                    new ProjectQueue.ChestReport(chest, stock), null, true, 4));

            var resumed = queue.claim(WORLD, "worker", Set.of(GATHER), 35_000);
            assertEquals(16, resumed.cargoCheckpoint());
            assertEquals(banked ? 0 : 16, resumed.previousCheckpointCargo());
        }
    }

    @Test void deepMinersPreferUnoccupiedInterruptedSitesOverShallowCheckpoints() throws Exception {
        var journal = directory.resolve("interrupted-deep-site.json");
        var ore = """
                {"resource":"diamond","variant":"both","item":"minecraft:diamond","count":4}
                """;
        var tool = new ProjectQueue.Work("tool", CRAFT, "", List.of(), Set.of());
        var jobs = new ArrayList<ProjectQueue.Job>();
        jobs.add(new ProjectQueue.Job(tool, ProjectQueue.State.WAITING, 0, null, null, 0, null, null));
        var sites = List.of(new ProjectQueue.ResourceSite(48, -54, 0),
                new ProjectQueue.ResourceSite(15, -54, 0), new ProjectQueue.ResourceSite(6, -30, 0),
                new ProjectQueue.ResourceSite(8, -50, 0));
        for (int i = 0; i < sites.size(); i++) {
            var work = new ProjectQueue.Work("paused-" + i, GATHER,
                    i == 3 ? ore.replace("both", "normal") : ore, List.of("tool"), Set.of(), "tool");
            jobs.add(new ProjectQueue.Job(work, ProjectQueue.State.WAITING, 0, null, null, 0,
                    ProjectQueue.GATHER_TOOLS_NEEDED + "; reacquiring materials", null, sites.get(i)));
        }
        var shallow = new ProjectQueue.ResourceSite(3, 4, 0);
        for (String id : List.of("first", "second", "third")) {
            var work = new ProjectQueue.Work(id, GATHER, ore, List.of(), Set.of());
            jobs.add(new ProjectQueue.Job(work, ProjectQueue.State.WAITING, 0, null, null, 0, null, null,
                    "second".equals(id) ? null : shallow));
        }
        var project = new ProjectQueue.Project("stock", WORLD, jobs, null, null, java.util.Map.of());
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of("stock", project))));
        var queue = new ProjectQueue(journal);
        var position = new ProjectQueue.ResourceSite(0, 64, 0);
        for (int i = 0; i < 3; i++) {
            var assignment = queue.claim(WORLD, "miner-" + i, Set.of(GATHER), "stock", i + 1, position);
            assertEquals(List.of("first", "second", "third").get(i), assignment.job().work().id());
            assertEquals(i == 2 ? shallow : sites.get(1 - i), assignment.job().resourceSite());
        }
    }

    @Test void stalledGatheringUsesNearestCompatibleSiteOutsideFailedAreaAfterRestart() throws Exception {
        var journal = directory.resolve("stalled-gather-alternate.json");
        var queue = new ProjectQueue(journal);
        String logs = "{\"resource\":\"block:minecraft:oak_log\",\"variant\":\"both\",\"item\":\"minecraft:oak_log\",\"count\":2}";
        var sites = List.of(new ProjectQueue.ResourceSite(48, 64, 0),
                new ProjectQueue.ResourceSite(30, 77, 0), new ProjectQueue.ResourceSite(55, 64, 0),
                new ProjectQueue.ResourceSite(28, 64, 0), new ProjectQueue.ResourceSite(20, 64, 0),
                new ProjectQueue.ResourceSite(30, 64, 0));
        var work = new java.util.ArrayList<ProjectQueue.Work>();
        for (int i = 0; i < sites.size(); i++) {
            var payload = i == sites.size() - 1 ? logs.replace("oak_log", "birch_log") : logs;
            work.add(new ProjectQueue.Work("scout-" + i, GATHER, payload, List.of(), Set.of()));
        }
        work.add(new ProjectQueue.Work("collect", GATHER, logs,
                work.stream().map(ProjectQueue.Work::id).toList(), Set.of()));
        queue.submit("house", WORLD, work);
        for (int i = 0; i < sites.size(); i++) {
            var prior = queue.claim(WORLD, "Scout", Set.of(GATHER), 2L * i).job();
            assertTrue(queue.finish("house", prior.work().id(), prior.lease(), true, "Observed stock",
                    null, sites.get(i), 2L * i + 1));
        }
        var first = queue.claim(WORLD, "First", Set.of(GATHER), 20).job();
        assertEquals(sites.get(4), first.resourceSite());
        assertTrue(queue.finish("house", "collect", first.lease(), false, ProjectQueue.GATHER_STALLED, 21));

        var restored = new ProjectQueue(journal);
        var replacement = restored.claim(WORLD, "Second", Set.of(GATHER), "house", 22,
                new ProjectQueue.ResourceSite(30, 64, 0)).job();
        assertEquals(sites.get(0), replacement.resourceSite());
    }

    @Test void repairingAnotherBlockRecalculatesAPreviouslyShrunkPickup() throws Exception {
        var journal = directory.resolve("expanded-repair-cargo.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String material = "minecraft:oak_log";
        queue.submitSupplied("house", WORLD, List.of(job("gather", GATHER, List.of()),
                new ProjectQueue.Work("pickup", TRANSFER,
                        "{\"direction\":\"TAKE\",\"item\":\"" + material + "\",\"stock\":2}",
                        List.of("gather"), Set.of(chest), null, null, material),
                new ProjectQueue.Work("place-0", BUILD, "", List.of("pickup"), Set.of(), "pickup"),
                new ProjectQueue.Work("place-1", BUILD, "", List.of("pickup"), Set.of(), "pickup")), chest);
        long now = 1;
        var gather = queue.claim(WORLD, "builder", Set.of(GATHER), now++).job();
        assertTrue(queue.finish("house", "gather", gather.lease(), true, "Gathered", now++));
        var observed = new ProjectQueue.ChestReport(chest, java.util.Map.of());
        var pickup = queue.claim(WORLD, "builder", Set.of(TRANSFER), now++).job();
        assertTrue(queue.finish("house", "pickup", pickup.lease(), true, "Collected", observed, now++));
        var lower = queue.claim(WORLD, "builder", Set.of(BUILD), now++).job();
        assertTrue(queue.finish("house", "place-0", lower.lease(), true, "Placed", now++));
        for (int attempt = 0; attempt < 3; attempt++) {
            var upper = queue.claim(WORLD, "builder", Set.of(BUILD), now++).job();
            assertTrue(queue.finish("house", "place-1", upper.lease(), false, "Missing material " + material, now++));
            if (attempt == 2) break;
            var replacement = queue.claim(WORLD, "builder", Set.of(TRANSFER), now++).job();
            assertEquals(1, com.google.gson.JsonParser.parseString(replacement.work().payload()).getAsJsonObject()
                    .get("stock").getAsInt());
            assertTrue(queue.finish("house", "pickup", replacement.lease(), true, "Collected", observed, now++));
        }
        assertNull(queue.claim(WORLD, "builder", Set.of(TRANSFER), now++));
        assertTrue(queue.repairMissingPlacement(WORLD, "house", "place-0"));
        var restored = new ProjectQueue(journal);
        var refill = restored.claim(WORLD, "builder", Set.of(TRANSFER), now).job();
        assertEquals(2, com.google.gson.JsonParser.parseString(refill.work().payload()).getAsJsonObject()
                .get("stock").getAsInt());
    }

    @Test void lostBuildInventoryRequestsOnlyUnplacedMaterialAfterRestart() throws Exception {
        var journal = directory.resolve("lost-construction-cargo.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String material = "minecraft:red_stained_glass";
        queue.submitSupplied("house", WORLD, List.of(job("prepare", GATHER, List.of()),
                new ProjectQueue.Work("pickup", TRANSFER,
                        "{\"direction\":\"TAKE\",\"item\":\"" + material + "\",\"stock\":3}",
                        List.of("prepare"), Set.of(chest), null, null, material),
                new ProjectQueue.Work("placed", BUILD, "", List.of("pickup"), Set.of(), "pickup"),
                new ProjectQueue.Work("missing", BUILD, "", List.of("pickup"), Set.of(), "pickup"),
                new ProjectQueue.Work("pending", BUILD, "", List.of("pickup"), Set.of(), "pickup")), chest);
        var gather = queue.claim(WORLD, "builder", Set.of(GATHER), 1).job();
        assertTrue(queue.finish("house", "prepare", gather.lease(), true, "Gathered", 2));
        var pickup = queue.claim(WORLD, "builder", Set.of(TRANSFER), 3).job();
        var emptyChest = new ProjectQueue.ChestReport(chest, java.util.Map.of());
        assertTrue(queue.finish("house", "pickup", pickup.lease(), true, "Collected", emptyChest, 4));
        var placed = queue.claim(WORLD, "builder", Set.of(BUILD), 5).job();
        assertTrue(queue.finish("house", "placed", placed.lease(), true, "Placed", 6));
        var missing = queue.claim(WORLD, "builder", Set.of(BUILD), 7).job();
        assertTrue(queue.finish("house", "missing", missing.lease(), false, "Missing material " + material, 8));

        var restored = new ProjectQueue(journal);
        assertNull(restored.claim(WORLD, "builder", Set.of(BUILD), 9));
        var refill = restored.claim(WORLD, "replacement", Set.of(TRANSFER), 10).job();
        var payload = com.google.gson.JsonParser.parseString(refill.work().payload()).getAsJsonObject();
        assertEquals(2, payload.get("stock").getAsInt());
        assertEquals(ProjectQueue.State.DONE, restored.snapshot(11).get(0).jobs().get(2).state());
        assertTrue(restored.finish("house", "pickup", refill.lease(), true, "Collected replacement", emptyChest, 12));
        assertEquals("missing", restored.claim(WORLD, "replacement", Set.of(BUILD), 13).job().work().id());
    }

    @Test void bankingAnEarlierBatchRefreshesAnOutdatedCompletedGatheringQuota() throws Exception {
        var journal = directory.resolve("refresh-stock-gather.json");
        String chest = "block:3,64,0";
        var firstBank = new ProjectQueue.Work("first-bank", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"any_logs\",\"stock\":64}", List.of(), Set.of(chest));
        var gather = new ProjectQueue.Work("gather", GATHER,
                "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\",\"count\":20}", List.of(), Set.of());
        var lastBank = new ProjectQueue.Work("last-bank", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"any_logs\",\"stock\":128}",
                List.of(firstBank.id(), gather.id()), Set.of(chest), gather.id());
        var queue = new ProjectQueue(journal);
        queue.submitSupplied("stock", WORLD, List.of(firstBank, gather, lastBank), chest);
        var collected = queue.claim(WORLD, "old", Set.of(GATHER), 1).job();
        assertTrue(queue.finish("stock", gather.id(), collected.lease(), true, "Gathered", 2));
        assertFalse(queue.refreshUnderfilledStockGather(WORLD, (worker, item) -> 20));
        var first = queue.claim(WORLD, "carrier", Set.of(TRANSFER), 3).job();
        var receipt = new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:oak_log", 50, "minecraft:birch_log", 14));
        assertTrue(queue.finish("stock", firstBank.id(), first.lease(), true, "Banked", receipt, 4));

        assertFalse(queue.refreshUnderfilledStockGather(WORLD, (worker, item) -> -1));
        assertFalse(queue.refreshUnderfilledStockGather(WORLD, (worker, item) -> 64));
        assertTrue(queue.refreshUnderfilledStockGather(WORLD, (worker, item) -> 20));
        var restored = new ProjectQueue(journal);
        assertFalse(restored.refreshUnderfilledStockGather(WORLD, (worker, item) -> 20));
        var jobs = restored.snapshot(5).get(0).jobs();
        assertEquals(first.lease(), jobs.get(0).lease());
        assertEquals(receipt, jobs.get(2).chest().report());
        var refill = restored.claim(WORLD, "new", Set.of(GATHER), 6).job();
        assertEquals(gather.id(), refill.work().id());
        assertEquals(64, com.google.gson.JsonParser.parseString(refill.work().payload()).getAsJsonObject().get("count").getAsInt());
    }

    @Test void observedReplacementCargoCanFinishAnAlreadyGatheredStockBatch() throws Exception {
        var journal = directory.resolve("replacement-deposit.json");
        String project = "stock";
        String chest = "block:3,64,0";
        String stone = "minecraft:cobblestone";
        var firstBank = new ProjectQueue.Work("first-bank", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:cobblestone\",\"stock\":128}", List.of(), Set.of(chest));
        var gather = new ProjectQueue.Work("gather", GATHER,
                "{\"resource\":\"stone\",\"variant\":\"both\",\"item\":\"minecraft:cobblestone\",\"count\":64}", List.of(), Set.of());
        var lastBank = new ProjectQueue.Work("last-bank", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:cobblestone\",\"stock\":192}",
                List.of(firstBank.id(), gather.id()), Set.of(chest), gather.id());
        var queue = new ProjectQueue(journal);
        queue.submitSupplied(project, WORLD, List.of(firstBank, gather, lastBank), chest);
        var bank = queue.claim(WORLD, "first", Set.of(TRANSFER), 1).job();
        assertTrue(queue.finish(project, firstBank.id(), bank.lease(), true, "Banked",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(stone, 128)), 2));
        var mined = queue.claim(WORLD, "miner", Set.of(GATHER), 3).job();
        assertTrue(queue.finish(project, gather.id(), mined.lease(), true, "Collected", 4));

        var restored = new ProjectQueue(journal);
        assertNull(restored.offerInterruptedDeposit(WORLD, "helper", project, 5,
                (bot, item) -> bot.equals("miner") ? -1 : 64));
        assertNull(restored.offerInterruptedDeposit(WORLD, "helper", project, 6,
                (bot, item) -> 64));
        assertNull(restored.offerInterruptedDeposit(WORLD, "helper", project, 7,
                (bot, item) -> bot.equals("miner") ? 0 : 63));
        var transfer = restored.offerInterruptedDeposit(WORLD, "helper", project, 8,
                (bot, item) -> bot.equals("miner") ? 0 : 64).job();
        assertEquals(lastBank.id(), transfer.work().id());
        assertNull(restored.claim(WORLD, "miner", Set.of(TRANSFER), 9));
        assertTrue(restored.finish(project, lastBank.id(), transfer.lease(), true, "Verified replacement stock",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(stone, 192)), 10));
        assertEquals(192, restored.chestStock(WORLD).get(chest).report().items().get(stone));
        assertEquals(mined.lease(), restored.snapshot(11).get(0).jobs().get(1).lease());
    }

    @Test void nearbyCarrierCanResumeACompletelyTimedOutDepositWithoutRewritingItsGather() throws Exception {
        var journal = directory.resolve("timed-out-deposit.json");
        String project = "stock";
        String chest = "block:3,64,0";
        String dirt = "minecraft:dirt";
        var first = new ProjectQueue.Work("first", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:dirt\",\"stock\":64}", List.of(), Set.of(chest));
        var gather = new ProjectQueue.Work("gather", GATHER,
                "{\"resource\":\"dirt\",\"variant\":\"both\",\"item\":\"minecraft:dirt\",\"count\":32}", List.of(), Set.of());
        var deposit = new ProjectQueue.Work("deposit", TRANSFER, """
                {"target":{"x":3,"y":64,"z":0},"direction":"DEPOSIT","item":"minecraft:dirt","stock":96}
                """, List.of(first.id(), gather.id()), Set.of(chest), gather.id());
        var verify = new ProjectQueue.Work("verify", TRANSFER,
                "{\"direction\":\"VERIFY_STOCK\",\"item\":\"minecraft:dirt\",\"stock\":96}", List.of(deposit.id()), Set.of(chest));
        var queue = new ProjectQueue(journal);
        queue.submitSupplied(project, WORLD, List.of(first, gather, deposit, verify), chest);
        var firstLease = queue.claim(WORLD, "miner", Set.of(TRANSFER), 1).job();
        assertTrue(queue.finish(project, first.id(), firstLease.lease(), true, "Banked",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(dirt, 64)), 2));
        var gathered = queue.claim(WORLD, "miner", Set.of(GATHER), 3).job();
        assertTrue(queue.finish(project, gather.id(), gathered.lease(), true, "Gathered", 4));
        for (int now = 5; now < 11; now += 2) {
            var lease = queue.claim(WORLD, "miner", Set.of(TRANSFER), now).job();
            assertTrue(queue.finish(project, deposit.id(), lease.lease(), false, ProjectQueue.TARGET_TIMEOUT, now + 1));
        }

        assertNull(queue.claim(WORLD, "helper", Set.of(TRANSFER), 11));
        var far = new ProjectQueue.StockCarrier("helper", new ProjectQueue.ResourceSite(3, 44, 0), java.util.Map.of(dirt, 64));
        assertFalse(queue.rescueFailedDeposit(WORLD, far, 12));
        var local = new ProjectQueue.StockCarrier("helper", new ProjectQueue.ResourceSite(3, 63, 3), java.util.Map.of(dirt, 40));
        assertTrue(queue.rescueFailedDeposit(WORLD, local, 13));
        assertFalse(queue.rescueFailedDeposit(WORLD, local, 13));
        var recovered = new ProjectQueue(journal);
        var rescue = recovered.claim(WORLD, "helper", Set.of(TRANSFER), 14).job();
        assertEquals(deposit, rescue.work());
        assertEquals(64, recovered.chestStock(WORLD).get(chest).report().items().get(dirt));
        assertNull(recovered.claim(WORLD, "miner", Set.of(TRANSFER), 15));
        assertTrue(recovered.finish(project, deposit.id(), rescue.lease(), true, "Verified",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(dirt, 96)), 16));
        assertEquals(verify, recovered.claim(WORLD, "helper", Set.of(TRANSFER), 17).job().work());
        assertEquals(gathered.lease(), recovered.snapshot(18).get(0).jobs().get(1).lease());
    }

    @Test void laterCargoFillsStalledManifestBatchAndReopensItsOwnGather() throws Exception {
        var journal = directory.resolve("bank-ready-cargo.json");
        String projectId = "manifest-stock";
        String chest = "block:3,64,0";
        String item = "minecraft:dirt";
        String gather = "{\"resource\":\"dirt\",\"variant\":\"both\",\"item\":\"minecraft:dirt\",\"count\":13}";
        String deposit = "{\"target\":{\"x\":3,\"y\":64,\"z\":0},\"direction\":\"DEPOSIT\",\"item\":\"minecraft:dirt\",\"stock\":%d}";
        var firstGather = new ProjectQueue.Work("first-gather", GATHER, gather, List.of(), Set.of());
        var firstBank = new ProjectQueue.Work("first-bank", TRANSFER, deposit.formatted(13),
                List.of(firstGather.id()), Set.of(chest), firstGather.id());
        var laterGather = new ProjectQueue.Work("later-gather", GATHER, gather, List.of(), Set.of());
        var laterBank = new ProjectQueue.Work("later-bank", TRANSFER, deposit.formatted(26),
                List.of(laterGather.id(), firstBank.id()), Set.of(chest), laterGather.id());
        var project = new ProjectQueue.Project(projectId, WORLD, List.of(
                new ProjectQueue.Job(firstGather, ProjectQueue.State.FAILED, 3, "stalled", null, 0, ProjectQueue.GATHER_STALLED, null),
                new ProjectQueue.Job(firstBank, ProjectQueue.State.WAITING, 0, null, null, 0, null, null),
                new ProjectQueue.Job(laterGather, ProjectQueue.State.DONE, 1, "carrier", null, 0, "Gathered", null),
                new ProjectQueue.Job(laterBank, ProjectQueue.State.WAITING, 0, null, null, 0, null, null)), null, chest, java.util.Map.of());
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1, "projects", java.util.Map.of(projectId, project))));

        var queue = new ProjectQueue(journal);
        assertNull(queue.claim(WORLD, "carrier", Set.of(TRANSFER), projectId, 1, null,
                block -> true, (worker, key) -> 12, java.util.Map.of()));
        var rescued = queue.claim(WORLD, "carrier", Set.of(TRANSFER), projectId, 2, null,
                block -> true, (worker, key) -> 13, java.util.Map.of()).job();
        assertEquals(firstBank, rescued.work());
        queue = new ProjectQueue(journal);
        assertEquals(rescued.lease(), queue.claim(WORLD, "carrier", Set.of(TRANSFER), 3).job().lease());
        assertTrue(queue.finish(projectId, firstBank.id(), rescued.lease(), true, "Verified",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 13)), 4));
        assertNull(queue.claim(WORLD, "carrier", Set.of(TRANSFER), 5));
        var recollect = queue.claim(WORLD, "helper", Set.of(GATHER), 6).job();
        assertEquals(laterGather, recollect.work());
        assertTrue(queue.finish(projectId, laterGather.id(), recollect.lease(), true, "Gathered", 7));
        assertEquals(laterBank, queue.claim(WORLD, "helper", Set.of(TRANSFER), 8).job().work());
    }

    @Test void shortDepositReservesAStockedCarrierBeforeRewinding() throws Exception {
        var journal = directory.resolve("short-deposit-carrier.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String item = "minecraft:cobblestone";
        var collect = new ProjectQueue.Work("collect", GATHER,
                "{\"resource\":\"stone\",\"variant\":\"both\",\"item\":\"minecraft:cobblestone\",\"count\":64}", List.of(), Set.of());
        var deliver = new ProjectQueue.Work("deliver", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:cobblestone\",\"stock\":192}",
                List.of(collect.id()), Set.of(chest), collect.id());
        queue.submitSupplied("stock", WORLD, List.of(collect, deliver), chest);
        var gathered = queue.claim(WORLD, "old", Set.of(GATHER), 1).job();
        assertTrue(queue.finish("stock", "collect", gathered.lease(), true, "Collected", 2));
        var deposit = queue.claim(WORLD, "old", Set.of(TRANSFER), 3).job();
        assertNull(queue.claim(WORLD, "helper", Set.of(TRANSFER), 4));
        var observation = new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 128));
        var carrier = new ProjectQueue.StockCarrier("helper", new ProjectQueue.ResourceSite(2, 64, 0), java.util.Map.of(item, 64));
        assertTrue(queue.finish("stock", deliver.id(), deposit.lease(), false, "Short deposit",
                observation, null, true, List.of(carrier), 5));

        var reopened = new ProjectQueue(journal);
        var jobs = reopened.snapshot(6).get(0).jobs();
        assertEquals(gathered.lease(), jobs.get(0).lease());
        assertEquals(ProjectQueue.State.DONE, jobs.get(0).state());
        assertEquals("helper", jobs.get(1).worker());
        assertNotEquals(deposit.lease(), jobs.get(1).lease());
        assertEquals(observation, jobs.get(1).chest().report());

        var failedHandoff = reopened.claim(WORLD, "helper", Set.of(TRANSFER), 7).job();
        assertTrue(reopened.finish("stock", deliver.id(), failedHandoff.lease(), false, "Chest still short",
                observation, null, true, List.of(carrier), 8));
        var again = reopened.claim(WORLD, "old", Set.of(TRANSFER), 9).job();
        assertTrue(reopened.finish("stock", deliver.id(), again.lease(), false, "Same shortfall",
                observation, null, true, List.of(carrier), 10));
        assertEquals(ProjectQueue.State.WAITING, reopened.snapshot(11).get(0).jobs().get(0).state());
    }

    @Test void missingCargoReopensOnlyTheAffectedGatheringChain() throws Exception {
        var journal = directory.resolve("missing-cargo.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        queue.submitSupplied("stock", WORLD, List.of(job("setup", EXCAVATE, List.of()),
                new ProjectQueue.Work("logs", GATHER,
                        "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\",\"count\":4}",
                        List.of("setup"), Set.of()),
                new ProjectQueue.Work("deliver", TRANSFER,
                        "{\"direction\":\"DEPOSIT\",\"item\":\"any_logs\",\"stock\":4}",
                        List.of("logs"), Set.of(chest), "logs")), chest);
        var setup = queue.claim(WORLD, "miner", Set.of(EXCAVATE), 1).job();
        assertTrue(queue.finish("stock", setup.work().id(), setup.lease(), true, "Ready", 2));
        var gather = queue.claim(WORLD, "miner", Set.of(GATHER), 3).job();
        var site = new ProjectQueue.ResourceSite(10, 65, 8);
        assertTrue(queue.finish("stock", gather.work().id(), gather.lease(), true, "Collected logs", null, site, 4));
        var delivery = queue.claim(WORLD, "miner", Set.of(TRANSFER), 5).job();
        var observed = new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:birch_log", 2));
        assertTrue(queue.finish("stock", delivery.work().id(), delivery.lease(), false, "Delivery is short",
                observed, null, true, 6));
        var restored = new ProjectQueue(journal);
        var jobs = restored.snapshot(7).get(0).jobs();
        assertEquals(ProjectQueue.State.DONE, jobs.get(0).state());
        assertEquals(ProjectQueue.State.WAITING, jobs.get(1).state());
        assertEquals(site, jobs.get(1).resourceSite());
        assertNull(restored.claim(WORLD, "next", Set.of(TRANSFER), 8));
        var refill = restored.claim(WORLD, "next", Set.of(GATHER), 9).job();
        assertEquals("logs", refill.work().id());
        assertEquals(2, com.google.gson.JsonParser.parseString(refill.work().payload()).getAsJsonObject().get("count").getAsInt());

        // Replaying a claim preserves the lease and its reduced target.
        assertEquals(refill, restored.claim(WORLD, "next", Set.of(GATHER), 9).job());
        assertTrue(restored.finish("stock", "logs", refill.lease(), true, "Collected replacement logs", 10));
        var nextDelivery = restored.claim(WORLD, "next", Set.of(TRANSFER), 11).job();
        var largerShortfall = new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:birch_log", 1));
        assertTrue(restored.finish("stock", "deliver", nextDelivery.lease(), false, "Delivery is short",
                largerShortfall, null, true, 12));
        var secondRefill = restored.claim(WORLD, "next", Set.of(GATHER), 13).job();
        assertEquals(3, com.google.gson.JsonParser.parseString(secondRefill.work().payload()).getAsJsonObject().get("count").getAsInt());
        var planned = restored.snapshot(13).get(0).jobs().get(1).work();
        assertEquals(4, com.google.gson.JsonParser.parseString(planned.payload()).getAsJsonObject().get("count").getAsInt());
    }

    @Test void restoresOwnedStationWithoutReplayingUnrelatedCargo() throws Exception {
        var journal = directory.resolve("missing-station.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        queue.submitSupplied("stock", WORLD, List.of(job("wood", GATHER, List.of()),
                new ProjectQueue.Work("craft-chest", CRAFT, "", List.of("wood"), Set.of(), "wood"),
                new ProjectQueue.Work("setup-storage", BUILD,
                        "{\"target\":{\"x\":3,\"y\":64,\"z\":0},\"block\":\"minecraft:chest\"}",
                        List.of("craft-chest"), Set.of(chest), "craft-chest"),
                job("cargo", GATHER, List.of("setup-storage")),
                new ProjectQueue.Work("deliver", TRANSFER, "", List.of("cargo"), Set.of(chest), "cargo")), chest);
        long now = 1;
        for (var kind : List.of(GATHER, CRAFT, BUILD, GATHER)) {
            var claimed = queue.claim(WORLD, kind == GATHER && now == 7 ? "hauler" : "builder", Set.of(kind), now++).job();
            assertTrue(queue.finish("stock", claimed.work().id(), claimed.lease(), true, "Done", now++));
        }
        assertEquals(1, queue.ownedStations(WORLD).size());
        assertTrue(queue.restoreOwnedStation(WORLD, "stock", "setup-storage"));
        var restored = new ProjectQueue(journal);
        assertEquals(ProjectQueue.State.DONE, restored.snapshot(now).get(0).jobs().get(3).state());
        assertNull(restored.claim(WORLD, "hauler", Set.of(TRANSFER), now++));
        for (var kind : List.of(GATHER, CRAFT, BUILD)) {
            var claimed = restored.claim(WORLD, "repairer", Set.of(kind), now++).job();
            assertTrue(restored.finish("stock", claimed.work().id(), claimed.lease(), true, "Restored", now++));
        }
        assertEquals("deliver", restored.claim(WORLD, "hauler", Set.of(TRANSFER), now).job().work().id());
    }

    @Test void repairsALostChestFromAnExhaustedJournalAndForgetsItsOldStock() throws Exception {
        var journal = directory.resolve("lost-chest-attempts.json");
        String chest = "block:3,64,0";
        var wood = job("wood", GATHER, List.of());
        var craft = new ProjectQueue.Work("craft-chest", CRAFT, "", List.of("wood"), Set.of(), "wood");
        var station = new ProjectQueue.Work("setup-storage", BUILD,
                "{\"target\":{\"x\":3,\"y\":64,\"z\":0},\"block\":\"minecraft:chest\"}",
                List.of("craft-chest"), Set.of(chest), "craft-chest");
        var stock = new ProjectQueue.ChestObservation(
                new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:diamond", 15)), 1);
        var jobs = List.of(
                new ProjectQueue.Job(wood, ProjectQueue.State.DONE, 1, "builder", null, 0, "Collected", null),
                new ProjectQueue.Job(craft, ProjectQueue.State.DONE, 1, "builder", null, 0, "Crafted", null),
                new ProjectQueue.Job(station, ProjectQueue.State.FAILED, 1, null, null, 0,
                        "Owned station is missing after 2 supply recoveries", null),
                new ProjectQueue.Job(job("old-stock", TRANSFER, List.of("setup-storage"), chest),
                        ProjectQueue.State.DONE, 1, "hauler", null, 0, "Observed stock", stock),
                new ProjectQueue.Job(job("next", TRANSFER, List.of("setup-storage"), chest),
                        ProjectQueue.State.WAITING, 0, null, null, 0, null, null));
        var project = new ProjectQueue.Project("stock", WORLD, jobs, null, chest, java.util.Map.of("wood", 2));
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of("stock", project))));
        long now = 2;
        for (int loss = 0; loss < 3; loss++) {
            var queue = new ProjectQueue(journal);
            assertEquals("setup-storage", queue.ownedStations(WORLD).get(0).job());
            assertTrue(queue.restoreOwnedStation(WORLD, "stock", "setup-storage"));
            assertTrue(queue.chestStock(WORLD).isEmpty());
            assertEquals(ProjectQueue.State.DONE, queue.snapshot(now).get(0).jobs().get(3).state());
            assertFalse(queue.restoreOwnedStation(WORLD, "stock", "setup-storage"));
            for (var kind : List.of(GATHER, CRAFT, BUILD)) {
                var assigned = queue.claim(WORLD, "repairer", Set.of(kind), now++).job();
                assertTrue(queue.finish("stock", assigned.work().id(), assigned.lease(), true, "Restored", now++));
            }
        }
    }

    @Test void stalledPlacementCanUseANearbyBuildersExistingMaterials() throws Exception {
        checkConstructionHandoff(false, false, false);
    }

    @Test void stalledPlacementCanUseReplacementMaterialsFromTheCommunityChest() throws Exception {
        checkConstructionHandoff(true, false, false);
    }

    @Test void readyPlacementHandsOffCargoWithoutInterruptingADistantMiner() throws Exception {
        checkConstructionHandoff(true, true, false);
    }

    @Test void stalledPlacementCanHandOffFromACloseStrandedWorkerToADifferentApproach() throws Exception {
        checkConstructionHandoff(true, false, true);
    }

    @Test void stalledPlacementGivesAnotherBuilderTheBatchBeforeTheOriginalRetries() throws Exception {
        var journal = directory.resolve("early-construction-handoff.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String material = "minecraft:oak_log";
        String target = "{\"target\":{\"x\":8,\"y\":64,\"z\":8}}";
        var pickup = new ProjectQueue.Work("pickup", TRANSFER,
                "{\"direction\":\"TAKE\",\"item\":\"minecraft:oak_log\",\"stock\":2}",
                List.of("ready"), Set.of(chest), null, null, material);
        queue.submitSupplied("house", WORLD, List.of(job("ready", ProjectQueue.Kind.BARRIER, List.of()), pickup,
                new ProjectQueue.Work("place-1", BUILD, target, List.of("pickup"), Set.of("wall"), "pickup"),
                new ProjectQueue.Work("place-2", BUILD, target, List.of("pickup"), Set.of("roof"), "pickup")), chest);
        var cargo = queue.claim(WORLD, "original", Set.of(TRANSFER), 1).job();
        assertTrue(queue.finish("house", "pickup", cargo.lease(), true, "Taken",
                new ProjectQueue.ChestReport(chest, java.util.Map.of()), 2));
        var first = queue.claim(WORLD, "original", Set.of(BUILD), 3).job();
        assertTrue(queue.finish("house", "place-1", first.lease(), false, ProjectQueue.TARGET_TIMEOUT, 4));

        var restored = new ProjectQueue(journal);
        assertNull(restored.claim(WORLD, "original", Set.of(BUILD), 5));
        var stranded = new ProjectQueue.ResourceSite(8, 60, 8);
        assertFalse(restored.offerConstructionHandoff(WORLD, "near-original", null, Set.of(BUILD), 5,
                worker -> stranded, (worker, item) -> 2));
        assertTrue(restored.offerConstructionHandoff(WORLD, "alternate", null, Set.of(BUILD), 6,
                worker -> worker.equals("original") ? stranded : new ProjectQueue.ResourceSite(15, 64, 8),
                (worker, item) -> material.equals(item) ? 2 : 0));
        assertNull(restored.claim(WORLD, "original", Set.of(BUILD), 7));
        var replacement = restored.claim(WORLD, "alternate", Set.of(BUILD), 8).job();
        assertEquals("place-1", replacement.work().id());
        assertEquals(1, replacement.attempts());
    }

    @Test void exhaustedHandoffBudgetAllowsOneNewBuilderForAFailedReplannedPose() throws Exception {
        var journal = directory.resolve("pose-handoff.json");
        String material = "minecraft:oak_planks";
        String chest = "block:-27,64,0";
        var pickup = new ProjectQueue.Work("pickup", TRANSFER,
                "{\"target\":{\"x\":-27,\"y\":64,\"z\":0},\"direction\":\"TAKE\",\"item\":\"" + material + "\",\"stock\":1}",
                List.of("ready"), Set.of(chest), null, null, material);
        var placement = new ProjectQueue.Work("place-1", BUILD, "{\"target\":{\"x\":0,\"y\":66,\"z\":0}}",
                List.of("pickup"), Set.of("wall"), "pickup");
        var later = new ProjectQueue.Work("place-2", BUILD, "{}", List.of("pickup", "place-1"),
                Set.of("other-wall"), "pickup");
        var laterPickup = new ProjectQueue.Work("later-pickup", TRANSFER, pickup.payload(),
                List.of("ready"), Set.of(chest), null, null, material);
        var dependent = new ProjectQueue.Work("place-3", BUILD, "{}",
                List.of(laterPickup.id(), later.id()), Set.of("next-wall"), laterPickup.id());
        var stock = new ProjectQueue.ChestReport(chest, java.util.Map.of(material, 4));
        var ready = job("ready", ProjectQueue.Kind.BARRIER, List.of());
        var initial = List.of(new ProjectQueue.Job(ready, ProjectQueue.State.DONE, 0, null, null, 0, "Ready", null),
                new ProjectQueue.Job(pickup, ProjectQueue.State.DONE, 1, "original", null, 0,
                        "Materials collected", new ProjectQueue.ChestObservation(stock, 1)),
                new ProjectQueue.Job(placement, ProjectQueue.State.FAILED, 3, null, null, 0, ProjectQueue.TARGET_TIMEOUT, null),
                new ProjectQueue.Job(later, ProjectQueue.State.WAITING, 0, null, null, 0, null, null),
                new ProjectQueue.Job(laterPickup, ProjectQueue.State.DONE, 1, "alternate", null, 0, "Collected", null),
                new ProjectQueue.Job(dependent, ProjectQueue.State.WAITING, 0, null, null, 0, null, null));
        var project = new ProjectQueue.Project("house", WORLD, initial, null, chest,
                java.util.Map.of("build-handoff:pickup", 2, "pose-handoff:pickup", 1, "pose:place-1", 1));
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of(project.id(), project))));
        var queue = new ProjectQueue(journal);
        var originalSite = new ProjectQueue.ResourceSite(1, 64, 1);
        var alternateSite = new ProjectQueue.ResourceSite(-29, 64, 0);
        assertFalse(queue.offerConstructionHandoff(WORLD, "far", null, Set.of(BUILD, TRANSFER), 2,
                name -> name.equals("original") ? originalSite : new ProjectQueue.ResourceSite(29, 64, 0), (bot, item) -> 0));
        assertTrue(queue.offerConstructionHandoff(WORLD, "alternate", null, Set.of(BUILD, TRANSFER), 3,
                name -> name.equals("original") ? originalSite : alternateSite, (bot, item) -> 0));
        queue = new ProjectQueue(journal);
        assertNull(queue.claim(WORLD, "original", Set.of(BUILD), 4));
        var collector = queue.claim(WORLD, "alternate", Set.of(TRANSFER), 5).job();
        assertEquals("recovery-place-1-cargo", collector.work().id());
        assertTrue(queue.finish("house", collector.work().id(), collector.lease(), true, "Plank collected", stock, 6));
        assertEquals("original", queue.snapshot(6).get(0).jobs().stream()
                .filter(job -> job.work().id().equals("pickup")).findFirst().orElseThrow().worker());
        assertTrue(queue.snapshot(6).get(0).jobs().stream().anyMatch(job -> job.work().equals(later)));
        long now = 7;
        for (int attempt = 0; attempt < 3; attempt++) {
            var assigned = queue.claim(WORLD, "alternate", Set.of(BUILD), now++).job();
            assertEquals("place-1", assigned.work().id());
            assertTrue(queue.finish("house", "place-1", assigned.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
            now += ProjectQueue.PLACEMENT_HANDOFF_WINDOW_MS;
        }
        queue = new ProjectQueue(journal);
        assertFalse(queue.offerConstructionHandoff(WORLD, "third", null, Set.of(BUILD, TRANSFER), now,
                name -> name.equals("alternate") ? originalSite : alternateSite, (bot, item) -> 1));
        assertEquals(1, queue.snapshot(now).get(0).supplyRecoveries().get("pose-handoff:place-1"));
        assertEquals(2, queue.snapshot(now).get(0).supplyRecoveries().get("build-handoff:pickup"));
        var verified = queue.blockedLocalHandoffs(WORLD).get(0);
        assertFalse(queue.installLocalHandoff(WORLD, verified, verified.job(), "third", 1, now++));
        assertNull(queue.claim(WORLD, "third", Set.of(BUILD), now++));
        assertFalse(queue.installLocalHandoff(WORLD, verified, verified.job(), "alternate", 1, now++));
        assertTrue(queue.installLocalHandoff(WORLD, verified, verified.job(), "third", 1, now++));
        queue = new ProjectQueue(journal);
        assertTrue(queue.blockedLocalHandoffs(WORLD).isEmpty());
        var next = queue.claim(WORLD, "third", Set.of(BUILD), now).job();
        assertEquals("place-1", next.work().id());
    }

    @Test void observedAlternatePoseReopensPlacementAndReplenishesMissingCargo() throws Exception {
        var journal = directory.resolve("alternate-placement-pose.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String material = "minecraft:oak_planks";
        var pickup = new ProjectQueue.Work("pickup", TRANSFER,
                "{\"direction\":\"TAKE\",\"item\":\"" + material + "\",\"stock\":1}",
                List.of("ready"), Set.of(chest), null, null, material);
        var original = new ProjectQueue.Work("place-1", BUILD, "{}", List.of("pickup"), Set.of("wall"), "pickup");
        queue.submitSupplied("house", WORLD, List.of(job("ready", ProjectQueue.Kind.BARRIER, List.of()), pickup, original), chest);
        var stock = new ProjectQueue.ChestReport(chest, java.util.Map.of(material, 5));
        var claim = queue.claim(WORLD, "builder", Set.of(TRANSFER), 1).job();
        assertTrue(queue.finish("house", "pickup", claim.lease(), true, "Taken", stock, 2));
        long now = 3;
        for (int attempt = 0; attempt < 3; attempt++) {
            var placement = queue.claim(WORLD, "builder", Set.of(BUILD), now++).job();
            assertTrue(queue.finish("house", "place-1", placement.lease(), false,
                    ProjectQueue.PLACEMENT_REJECTED + "6, 65, 1", now++));
            now += ProjectQueue.PLACEMENT_HANDOFF_WINDOW_MS;
        }
        var blocked = queue.blockedPlacementPoses(WORLD).get(0);
        var alternate = new ProjectQueue.Work("place-1", BUILD, "{\"face\":\"east\"}", List.of("pickup"),
                Set.of("wall", "other-support"), "pickup");
        assertTrue(queue.installPlacementPose(WORLD, blocked, alternate, 0, now++));
        assertFalse(queue.installPlacementPose(WORLD, blocked, alternate, 0, now++));
        queue = new ProjectQueue(journal);
        assertTrue(queue.blockedPlacementPoses(WORLD).isEmpty());
        assertNull(queue.claim(WORLD, "builder", Set.of(BUILD), now++));
        claim = queue.claim(WORLD, "builder", Set.of(TRANSFER), now++).job();
        assertEquals("pickup", claim.work().id());
        assertTrue(queue.finish("house", "pickup", claim.lease(), true, "Replacement picked up", stock, now++));
        var placement = queue.claim(WORLD, "builder", Set.of(BUILD), now).job();
        assertEquals(alternate, placement.work());
        assertEquals(1, placement.attempts());
    }

    @Test void temporaryAccessPersistsCargoDependencyAndCleansUpAfterTheBlockedPlacement() throws Exception {
        var journal = directory.resolve("scaffold-recovery.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String key = "block:6,64,1";
        String wall = "block:6,65,0";
        String originalStand = "block:7,65,0";
        var cargo = new ProjectQueue.Work("pickup", TRANSFER,
                "{\"direction\":\"TAKE\",\"item\":\"minecraft:oak_planks\",\"stock\":1}",
                List.of("ready"), Set.of(chest), null, null, "minecraft:oak_planks");
        var original = new ProjectQueue.Work("place-1", BUILD, "{\"target\":{\"x\":6,\"y\":65,\"z\":0}}",
                List.of("pickup"), Set.of(wall, originalStand), "pickup");
        var later = new ProjectQueue.Work("place-2", BUILD, "{}", List.of("pickup", "place-1"),
                Set.of("block:10,65,0"), "pickup");
        queue.submitSupplied("house", WORLD, List.of(job("ready", ProjectQueue.Kind.BARRIER, List.of()), cargo, original, later), chest);
        assertTrue(queue.accessReservations(WORLD).contains(originalStand));
        var stock = new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:cobblestone", 4));
        var taken = queue.claim(WORLD, "builder", Set.of(TRANSFER), 1).job();
        assertTrue(queue.finish("house", "pickup", taken.lease(), true, "Taken", stock, 2));
        long now = 3;
        for (int attempt = 0; attempt < 3; attempt++) {
            var lease = queue.claim(WORLD, "builder", Set.of(BUILD), now++).job();
            assertTrue(queue.finish("house", "place-1", lease.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
            now += ProjectQueue.PLACEMENT_HANDOFF_WINDOW_MS;
        }
        var request = queue.blockedAccess(WORLD).get(0);
        assertTrue(queue.accessReservations(WORLD).contains(wall));
        assertFalse(queue.accessReservations(WORLD).contains(originalStand));
        String intake = "repair-place-1-stock";
        String check = "check-access-repair-place-1-0";
        String step = "place-access-repair-place-1-0";
        String done = "repair-place-1-settled";
        var verification = new ProjectQueue.Work(check, ProjectQueue.Kind.VERIFY,
                "{\"target\":{\"x\":6,\"y\":64,\"z\":1},\"block\":\"minecraft:air\"}", List.of(intake), Set.of(key));
        var temporary = new ProjectQueue.Work(step, BUILD,
                "{\"target\":{\"x\":6,\"y\":64,\"z\":1},\"block\":\"minecraft:cobblestone\"}",
                List.of(intake, check), Set.of(key), intake);
        var moved = new ProjectQueue.Work("place-1", BUILD, "{\"stand\":{\"x\":6,\"y\":65,\"z\":1}}",
                List.of("pickup", step), Set.of("wall"), "pickup");
        var barrier = job(done, ProjectQueue.Kind.SETTLEMENT_BARRIER, List.of("place-1"));
        var cleanup = new ProjectQueue.Work("remove-access-repair-place-1-0", EXCAVATE,
                "{\"target\":{\"x\":6,\"y\":64,\"z\":1},\"block\":\"minecraft:cobblestone\"}", List.of(step),
                Set.of(key), null, new ProjectQueue.Cleanup(key, done));
        var repair = new StructureProject.AccessRepair(moved, List.of(verification, temporary), List.of(barrier, cleanup), 1);
        assertTrue(queue.installAccess(WORLD, request, repair, now++));
        assertFalse(queue.installAccess(WORLD, request, repair, now++));
        var restored = new ProjectQueue(journal);
        assertTrue(restored.scaffoldClearances(WORLD).isEmpty());
        assertNull(restored.claim(WORLD, "builder", Set.of(BUILD, EXCAVATE), now++));
        var gather = restored.claim(WORLD, "builder", Set.of(TRANSFER), now++).job();
        assertEquals(intake, gather.work().id());
        assertTrue(restored.finish("house", intake, gather.lease(), true, "Took scaffold stock", stock, now++));
        var remote = restored.claim(WORLD, "remote", Set.of(ProjectQueue.Kind.VERIFY), now++).job();
        assertEquals(check, remote.work().id());
        var clearance = restored.scaffoldClearances(WORLD).get(0);
        assertTrue(restored.confirmScaffoldClearance(WORLD, clearance, "Observed clear cell"));
        assertFalse(restored.finish("house", check, remote.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
        var scaffold = restored.claim(WORLD, "builder", Set.of(BUILD), now++).job();
        assertEquals(step, scaffold.work().id());
        assertTrue(restored.finish("house", step, scaffold.lease(), true, "Built scaffold", now++));
        assertNull(restored.claim(WORLD, "helper", Set.of(EXCAVATE), now++));
        var assignment = restored.claim(WORLD, "builder", Set.of(BUILD), now++);
        assertEquals(List.of(new ProjectQueue.ResourceSite(6, 65, 1)), assignment.accessRoute());
        var placement = assignment.job();
        assertEquals("place-1", placement.work().id());
        assertTrue(restored.finish("house", "place-1", placement.lease(), true, "Built wall", now++));
        for (int attempt = 0; attempt < 3; attempt++) {
            var next = restored.claim(WORLD, "builder", Set.of(BUILD), now++).job();
            assertEquals(later.id(), next.work().id());
            assertTrue(restored.finish("house", later.id(), next.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
            now += ProjectQueue.PLACEMENT_HANDOFF_WINDOW_MS;
        }
        assertTrue(restored.blockedAccess(WORLD).isEmpty());
        for (int attempt = 0; attempt < 3; attempt++) {
            var removal = restored.claim(WORLD, "helper", Set.of(EXCAVATE), now++).job();
            assertEquals(cleanup.id(), removal.work().id());
            assertTrue(restored.finish("house", cleanup.id(), removal.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
        }
        assertEquals(later, restored.blockedAccess(WORLD).get(0).job());
        var distant = new ProjectQueue.ResourceSite(6, 54, 1);
        var local = new ProjectQueue.ResourceSite(7, 64, 1);
        assertNull(restored.claim(WORLD, "remote", Set.of(EXCAVATE), null, now++, distant, item -> true));
        assertNull(restored.claim(WORLD, "local", Set.of(EXCAVATE), null, now++, local, item -> false));
        var recovered = restored.claim(WORLD, "local", Set.of(EXCAVATE), null, now++, local,
                "minecraft:cobblestone"::equals).job();
        assertEquals(cleanup.id(), recovered.work().id());
        assertTrue(restored.finish("house", cleanup.id(), recovered.lease(), true, "Observed air", now++));
        assertEquals(later, restored.blockedAccess(WORLD).get(0).job());
    }

    @Test void placementHandoffAccountsForCargoInTheBatchesItUnblocks() throws Exception {
        var journal = directory.resolve("reopened-construction-cargo.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String material = "minecraft:oak_log";
        String pickup = "{\"direction\":\"TAKE\",\"item\":\"" + material + "\",\"stock\":1}";
        var stock = new ProjectQueue.ChestReport(chest, java.util.Map.of(material, 2));
        queue.submitSupplied("house", WORLD, List.of(
                job("prepare", EXCAVATE, List.of()),
                new ProjectQueue.Work("pickup", TRANSFER, pickup, List.of("prepare"), Set.of(chest), null, null, material),
                new ProjectQueue.Work("roof-cargo", TRANSFER, pickup, List.of("prepare"), Set.of(chest), null, null, material),
                new ProjectQueue.Work("place-1", BUILD, "{\"target\":{\"x\":8,\"y\":64,\"z\":8}}",
                        List.of("pickup"), Set.of("wall"), "pickup"),
                new ProjectQueue.Work("place-2", BUILD, "", List.of("roof-cargo", "place-1"), Set.of("roof"), "roof-cargo")), chest);
        var prep = queue.claim(WORLD, "first", Set.of(EXCAVATE), 1).job();
        assertTrue(queue.finish("house", "prepare", prep.lease(), true, "Cleared", 2));
        var wallCargo = queue.claim(WORLD, "first", Set.of(TRANSFER), 3).job();
        assertTrue(queue.finish("house", "pickup", wallCargo.lease(), true, "Carrying wall cargo", stock, 4));
        var roofCargo = queue.claim(WORLD, "roofer", Set.of(TRANSFER), 5).job();
        assertTrue(queue.finish("house", "roof-cargo", roofCargo.lease(), true, "Carrying roof cargo", stock, 6));
        long now = 7;
        for (int attempt = 0; attempt < 3; attempt++) {
            var lease = queue.claim(WORLD, "first", Set.of(BUILD), now).job();
            assertTrue(queue.finish("house", "place-1", lease.lease(), false, ProjectQueue.TARGET_TIMEOUT, ++now));
            now += ProjectQueue.PLACEMENT_HANDOFF_WINDOW_MS;
        }
        java.util.function.Function<String, ProjectQueue.ResourceSite> locate = worker -> "first".equals(worker)
                ? new ProjectQueue.ResourceSite(8, 60, 8) : new ProjectQueue.ResourceSite(16, 64, 8);
        var kinds = Set.of(BUILD, TRANSFER);
        assertFalse(queue.offerConstructionHandoff(WORLD, "roofer", null, kinds, now, locate, (worker, item) -> 0));
        assertFalse(queue.offerConstructionHandoff(WORLD, "roofer", null, kinds, now, locate, (worker, item) -> 1));
        assertEquals(ProjectQueue.State.FAILED, queue.snapshot(now).get(0).jobs().get(3).state());
        assertTrue(queue.offerConstructionHandoff(WORLD, "helper", null, kinds, now + 1, locate, (worker, item) -> 0));
        var restored = new ProjectQueue(journal);
        var replacement = restored.claim(WORLD, "helper", kinds, now + 2).job();
        assertEquals("pickup", replacement.work().id());
        assertTrue(restored.finish("house", "pickup", replacement.lease(), true, "Carrying replacement wall cargo", stock, now + 3));
        var wall = restored.claim(WORLD, "helper", kinds, now + 4).job();
        assertEquals("place-1", wall.work().id());
        assertTrue(restored.finish("house", "place-1", wall.lease(), true, "Placed wall", now + 5));
        assertEquals("place-2", restored.claim(WORLD, "roofer", kinds, now + 6).job().work().id());
    }

    @Test void delegatesOneScaffoldStepWithoutReassigningTheMinersRemainingBlocks() throws Exception {
        var journal = directory.resolve("scaffold-handoff.json");
        var queue = new ProjectQueue(journal);
        var chest = "block:3,64,0";
        var pickup = new ProjectQueue.Work("pickup", TRANSFER,
                """
                {"direction":"TAKE","item":"minecraft:cobblestone","stock":2}
                """, List.of("clear"), Set.of(chest), null, null, "minecraft:cobblestone");
        var step = new ProjectQueue.Work("place-access-0", BUILD,
                """
                {"target":{"x":8,"y":64,"z":8},"block":"minecraft:cobblestone"}
                """, List.of("pickup"), Set.of("block:8,64,8"), "pickup");
        var wall = new ProjectQueue.Work("place-1", BUILD, "",
                List.of(step.id(), pickup.id()), Set.of("block:8,65,8"), pickup.id());
        queue.submitSupplied("house", WORLD, List.of(job("clear", EXCAVATE, List.of()), pickup, step, wall), chest);
        var clear = queue.claim(WORLD, "miner", Set.of(EXCAVATE), 1).job();
        assertTrue(queue.finish("house", clear.work().id(), clear.lease(), true, "Observed air", 2));
        var taken = queue.claim(WORLD, "miner", Set.of(TRANSFER), 3).job();
        assertTrue(queue.finish("house", taken.work().id(), taken.lease(), true, "Observed two blocks carried",
                new ProjectQueue.ChestReport(chest, java.util.Map.of("minecraft:cobblestone", 1)), 4));
        queue.submit("mining", WORLD, List.of(job("mine", GATHER, List.of()), job("more-mining", GATHER, List.of())));
        var mining = queue.claim(WORLD, "miner", Set.of(GATHER), 5).job();
        var kinds = Set.of(BUILD, TRANSFER);
        var nearby = new ProjectQueue.ResourceSite(8, 64, 9);
        var supplied = queue.claim(WORLD, "builder", Set.of(BUILD, TRANSFER, GATHER), null, 6, nearby,
                material -> false, (worker, item) -> 0, java.util.Map.of(), 0, false,
                worker -> worker.equals("miner") ? new ProjectQueue.ResourceSite(8, 14, 9) : nearby).job();
        assertEquals("recovery-place-access-0-cargo", supplied.work().id());
        queue = new ProjectQueue(journal);
        assertEquals(supplied.lease(), queue.claim(WORLD, "builder", kinds, 7).job().lease());
        assertTrue(supplied.work().payload().contains("\"stock\":1"));
        assertTrue(queue.finish("house", supplied.work().id(), supplied.lease(), true, "Observed one block carried",
                new ProjectQueue.ChestReport(chest, java.util.Map.of()), 8));
        var placement = queue.claim(WORLD, "builder", kinds, 9).job();
        assertEquals(step.id(), placement.work().id());
        assertTrue(queue.finish("house", step.id(), placement.lease(), true, "Observed scaffold", 10));
        assertNull(queue.claim(WORLD, "builder", kinds, 11));
        assertEquals(mining.lease(), queue.claim(WORLD, "miner", Set.of(GATHER), 12).job().lease());
        assertTrue(queue.finish("mining", mining.work().id(), mining.lease(), true, "Observed ore", 13));
        assertEquals(wall.id(), queue.claim(WORLD, "miner", kinds, 14).job().work().id());
    }

    private void checkConstructionHandoff(boolean stockAvailable, boolean busySupplier, boolean closeSupplier) throws Exception {
        var journal = directory.resolve("construction-handoff-" + stockAvailable + ".json");
        var queue = new ProjectQueue(journal);
        var chest = "block:3,64,0";
        var pickup = new ProjectQueue.Work("pickup", TRANSFER,
                "{\"direction\":\"TAKE\",\"item\":\"minecraft:oak_log\",\"stock\":1}",
                List.of("clear"), Set.of(chest), null, null, "minecraft:oak_log");
        var place = new ProjectQueue.Work("place-1", BUILD,
                "{\"target\":{\"x\":8,\"y\":64,\"z\":8},\"block\":\"minecraft:oak_log\"}",
                List.of("pickup"), Set.of("block:8,64,8"), "pickup");
        queue.submitSupplied("house", WORLD, List.of(job("clear", EXCAVATE, List.of()), pickup, place), chest);
        var done = queue.claim(WORLD, "original", Set.of(EXCAVATE), 1).job();
        assertTrue(queue.finish("house", "clear", done.lease(), true, "Done", 2));
        var cargo = queue.claim(WORLD, "original", Set.of(TRANSFER), 3).job();
        var stock = new ProjectQueue.ChestReport(chest, stockAvailable
                ? java.util.Map.of("minecraft:oak_log", 1) : java.util.Map.of());
        assertTrue(queue.finish("house", "pickup", cargo.lease(), true, "Taken", stock, 4));
        java.util.function.Function<String, ProjectQueue.ResourceSite> locate = name -> name.equals("builder")
                ? new ProjectQueue.ResourceSite(closeSupplier ? 16 : 8, 64, closeSupplier ? 8 : 10)
                : new ProjectQueue.ResourceSite(8, closeSupplier ? 60 : 14, 8);
        long now = busySupplier ? 11 : 5;
        ProjectQueue.Job mining = null;
        if (busySupplier) {
            var kinds = Set.of(BUILD, TRANSFER);
            assertFalse(queue.offerConstructionHandoff(WORLD, "builder", null, kinds, 5, locate, (worker, item) -> 0));
            queue.submit("mining", WORLD, List.of(job("mine", GATHER, List.of())));
            mining = queue.claim(WORLD, "original", Set.of(GATHER), 6).job();
            var closeBy = new ProjectQueue.ResourceSite(8, 64, 10);
            assertFalse(queue.offerConstructionHandoff(WORLD, "builder", null, kinds, 7,
                    worker -> closeBy, (worker, item) -> 0));
        } else {
            for (int attempt = 0; attempt < 3; attempt++) {
                var lease = queue.claim(WORLD, "original", Set.of(BUILD), now++).job();
                assertTrue(queue.finish("house", "place-1", lease.lease(), false, ProjectQueue.TARGET_TIMEOUT, now++));
                now += ProjectQueue.PLACEMENT_HANDOFF_WINDOW_MS;
            }
        }
        if (closeSupplier) {
            assertFalse(queue.offerConstructionHandoff(WORLD, "builder", null, Set.of(BUILD, TRANSFER), now,
                    worker -> "builder".equals(worker) ? new ProjectQueue.ResourceSite(9, 61, 8) : locate.apply(worker),
                    (worker, item) -> 0));
        }
        assertFalse(queue.offerConstructionHandoff(WORLD, "builder", null, Set.of(BUILD), now, locate, (worker, item) -> 0));
        assertEquals(stockAvailable, queue.offerConstructionHandoff(WORLD, "builder", null,
                Set.of(BUILD, TRANSFER), now, locate, (worker, item) -> 0));
        if (!stockAvailable) {
            assertTrue(queue.offerConstructionHandoff(WORLD, "builder", null, Set.of(BUILD), now, locate, (worker, item) -> 1));
        }
        var restored = new ProjectQueue(journal);
        if (stockAvailable) {
            assertFalse(restored.finish("house", "pickup", cargo.lease(), true, "Taken", stock, now + 1));
            assertNull(restored.claim(WORLD, "original", Set.of(TRANSFER), now + 1));
            assertNull(restored.claim(WORLD, "builder", Set.of(BUILD), now + 1));
            var replacement = restored.claim(WORLD, "builder", Set.of(TRANSFER), now + 1).job();
            assertEquals("pickup", replacement.work().id());
            assertTrue(restored.finish("house", "pickup", replacement.lease(), true, "Taken",
                    new ProjectQueue.ChestReport(chest, java.util.Map.of()), now + 2));
        } else {
            assertTrue(restored.finish("house", "pickup", cargo.lease(), true, "Taken", stock, now + 2));
        }
        assertNull(restored.claim(WORLD, "original", Set.of(BUILD), now + 3));
        assertEquals("place-1", restored.claim(WORLD, "builder", Set.of(BUILD), now + 3).job().work().id());
        if (busySupplier) {
            assertEquals(mining.lease(), restored.claim(WORLD, "original", Set.of(GATHER), now + 3).job().lease());
        }
    }

    @Test void retriesOnlyPlacementsWithExhaustedApproaches() throws Exception {
        var journal = directory.resolve("placement-approaches.json");
        var board = new ProjectQueue(journal);
        board.submit("house", WORLD, List.of(job("first", BUILD, List.of()), job("second", BUILD, List.of()),
                job("third", BUILD, List.of())));
        for (long now = 1; now <= 17; now += 2) {
            var lease = board.claim(WORLD, "worker", Set.of(BUILD), now).job();
            String evidence = switch (lease.work().id()) {
                case "first" -> "No reachable solid support face for 1, 2, 3";
                case "second" -> "Build target is occupied";
                default -> ProjectQueue.TARGET_TIMEOUT;
            };
            assertTrue(board.finish("house", lease.work().id(), lease.lease(), false, evidence, now + 1));
        }
        assertEquals(2, board.retryPlacementApproaches(WORLD, "house"));
        var restored = new ProjectQueue(journal);
        var jobs = restored.snapshot(19).get(0).jobs();
        assertEquals(ProjectQueue.State.WAITING, jobs.get(0).state());
        assertEquals(ProjectQueue.State.FAILED, jobs.get(1).state());
        assertEquals(ProjectQueue.State.WAITING, jobs.get(2).state());
    }

    @Test void fuelSubstitutionUpdatesOnlyTheMatchingSmeltJobAndSurvivesRestart() throws Exception {
        var journal = directory.resolve("smelt-fuel.json");
        var board = new ProjectQueue(journal);
        String coal = "{\"resource\":\"coal\",\"variant\":\"both\",\"item\":\"minecraft:coal\",\"count\":1}";
        board.submitSupplied("stock", WORLD, List.of(new ProjectQueue.Work("fuel", GATHER, coal, List.of(), Set.of(), null),
                new ProjectQueue.Work("smelt", SMELT,
                        "{\"recipe\":\"minecraft:iron_ingot_from_smelting_raw_iron\",\"input\":\"minecraft:raw_iron\",\"fuel\":\"minecraft:coal\",\"count\":3}",
                        List.of("fuel"), Set.of(), "fuel"),
                new ProjectQueue.Work("coal-stock", GATHER, coal, List.of(), Set.of(), null)), "block:3,64,0");
        var fuel = board.claim(WORLD, "miner", Set.of(GATHER), 1);
        assertEquals("smelt", fuel.fuelFor().job());
        assertTrue(board.finish("stock", "fuel", fuel.job().lease(), true,
                ProjectQueue.ALTERNATIVE_FUEL + "minecraft:oak_planks", 2));
        var restored = new ProjectQueue(journal);
        var smelt = restored.claim(WORLD, "miner", Set.of(SMELT), 3).job();
        assertEquals("minecraft:oak_planks", com.google.gson.JsonParser.parseString(smelt.work().payload())
                .getAsJsonObject().get("fuel").getAsString());
        var coalStock = restored.claim(WORLD, "collector", Set.of(GATHER), 4);
        assertNull(coalStock.fuelFor());
        assertThrows(IllegalArgumentException.class, () -> restored.finish("stock", "coal-stock", coalStock.job().lease(),
                true, ProjectQueue.ALTERNATIVE_FUEL + "minecraft:oak_planks", 5));
    }

    @Test void helperWithRecipeIngredientsTakesOverAfterTheGatherLeaseEnds() throws Exception {
        var journal = directory.resolve("craft-handoff.json");
        var board = new ProjectQueue(journal);
        var wood = new ProjectQueue.Work("logs", GATHER,
                "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\"}", List.of(), Set.of());
        var ladder = new ProjectQueue.Work("ladders", CRAFT, "{\"recipe\":\"minecraft:ladder\"}",
                List.of("logs"), Set.of(), "logs");
        board.submitSupplied("order", WORLD, List.of(wood, ladder), "block:1,64,0");
        var original = board.claim(WORLD, "scout", Set.of(GATHER), 1).job();
        var request = board.carriedCraftCandidates(WORLD, 2).get(0);
        var sticks = Set.of("minecraft:stick");
        assertFalse(board.offerCarriedCraftHandoff(WORLD, request, "helper", sticks, (w,i) -> 0, 2));
        assertNull(board.claim(WORLD, "helper", Set.of(GATHER, CRAFT), 3));
        assertFalse(board.offerCarriedCraftHandoff(WORLD, request, "helper", sticks, (w,i) -> 1, 4));
        assertTrue(board.offerCarriedCraftHandoff(WORLD, request, "helper", sticks, (w,i) -> 0, 4));
        board.submit("other", WORLD, List.of(job("stone", GATHER, List.of())));
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "scout", Set.of(GATHER), 5));
        assertNull(board.claim(WORLD, "helper", Set.of(GATHER), original.expiresAt() - 1));
        assertNull(board.claim(WORLD, "helper", Set.of(GATHER), "other", original.expiresAt() - 1));
        var independent = board.claim(WORLD, "collector", Set.of(GATHER), "other", original.expiresAt() - 1).job();
        assertEquals("stone", independent.work().id());
        assertNull(board.renew("order", "logs", original.lease(), 5));
        board.submitSupplied("second", WORLD, List.of(wood, ladder), "block:2,64,0");
        board.claim(WORLD, "second-scout", Set.of(GATHER), "second", original.expiresAt() - 1);
        // The helper also owns the short window between expiry and collecting the promised lease.
        board.snapshot(original.expiresAt());

        var second = board.carriedCraftCandidates(WORLD, original.expiresAt()).stream()
                .filter(candidate -> candidate.project().equals("second")).findFirst().orElseThrow();
        assertFalse(board.offerCarriedCraftHandoff(WORLD, second, "helper", sticks, (w,i) -> 0, original.expiresAt()));
        var next = board.claim(WORLD, "helper", Set.of(GATHER), original.expiresAt()).job();
        assertEquals("logs", next.work().id());
        assertFalse(board.useCarriedCraftingIngredients(WORLD, request, sticks, original.expiresAt() + 1));
        var transferred = board.carriedCraftCandidates(WORLD, original.expiresAt() + 1).stream()
                .filter(candidate -> candidate.project().equals("order")).findFirst().orElseThrow();
        assertTrue(board.useCarriedCraftingIngredients(WORLD, transferred, sticks, original.expiresAt() + 1));
        assertEquals("ladders", board.claim(WORLD, "helper", Set.of(CRAFT), original.expiresAt() + 2).job().work().id());
    }

    @Test void helperCanFinishPinnedPreparationOnlyAfterLeaseExpiryAndFreshInventory() throws Exception {
        var journal = directory.resolve("pinned-recipe.json");
        var board = new ProjectQueue(journal);
        var prior = job("old-tool", CRAFT, List.of());
        var wood = new ProjectQueue.Work("logs", GATHER,
                "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\",\"count\":3}", List.of("old-tool"), Set.of(), "old-tool");
        var ladder = new ProjectQueue.Work("ladders", CRAFT, "{\"recipe\":\"minecraft:ladder\"}",
                List.of("logs"), Set.of(), "logs");
        board.submitSupplied("order", WORLD, List.of(prior, wood, ladder), "block:1,64,0");
        var tool = board.claim(WORLD, "scout", Set.of(CRAFT), 1).job();
        assertTrue(board.finish("order", "old-tool", tool.lease(), true, "Observed old tool", 2));
        var gather = board.claim(WORLD, "scout", Set.of(GATHER), 3).job();
        var sticks = Set.of("minecraft:stick");
        assertNull(board.claim(WORLD, "helper", Set.of(CRAFT, GATHER), 4));
        var request = board.carriedCraftCandidates(WORLD, 5).get(0);
        assertFalse(board.offerCarriedCraftHandoff(WORLD, request, "helper", sticks, (w,i) -> -1, 5));
        assertFalse(board.offerCarriedCraftHandoff(WORLD, request, "helper", sticks, (w,i) -> 3, 5));
        assertTrue(board.offerCarriedCraftHandoff(WORLD, request, "helper", sticks, (w,i) -> 2, 5));
        board = new ProjectQueue(journal);
        assertNull(board.renew("order", "logs", gather.lease(), 6));
        assertTrue(board.carriedCraftCandidates(WORLD, gather.expiresAt() - 1).isEmpty());
        board.submit("other", WORLD, List.of(job("spare", GATHER, List.of())));
        assertNull(board.claim(WORLD, "helper", Set.of(GATHER, CRAFT), gather.expiresAt()));
        var released = board.carriedCraftCandidates(WORLD, gather.expiresAt()).get(0);
        assertEquals("helper", released.worker());
        assertFalse(board.useCarriedCraftingIngredients(WORLD, request, sticks, gather.expiresAt()));
        assertFalse(board.useCarriedCraftingIngredients(WORLD, released, Set.of(), gather.expiresAt()));
        assertTrue(board.useCarriedCraftingIngredients(WORLD, released, sticks, gather.expiresAt()));
        assertEquals("ladders", board.claim(WORLD, "helper", Set.of(CRAFT), gather.expiresAt() + 1).job().work().id());
        assertNull(board.claim(WORLD, "scout", Set.of(CRAFT), gather.expiresAt() + 2));
    }

    @Test void carriedIngredientsRetireOnlyThePrivateGatherForTheCurrentWorker() throws Exception {
        var journal = directory.resolve("carried-crafting.json");
        var board = new ProjectQueue(journal);
        var wood = job("logs", GATHER, List.of());
        var sticks = new ProjectQueue.Work("sticks", CRAFT, "{}", List.of("logs"), Set.of(), "logs");
        var craft = new ProjectQueue.Work("tool", CRAFT, "{\"recipe\":\"minecraft:iron_pickaxe\"}",
                List.of("sticks"), Set.of(), "sticks");
        var mine = new ProjectQueue.Work("diamonds", GATHER, "{}", List.of("tool"), Set.of(), "tool");
        var shared = new ProjectQueue.Work("second-use", VERIFY, "{}", List.of("sticks"), Set.of());
        board.submitSupplied("independent", WORLD, List.of(wood, sticks, craft, mine), "block:1,64,0");
        board.submitSupplied("shared", WORLD, List.of(wood, sticks, craft, mine, shared), "block:2,64,0");
        var gather = board.claim(WORLD, "worker", Set.of(GATHER), "independent", 1).job();
        var sharedGather = board.claim(WORLD, "other", Set.of(GATHER), "shared", 1).job();
        var candidates = board.carriedCraftCandidates(WORLD, 2);
        assertEquals(1, candidates.size());
        var request = candidates.get(0);
        assertEquals("independent", request.project());
        assertFalse(board.useCarriedCraftingIngredients(WORLD, request, Set.of(), 2));
        assertTrue(board.useCarriedCraftingIngredients(WORLD, request, Set.of("minecraft:stick", "minecraft:iron_ingot"), 2));
        assertNull(board.renew("independent", "logs", gather.lease(), 3));
        assertNotNull(board.renew("shared", "logs", sharedGather.lease(), 3));
        var restarted = new ProjectQueue(journal);
        assertNull(restarted.claim(WORLD, "helper", Set.of(CRAFT), "independent", 4));
        var tool = restarted.claim(WORLD, "worker", Set.of(CRAFT), "independent", 4).job();
        assertEquals("tool", tool.work().id());
        assertTrue(restarted.finish("independent", "tool", tool.lease(), true, "Observed iron pickaxe", 5));
        assertEquals("diamonds", restarted.claim(WORLD, "worker", Set.of(GATHER), "independent", 6).job().work().id());
    }

    @Test void equippedReplacementWorkerRefillsFailedSmeltingWithoutRebuildingTools() throws Exception {
        checkEquippedSmeltingRefill("Missing furnace input minecraft:raw_iron after 2 supply recoveries");
    }

    @Test void equippedReplacementWorkerRefillsTimedOutSmeltingWithoutRebuildingTools() throws Exception {
        checkEquippedSmeltingRefill(ProjectQueue.TARGET_TIMEOUT);
    }

    private void checkEquippedSmeltingRefill(String failure) throws Exception {
        var journal = directory.resolve("smelting-refill.json");
        var pick = new ProjectQueue.Work("pick", CRAFT, "{\"recipe\":\"minecraft:stone_pickaxe\"}", List.of(), Set.of());
        var ore = new ProjectQueue.Work("ore", GATHER,
                "{\"resource\":\"iron\",\"variant\":\"both\",\"item\":\"minecraft:raw_iron\",\"count\":3}",
                List.of("pick"), Set.of(), "pick");
        var fuel = new ProjectQueue.Work("fuel", GATHER,
                "{\"resource\":\"coal\",\"variant\":\"both\",\"item\":\"minecraft:coal\",\"count\":1}",
                List.of("ore"), Set.of(), "ore");
        var smelt = new ProjectQueue.Work("smelt", SMELT,
                "{\"input\":\"minecraft:raw_iron\",\"fuel\":\"minecraft:coal\",\"count\":3}",
                List.of("fuel"), Set.of(), "fuel");
        var site = new ProjectQueue.ResourceSite(4, 42, 8);
        var project = new ProjectQueue.Project("tools", WORLD, List.of(
                new ProjectQueue.Job(pick, ProjectQueue.State.DONE, 1, "original", null, 0, "Observed pick", null),
                new ProjectQueue.Job(ore, ProjectQueue.State.DONE, 1, "original", null, 0, "Observed ore", null, site),
                new ProjectQueue.Job(fuel, ProjectQueue.State.DONE, 1, "original", null, 0, "Observed fuel", null),
                new ProjectQueue.Job(smelt, ProjectQueue.State.FAILED, 2, null, null, 0, failure, null)),
                null, "block:3,64,0", java.util.Map.of());
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of(project.id(), project))));
        var board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "helper", Set.of(GATHER, SMELT), 1));
        assertNull(board.offerSmeltingRefill(WORLD, "helper", "tools", java.util.Map.of("minecraft:wooden_pickaxe", 50), 2));
        var recovery = board.offerSmeltingRefill(WORLD, "helper", "tools", java.util.Map.of("minecraft:stone_pickaxe", 50), 2);
        assertEquals("ore", recovery.job().work().id());
        assertEquals(site, recovery.job().resourceSite());
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "other", Set.of(GATHER, SMELT), 3));
        assertTrue(board.finish("tools", "ore", recovery.job().lease(), true, "Observed 3 raw iron", 4));
        var fuelLease = board.claim(WORLD, "helper", Set.of(GATHER, SMELT), 5).job();
        assertTrue(board.finish("tools", "fuel", fuelLease.lease(), true, "Observed coal", 6));
        assertEquals("smelt", board.claim(WORLD, "helper", Set.of(SMELT), 7).job().work().id());
    }

    @Test void smeltingWithLostInputsCanUseAnotherWorkersSuppliesAfterRetryLimit() throws Exception {
        var journal = directory.resolve("lost-furnace-input.json");
        var ore = job("ore", GATHER, List.of());
        var smelt = new ProjectQueue.Work("smelt", SMELT,
                "{\"input\":\"minecraft:raw_iron\",\"fuel\":\"minecraft:coal\",\"count\":3}",
                List.of("ore"), Set.of("block:4,64,0"), "ore");
        var finish = new ProjectQueue.Work("next", CRAFT, "{}", List.of("smelt"), Set.of(), "smelt");
        var project = new ProjectQueue.Project("tools", WORLD, List.of(
                new ProjectQueue.Job(ore, ProjectQueue.State.DONE, 1, "original", null, 0, "Observed ore", null),
                new ProjectQueue.Job(smelt, ProjectQueue.State.FAILED, 2, null, null, 0,
                        "Missing furnace input minecraft:raw_iron after 2 supply recoveries", null),
                new ProjectQueue.Job(finish, ProjectQueue.State.WAITING, 0, null, null, 0, null, null)),
                null, "block:3,64,0", java.util.Map.of());
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of(project.id(), project))));
        var board = new ProjectQueue(journal);
        var target = board.resumableSmelting(WORLD, 1).get(0);
        assertNull(board.claim(WORLD, "helper", Set.of(SMELT), 1));
        assertTrue(board.resumeCarriedSmelting(WORLD, target, "helper", "minecraft:oak_planks", 2));
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "original", Set.of(SMELT), 3));
        assertNull(board.claim(WORLD, "helper", Set.of(SMELT), 30_003));
        var expired = board.resumableSmelting(WORLD, 30_003).get(0);
        assertTrue(board.resumeCarriedSmelting(WORLD, expired, "helper", "minecraft:oak_planks", 30_004));
        var lease = board.claim(WORLD, "helper", Set.of(SMELT), 30_005).job();
        assertEquals("smelt", lease.work().id());
        assertTrue(board.finish("tools", "smelt", lease.lease(), true, "Observed 3 iron ingots", 30_006));
        assertEquals("next", board.claim(WORLD, "helper", Set.of(CRAFT), 30_007).job().work().id());
        assertEquals("original", com.google.gson.JsonParser.parseString(Files.readString(journal))
                .getAsJsonObject().getAsJsonObject("projects").getAsJsonObject("tools")
                .getAsJsonArray("jobs").get(0).getAsJsonObject().get("worker").getAsString());
    }

    @Test void stalledSmeltingResumesFromCarriedSuppliesWithoutReplayingPrivatePreparation() throws Exception {
        var journal = directory.resolve("resume-carried-smelting.json");
        var ore = job("ore", GATHER, List.of());
        var fuel = new ProjectQueue.Work("fuel", GATHER, "", List.of("ore"), Set.of(), "ore");
        var smelt = new ProjectQueue.Work("smelt", SMELT,
                "{\"input\":\"minecraft:raw_iron\",\"fuel\":\"minecraft:oak_log\",\"count\":3}",
                List.of("fuel"), Set.of(), "fuel");
        var craft = new ProjectQueue.Work("craft", CRAFT, "", List.of("smelt"), Set.of(), "smelt");
        var project = new ProjectQueue.Project("tools", WORLD, List.of(
                new ProjectQueue.Job(ore, ProjectQueue.State.FAILED, 3, null, null, 0, ProjectQueue.GATHER_STALLED, null),
                new ProjectQueue.Job(fuel, ProjectQueue.State.WAITING, 0, null, null, 0, null, null),
                new ProjectQueue.Job(smelt, ProjectQueue.State.WAITING, 0, null, null, 0,
                        "Missing furnace fuel minecraft:oak_log; reacquiring materials", null),
                new ProjectQueue.Job(craft, ProjectQueue.State.WAITING, 0, null, null, 0, null, null)),
                null, "block:3,64,0", java.util.Map.of());
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of(project.id(), project))));
        var board = new ProjectQueue(journal);
        var request = board.resumableSmelting(WORLD, 1).get(0);
        assertFalse(board.resumeCarriedSmelting(WORLD, request, "worker", "minecraft:oak_planks", 1));
        assertNull(board.claim(WORLD, "worker", Set.of(SMELT), 2));
        assertTrue(board.resumeCarriedSmelting(WORLD, request, "worker", "minecraft:oak_planks", 3));
        board = new ProjectQueue(journal);
        assertTrue(board.resumableSmelting(WORLD, 4).isEmpty());
        assertNull(board.claim(WORLD, "other", Set.of(SMELT, CRAFT), 4));
        var lease = board.claim(WORLD, "worker", Set.of(SMELT, CRAFT), 4).job();
        assertEquals("minecraft:oak_planks", com.google.gson.JsonParser.parseString(lease.work().payload())
                .getAsJsonObject().get("fuel").getAsString());
        assertTrue(board.finish("tools", "smelt", lease.lease(), true, "Observed 3 iron ingots", 5));
        assertEquals("craft", board.claim(WORLD, "worker", Set.of(CRAFT), 6).job().work().id());
    }

    @Test void stalledFuelGatheringCanResumeWithMixedSuppliesAndKeepTheOreReceipt() throws Exception {
        var journal = directory.resolve("mixed-smelting-recovery.json");
        var ore = job("ore", GATHER, List.of());
        var fuel = new ProjectQueue.Work("fuel", GATHER,
                "{\"resource\":\"coal\",\"variant\":\"both\",\"item\":\"minecraft:coal\",\"count\":1}",
                List.of("ore"), Set.of(), "ore");
        var smelt = new ProjectQueue.Work("smelt", SMELT,
                "{\"input\":\"minecraft:raw_iron\",\"fuel\":\"minecraft:coal\",\"count\":3}",
                List.of("fuel"), Set.of(), "fuel");
        var receipt = new ProjectQueue.Job(ore, ProjectQueue.State.DONE, 1, "original", null, 0, "Observed raw iron", null);
        var project = new ProjectQueue.Project("tools", WORLD, List.of(receipt,
                new ProjectQueue.Job(fuel, ProjectQueue.State.WAITING, 0, null, null, 10,
                        ProjectQueue.GATHER_STALLED, null),
                new ProjectQueue.Job(smelt, ProjectQueue.State.WAITING, 0, null, null, 0,
                        ProjectQueue.GATHER_STALLED + "; reacquiring materials", null)),
                null, "block:3,64,0", java.util.Map.of());
        Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                "projects", java.util.Map.of(project.id(), project))));
        var board = new ProjectQueue(journal);
        assertTrue(board.resumableSmelting(WORLD, 9).isEmpty());
        board.claim(WORLD, "helper", Set.of(SMELT), 11);
        var candidate = board.resumableSmelting(WORLD, 11).get(0);
        assertFalse(board.resumeCarriedSmelting(WORLD, candidate, "helper", "minecraft:oak_planks",
                Set.of("minecraft:stick"), 11));
        assertTrue(board.resumeCarriedSmelting(WORLD, candidate, "helper", "minecraft:oak_planks",
                Set.of("minecraft:oak_planks", "minecraft:stick"), 11));
        board = new ProjectQueue(journal);
        var changed = board.snapshot(12).get(0).jobs();
        assertEquals(receipt, changed.get(0));
        assertEquals("helper", changed.get(1).worker());
        assertTrue(board.resumableSmelting(WORLD, 12).isEmpty());
        var lease = board.claim(WORLD, "helper", Set.of(SMELT), 12).job();
        var payload = com.google.gson.JsonParser.parseString(lease.work().payload()).getAsJsonObject();
        assertEquals("minecraft:oak_planks", payload.get("fuel").getAsString());
        assertEquals("minecraft:coal", payload.get("plannedFuel").getAsString());
        assertEquals("[\"minecraft:oak_planks\",\"minecraft:stick\"]", payload.getAsJsonArray("fuelChoices").toString());
    }

    @Test void lostSmeltingInputsReopenOnlyTheirSupplyChain() throws Exception {
        var journal = directory.resolve("lost-smelting-inputs.json");
        var board = new ProjectQueue(journal);
        board.submitSupplied("tools", WORLD, List.of(job("earlier", EXCAVATE, List.of()),
                job("ore", GATHER, List.of("earlier")),
                new ProjectQueue.Work("fuel", GATHER, "", List.of("ore"), Set.of(), "ore"),
                new ProjectQueue.Work("smelt", SMELT, "{\"input\":\"minecraft:raw_iron\",\"fuel\":\"minecraft:oak_log\"}",
                        List.of("fuel"), Set.of(), "fuel")), "block:3,64,0");
        long now = 1;
        for (var kind : List.of(EXCAVATE, GATHER, GATHER)) {
            var supplied = board.claim(WORLD, "worker", Set.of(kind), now++).job();
            assertTrue(board.finish("tools", supplied.work().id(), supplied.lease(), true, "Done", now++));
        }
        var smelt = board.claim(WORLD, "worker", Set.of(SMELT), now++).job();
        assertTrue(board.finish("tools", "smelt", smelt.lease(), false, "Missing furnace input minecraft:raw_iron", now++));
        var restored = new ProjectQueue(journal);
        assertEquals(ProjectQueue.State.DONE, restored.snapshot(now).get(0).jobs().get(0).state());
        assertNull(restored.claim(WORLD, "replacement", Set.of(SMELT), now++));
        assertEquals("ore", restored.claim(WORLD, "replacement", Set.of(GATHER), now).job().work().id());
    }

    @Test void returningProducerReconcilesOnceAfterLastLeaseExpires() throws Exception {
        for (var kind : List.of(CRAFT, SMELT)) {
            var journal = directory.resolve("interrupted-" + kind + ".json");
            var board = new ProjectQueue(journal);
            board.submit("orders", WORLD, List.of(job("work", kind, List.of())));
            var lease = board.claim(WORLD, "maker", Set.of(kind), 1).job();
            assertNull(board.claim(WORLD, "other", Set.of(kind), lease.expiresAt()));
            for (int attempt = 2; attempt <= 3; attempt++) {
                lease = board.claim(WORLD, "maker", Set.of(kind), lease.expiresAt() + 1).job();
                assertEquals(attempt, lease.attempts());
            }
            long now = lease.expiresAt();
            assertNull(board.claim(WORLD, "other", Set.of(kind), now));
            board = new ProjectQueue(journal);
            assertNull(board.claim(WORLD, "other", Set.of(kind), now + 60_000));
            var resumed = board.claim(WORLD, "maker", Set.of(kind), now + 60_001).job();
            assertEquals(4, resumed.attempts());
            assertNotEquals(lease.lease(), resumed.lease());
            assertFalse(board.finish("orders", "work", lease.lease(), true, "Late report", now + 60_002));
            board = new ProjectQueue(journal);
            assertNull(board.claim(WORLD, "maker", Set.of(kind), resumed.expiresAt()));
        }
    }

    @Test void oldProductionJournalUsesTheRecordedSupplierToIdentifyTheMaker() throws Exception {
        var journal = directory.resolve("old-production.json");
        var board = new ProjectQueue(journal);
        board.submit("orders", WORLD, List.of(job("supplies", GATHER, List.of()),
                new ProjectQueue.Work("tool", CRAFT, "{}", List.of("supplies"), Set.of(), "supplies")));
        var source = board.claim(WORLD, "maker", Set.of(GATHER), 1).job();
        assertTrue(board.finish("orders", "supplies", source.lease(), true, "Held materials", 2));
        var saved = com.google.gson.JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
        var tool = saved.getAsJsonObject("projects").getAsJsonObject("orders").getAsJsonArray("jobs").get(1).getAsJsonObject();
        tool.addProperty("state", "FAILED");
        tool.addProperty("attempts", 3);
        tool.addProperty("evidence", "Worker lease expired");
        Files.writeString(journal, saved.toString());
        board = new ProjectQueue(journal);
        assertNull(board.claim(WORLD, "other", Set.of(CRAFT), 3));
        var resumed = board.claim(WORLD, "maker", Set.of(CRAFT), 4).job();
        assertTrue(board.finish("orders", "tool", resumed.lease(), true, "Observed tool", 5));
    }

    @Test void retriesInterruptedProductionWithoutReopeningRecipeFailures() throws Exception {
        var board = new ProjectQueue(directory.resolve("production-retry.json"));
        board.submit("tools", WORLD, List.of(job("craft", CRAFT, List.of()), job("smelt", SMELT, List.of())));
        for (long now = 1; now <= 11; now += 2) {
            var lease = board.claim(WORLD, "worker", Set.of(CRAFT, SMELT), now).job();
            var reason = lease.work().kind() == CRAFT ? ProjectQueue.TARGET_TIMEOUT : "Recipe unavailable";
            assertTrue(board.finish("tools", lease.work().id(), lease.lease(), false, reason, now + 1));
        }
        assertEquals(1, board.retryInterruptedProduction(WORLD, "tools"));
        assertEquals("craft", board.claim(WORLD, "worker", Set.of(CRAFT, SMELT), 13).job().work().id());
        assertNull(board.claim(WORLD, "other", Set.of(SMELT), 13));
    }

    @Test void retriesPlacementAfterItsLastWorkerLeaseExpires() throws Exception {
        var journal = directory.resolve("placement-worker-lease.json");
        var queue = new ProjectQueue(journal);
        queue.submit("house", WORLD, List.of(job("place", BUILD, List.of())));
        long now = 1;
        for (int attempt = 0; attempt < 3; attempt++) {
            now = queue.claim(WORLD, "builder", Set.of(BUILD), now).job().expiresAt();
        }
        assertEquals(ProjectQueue.State.FAILED, queue.snapshot(now).get(0).jobs().get(0).state());
        assertEquals(1, queue.retryPlacementApproaches(WORLD, "house"));
        var restored = new ProjectQueue(journal);
        assertEquals("place", restored.claim(WORLD, "builder", Set.of(BUILD), now).job().work().id());
    }

    @Test void repairedFootingGatesStationAndReopensItsFailedConsumer() throws Exception {
        var journal = directory.resolve("station-footing.json");
        var queue = new ProjectQueue(journal);
        String table = "block:3,64,0";
        queue.submit("work", WORLD, List.of(job("logs", GATHER, List.of()),
                new ProjectQueue.Work("craft-table", CRAFT, "", List.of("logs"), Set.of(), "logs"),
                new ProjectQueue.Work("setup-workbench", BUILD,
                        "{\"target\":{\"x\":3,\"y\":64,\"z\":0},\"block\":\"minecraft:crafting_table\"}",
                        List.of("craft-table"), Set.of(table), "craft-table"),
                job("craft-chest", CRAFT, List.of("setup-workbench"), table)));
        long now = 1;
        for (var kind : List.of(GATHER, CRAFT, BUILD)) {
            var assigned = queue.claim(WORLD, "builder", Set.of(kind), now++).job();
            assertTrue(queue.finish("work", assigned.work().id(), assigned.lease(), true, "Done", now++));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            var assigned = queue.claim(WORLD, "crafter", Set.of(CRAFT), now++).job();
            assertTrue(queue.finish("work", "craft-chest", assigned.lease(), false, "Expected workstation is missing", now++));
        }
        var anchor = job("replace-footing", BUILD, List.of(), "block:3,63,0");
        assertTrue(queue.restoreOwnedStation(WORLD, "work", "setup-workbench", List.of(anchor)));
        var restored = new ProjectQueue(journal);
        for (var kind : List.of(GATHER, CRAFT)) {
            var assigned = restored.claim(WORLD, "builder", Set.of(kind), now++).job();
            assertTrue(restored.finish("work", assigned.work().id(), assigned.lease(), true, "Done", now++));
        }
        var repair = restored.claim(WORLD, "repairer", Set.of(BUILD), now++).job();
        assertEquals("replace-footing", repair.work().id());
        assertNull(restored.claim(WORLD, "builder", Set.of(BUILD), now++));
        assertNull(restored.claim(WORLD, "crafter", Set.of(CRAFT), now++));
        assertTrue(restored.finish("work", "replace-footing", repair.lease(), true, "Replaced", now++));
        var station = restored.claim(WORLD, "builder", Set.of(BUILD), now++).job();
        assertEquals("setup-workbench", station.work().id());
        assertTrue(restored.finish("work", "setup-workbench", station.lease(), true, "Replaced", now++));
        assertEquals("craft-chest", restored.claim(WORLD, "crafter", Set.of(CRAFT), now).job().work().id());
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

    @Test void timedOutConstructionPickupOffersOneRecoveryToAWorkerNearTheChest() throws Exception {
        var journal = directory.resolve("pickup-route-recovery.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        var pickup = new ProjectQueue.Work("pickup", TRANSFER,
                "{\"target\":{\"x\":3,\"y\":64,\"z\":0},\"direction\":\"TAKE\",\"item\":\"minecraft:oak_log\",\"stock\":1}",
                List.of("clear"), Set.of(chest), null, null, "minecraft:oak_log");
        var build = new ProjectQueue.Work("place-0", BUILD, "", List.of("pickup"), Set.of("placement"), "pickup");
        queue.submitSupplied("house", WORLD, List.of(job("clear", EXCAVATE, List.of()), pickup, build), chest);
        var clear = queue.claim(WORLD, "first", Set.of(EXCAVATE), 0).job();
        assertTrue(queue.finish("house", "clear", clear.lease(), true, "Cleared", 0));
        for (int now = 1; now < 7; now += 2) {
            var lease = queue.claim(WORLD, "first", Set.of(TRANSFER), now).job();
            assertTrue(queue.finish("house", "pickup", lease.lease(), false, ProjectQueue.TARGET_TIMEOUT, now + 1));
        }
        var nearby = new ProjectQueue.ResourceSite(1, 64, 0);
        var underground = new ProjectQueue.ResourceSite(1, 45, 0);
        var reopened = new ProjectQueue(journal);
        assertNull(reopened.claim(WORLD, "underground", Set.of(TRANSFER), null, 7, underground));
        assertNull(reopened.claim(WORLD, "builder", Set.of(BUILD), null, 7, nearby));
        var retry = reopened.claim(WORLD, "builder", Set.of(TRANSFER), null, 8, nearby).job();
        assertEquals(4, retry.attempts());
        assertNull(reopened.claim(WORLD, "other", Set.of(TRANSFER), null, 9, nearby));
        var recovered = new ProjectQueue(journal);
        assertNull(recovered.claim(WORLD, "underground", Set.of(TRANSFER), null, retry.expiresAt(), underground));
        var replacement = recovered.claim(WORLD, "other", Set.of(TRANSFER), null, retry.expiresAt(), nearby).job();
        assertTrue(recovered.finish("house", "pickup", replacement.lease(), false, ProjectQueue.TARGET_TIMEOUT,
                retry.expiresAt() + 1));
        assertNull(recovered.claim(WORLD, "builder", Set.of(TRANSFER), null, retry.expiresAt() + 2, nearby));
    }

    @Test void reopensExhaustedGatheringStallsThroughExistingRetry() throws Exception {
        var journal = directory.resolve("stalled-gather-retry.json");
        var queue = new ProjectQueue(journal);
        String logs = "{\"resource\":\"any_logs\",\"variant\":\"both\",\"item\":\"any_logs\"}";
        queue.submit("supplies", WORLD, List.of(
                new ProjectQueue.Work("previous", GATHER, logs, List.of(), Set.of()),
                new ProjectQueue.Work("collect", GATHER, logs, List.of("previous"), Set.of())));
        var oldSite = new ProjectQueue.ResourceSite(5, 68, -3);
        var previous = queue.claim(WORLD, "scout", Set.of(GATHER), 0).job();
        assertTrue(queue.finish("supplies", "previous", previous.lease(), true, "Collected", null, oldSite, 1));
        for (int attempt = 1; attempt <= 3; attempt++) {
            long now = 2L * attempt;
            var lease = queue.claim(WORLD, "worker" + attempt, Set.of(GATHER), now).job();
            assertEquals(attempt == 1 ? oldSite : null, lease.resourceSite());
            assertTrue(queue.finish("supplies", "collect", lease.lease(), false,
                    ProjectQueue.GATHER_STALLED, null, oldSite, now + 1));
        }
        assertEquals(ProjectQueue.State.FAILED, queue.snapshot(8).get(0).jobs().get(1).state());
        assertEquals(1, queue.retryInterruptedGathering(WORLD, "supplies"));
        var reopened = new ProjectQueue(journal);
        var retry = reopened.claim(WORLD, "replacement", Set.of(GATHER), 9).job();
        assertEquals(1, retry.attempts());
        assertNull(retry.resourceSite());
    }

    @Test void settledConstructionDepositsDoNotReserveTheOriginalBatchSizeForever() throws Exception {
        String chest = "block:3,64,0";
        String item = "minecraft:cobblestone";
        String station = "\"target\":{\"x\":3,\"y\":64,\"z\":0},";
        var deposit = new ProjectQueue.Work("deposit", TRANSFER,
                "{" + station + "\"direction\":\"DEPOSIT\",\"item\":\"minecraft:cobblestone\",\"stock\":38}",
                List.of(), Set.of(chest));
        var take = new ProjectQueue.Work("take", TRANSFER,
                "{" + station + "\"direction\":\"TAKE\",\"item\":\"minecraft:cobblestone\",\"stock\":4}",
                List.of("deposit"), Set.of(chest), null, null, item);
        var build = new ProjectQueue.Work("place", BUILD, "{}", List.of("take"), Set.of(), "take");
        for (var state : List.of(ProjectQueue.State.DONE, ProjectQueue.State.WAITING)) {
            var journal = directory.resolve("historical-construction-stock-" + state + ".json");
            var stock = new ProjectQueue.ChestObservation(new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 4)), 2);
            var supplier = new ProjectQueue.Project("structure", WORLD, List.of(
                    new ProjectQueue.Job(deposit, ProjectQueue.State.DONE, 1, "builder", null, 0, "Deposited", null),
                    new ProjectQueue.Job(take, ProjectQueue.State.DONE, 1, "builder", null, 0, "Took materials", stock),
                    new ProjectQueue.Job(build, state, 1, "builder", null, 0, "Placement", null)),
                    null, chest, java.util.Map.of());
            Files.writeString(journal, new com.google.gson.Gson().toJson(java.util.Map.of("schema", 1,
                    "projects", java.util.Map.of(supplier.id(), supplier))));
            var queue = new ProjectQueue(journal);
            var gather = new ProjectQueue.Work("gather", GATHER,
                    "{\"resource\":\"stone\",\"item\":\"minecraft:cobblestone\",\"variant\":\"both\",\"count\":3}",
                    List.of(), Set.of());
            var craft = new ProjectQueue.Work("craft", CRAFT, "{}", List.of("gather"), Set.of(), "gather");
            queue.submitSupplied("tools", WORLD, List.of(gather, craft), "block:4,64,0");
            var assigned = queue.claim(WORLD, "worker", Set.of(GATHER, CRAFT, TRANSFER), "tools", 3,
                    new ProjectQueue.ResourceSite(0, 64, 0), x -> true, (worker,itemId) -> 0, null, 0, true).job();
            assertEquals(state == ProjectQueue.State.DONE ? TRANSFER : GATHER, assigned.work().kind());
        }
    }

    @Test void nearbySurplusCanReplacePreparationGatheringWhileProtectingOutputAndFallingBack() throws Exception {
        var journal = directory.resolve("preparation-stock.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String item = "minecraft:cobblestone";
        String inspect = "{\"target\":{\"x\":3,\"y\":64,\"z\":0},\"direction\":\"INSPECT\"}";
        var output = new ProjectQueue.Work("target", TRANSFER,
                "{\"direction\":\"DEPOSIT\",\"item\":\"minecraft:cobblestone\",\"stock\":5}",
                List.of("inventory"), Set.of(chest));
        queue.submitSupplied("supplies", WORLD, List.of(
                new ProjectQueue.Work("inventory", TRANSFER, inspect, List.of(), Set.of(chest)), output), chest);
        var observationJob = queue.claim(WORLD, "observer", Set.of(TRANSFER), "supplies", 1).job();
        assertTrue(queue.finish("supplies", "inventory", observationJob.lease(), true, "Observed 8 cobblestone",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 8)), 2));
        var gather = new ProjectQueue.Work("gather", GATHER,
                "{\"resource\":\"stone\",\"item\":\"minecraft:cobblestone\",\"variant\":\"both\",\"count\":3}",
                List.of(), Set.of());
        var craft = new ProjectQueue.Work("craft", CRAFT, "{}", List.of("gather"), Set.of(), "gather");
        queue.submitSupplied("tools", WORLD, List.of(gather, craft), "block:4,64,0");
        var position = new ProjectQueue.ResourceSite(0, 64, 0);
        var kinds = Set.of(GATHER, CRAFT, TRANSFER);
        var pickup = queue.claim(WORLD, "worker", kinds, "tools", 3, position, x -> true, (w,i) -> 0, null, 0, true).job();
        assertEquals(TRANSFER, pickup.work().kind());
        assertNull(queue.claim(WORLD, "worker", kinds, "tools", 4));
        assertEquals(5, com.google.gson.JsonParser.parseString(pickup.work().payload())
                .getAsJsonObject().get("stockReserve").getAsInt());
        assertNull(queue.claim(WORLD, "observer", Set.of(TRANSFER), "supplies", 4));
        assertTrue(queue.finish("tools", "gather", pickup.lease(), false, "Insufficient unreserved cobblestone",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(item, 7)), 5));
        var mining = queue.claim(WORLD, "worker", kinds, "tools", 6, position, x -> true, (w,i) -> 0, null, 0, true).job();
        assertEquals(GATHER, mining.work().kind());
        assertEquals(1, mining.attempts());
    }

    @Test void carriedAndStockedInputsRecoverAStalledGatherWithoutBreakingCraftOwnership() throws Exception {
        checkGatheringStockRecovery(false);
    }

    @Test void gatheringStockRecoveryDoesNotSpendTheManifestMinimum() throws Exception {
        checkGatheringStockRecovery(true);
    }

    private void checkGatheringStockRecovery(boolean reserved) throws Exception {
        var journal = directory.resolve("recover-gather-from-chest.json");
        var queue = new ProjectQueue(journal);
        String chest = "block:3,64,0";
        String logs = "minecraft:oak_log";
        String inspection = "{\"target\":{\"x\":3,\"y\":64,\"z\":0},\"direction\":\"INSPECT\"}";
        String gathering = "{\"resource\":\"block:minecraft:oak_log\",\"item\":\"minecraft:oak_log\",\"variant\":\"both\",\"count\":32}";
        var work = new ArrayList<>(List.of(
                new ProjectQueue.Work("stock", TRANSFER, inspection, List.of(), Set.of(chest)),
                new ProjectQueue.Work("gather", GATHER, gathering, List.of("stock"), Set.of()),
                new ProjectQueue.Work("craft", CRAFT, "", List.of("gather"), Set.of(), "gather")));
        if (reserved) work.add(new ProjectQueue.Work("verify", TRANSFER,
                "{\"direction\":\"VERIFY_STOCK\",\"item\":\"any_logs\",\"stock\":5}",
                List.of("craft"), Set.of(chest)));
        queue.submitSupplied("house", WORLD, work, chest);
        var inspect = queue.claim(WORLD, "scout", Set.of(TRANSFER), 1).job();
        assertTrue(queue.finish("house", "stock", inspect.lease(), true, "Observed 12 logs",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(logs, 12)), 2));
        for (int now = 3; now <= 7; now += 2) {
            var lease = queue.claim(WORLD, "worker" + now, Set.of(GATHER), now).job();
            assertTrue(queue.finish("house", "gather", lease.lease(), false, ProjectQueue.GATHER_STALLED, now + 1));
        }
        var position = new ProjectQueue.ResourceSite(0, 64, 0);
        assertNull(queue.offerGatheringStock(WORLD, "helper", null, 9, position, (bot, item) -> 16));
        if (reserved) {
            assertNull(queue.offerGatheringStock(WORLD, "helper", null, 10, position, (bot, item) -> 24));
            return;
        }
        var pickup = queue.offerGatheringStock(WORLD, "helper", null, 10, position, (bot, item) -> 24).job();
        assertEquals("gather", pickup.work().id());
        assertEquals(TRANSFER, pickup.work().kind());
        assertEquals(Set.of(chest), pickup.work().locks());
        var restored = new ProjectQueue(journal);
        assertNull(restored.claim(WORLD, "other", Set.of(CRAFT), 11));
        pickup = restored.renew("house", "gather", pickup.lease(), 12);
        assertTrue(restored.finish("house", "gather", pickup.lease(), true, "Observed 32 logs",
                new ProjectQueue.ChestReport(chest, java.util.Map.of(logs, 4)), 13));
        assertEquals(GATHER, restored.snapshot(14).get(0).jobs().get(1).work().kind());
        assertEquals(4, restored.chestStock(WORLD).get(chest).report().items().get(logs));
        assertNull(restored.claim(WORLD, "other", Set.of(CRAFT), 14));
        assertEquals("craft", restored.claim(WORLD, "helper", Set.of(CRAFT), 15).job().work().id());
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

    @Test void shelteringSupplierRetainsMaterialsUntilItActuallyGoesOffline() throws Exception {
        var queue = new ProjectQueue(directory.resolve("sheltering-supplier.json"));
        queue.submitSupplied("house", WORLD, List.of(job("logs", GATHER, List.of()),
                new ProjectQueue.Work("planks", CRAFT, "", List.of("logs"), Set.of(), "logs")), "block:2,64,0");
        var logs = queue.claim(WORLD, "supplier", Set.of(GATHER), "house", 1).job();
        assertTrue(queue.finish("house", "logs", logs.lease(), true, "Collected logs", 2));
        for (long now = 10_000; now <= 90_000; now += 10_000) {
            queue.reportSafetyWait(WORLD, "supplier", "house", now);
            assertNull(queue.claim(WORLD, "helper", Set.of(GATHER, CRAFT), "house", now + 1));
        }
        assertTrue(queue.snapshot(90_001).get(0).supplyRecoveries().isEmpty());
        queue.reportSafetyWait(WORLD, "supplier", "elsewhere", 145_000);
        assertNull(queue.claim(WORLD, "helper", Set.of(GATHER, CRAFT), "house", 149_999));
        assertEquals("logs", queue.claim(WORLD, "helper", Set.of(GATHER), "house", 150_000).job().work().id());
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
