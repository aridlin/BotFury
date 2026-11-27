package com.botfury;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.botfury.ProjectQueue.Kind.*;
import static com.botfury.ProjectQueue.State.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectDiagnosticsTest {
    @Test void showsTheFailedSupplyStepBeforeTheBlockedBuildAndIgnoresSettledResupply() {
        var previousResupply = job("route-tool", RESUPPLY, "{}", List.of(), null, FAILED, null, "Worker lease expired");
        var gathered = job("gather", GATHER, "{}", List.of(), null, DONE, "Miner", null);
        var deposit = job("deposit", TRANSFER, """
                {"direction":"DEPOSIT","item":"minecraft:diamond","stock":39,"target":{"x":-3,"y":72,"z":4}}
                """, List.of("gather"), "gather", FAILED, null, "Delivery is short by 2 minecraft:diamond");
        var build = job("build", BUILD, "{}", List.of("deposit"), null, WAITING, null, null);
        var active = job("mine", GATHER, """
                {"resource":"iron","item":"minecraft:raw_iron","count":3}
                """, List.of(), null, LEASED, "Helper", null);
        var project = new ProjectQueue.Project("order", "world", List.of(previousResupply, gathered, deposit, build, active),
                null, null, Map.of());

        var steps = ProjectDiagnostics.steps(project, 8);
        assertEquals(List.of(deposit, active), steps.stream().map(ProjectDiagnostics.Entry::job).toList());
        assertEquals("Stock to 39 × diamond at -3, 72, 4", steps.get(0).requirement());
        assertNull(steps.get(0).worker());
        assertTrue(steps.get(0).next().contains("counted stock"));
        assertEquals("Helper", steps.get(1).worker());
        assertEquals("Gather 3 × raw iron", steps.get(1).requirement());
    }

    @Test void readyCraftNamesTheWorkerWhoPreparedItsIngredients() {
        var logs = job("logs", GATHER, "{}", List.of(), null, DONE, "Builder", null);
        var planks = job("planks", CRAFT, "{\"recipe\":\"minecraft:oak_planks\",\"count\":16}",
                List.of("logs"), "logs", WAITING, null, null);
        var project = new ProjectQueue.Project("order", "world", List.of(logs, planks), null, null, Map.of());
        var step = ProjectDiagnostics.steps(project, 8).get(0);
        assertEquals("Builder", step.worker());
        assertEquals("Craft 16 × oak planks", step.requirement());
        assertTrue(step.next().contains("previous step’s supplies"));
    }

    @Test void readyNativeGatherShowsAnActionForTheObservedBlocker() {
        var logs = job("logs", GATHER, "{\"resource\":\"any_logs\",\"item\":\"any_logs\",\"count\":8}",
                List.of(), null, WAITING, null,
                "No reachable any_logs found while scouting loaded terrain (2/8 carried)");
        var diamond = job("diamond", GATHER, "{\"resource\":\"diamond\",\"item\":\"minecraft:diamond\",\"count\":8}",
                List.of(), null, WAITING, null,
                "Need an iron pickaxe or better to gather diamond");
        var project = new ProjectQueue.Project("order", "world", List.of(logs, diamond), null, null, Map.of());
        var steps = ProjectDiagnostics.steps(project, 8);
        assertTrue(steps.stream().anyMatch(step -> step.job() == logs && step.next().contains("reachable source")));
        assertTrue(steps.stream().anyMatch(step -> step.job() == diamond && step.next().contains("required pickaxe")));
    }

    private static ProjectQueue.Job job(String id, ProjectQueue.Kind kind, String payload, List<String> needs,
                                        String previous, ProjectQueue.State state, String worker, String evidence) {
        var work = new ProjectQueue.Work(id, kind, payload, needs, Set.of(), previous);
        return new ProjectQueue.Job(work, state, 1, worker, null, 0, evidence, null);
    }
}
