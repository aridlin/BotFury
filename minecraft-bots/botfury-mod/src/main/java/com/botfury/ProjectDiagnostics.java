package com.botfury;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Turns the persisted job graph into the steps that can move the project forward. */
final class ProjectDiagnostics {
    record Entry(ProjectQueue.Job job, String requirement, String worker, String next) {
        boolean interrupted() {
            return job.state() == ProjectQueue.State.WAITING && ProjectQueue.LEASE_EXPIRED.equals(job.evidence());
        }
    }

    private ProjectDiagnostics() {}

    static List<Entry> steps(ProjectQueue.Project project, int limit) {
        if (project.repeat() != null && project.repeat().cancelled()) return List.of();
        var jobs = new HashMap<String, ProjectQueue.Job>();
        var required = new HashSet<String>();
        for (var job : project.jobs()) {
            jobs.put(job.work().id(), job);
            if (job.state() == ProjectQueue.State.WAITING || job.state() == ProjectQueue.State.LEASED) {
                required.addAll(job.work().needs());
            }
        }
        var entries = new ArrayList<Entry>();
        for (var state : List.of(ProjectQueue.State.FAILED, ProjectQueue.State.LEASED, ProjectQueue.State.WAITING)) {
            for (var job : project.jobs()) {
                if (entries.size() == limit) return List.copyOf(entries);
                if (job.state() != state
                        || job.work().kind() == ProjectQueue.Kind.RESUPPLY && state == ProjectQueue.State.FAILED
                        && !required.contains(job.work().id())) continue;
                if (state == ProjectQueue.State.WAITING && job.work().needs().stream().anyMatch(id -> {
                    var dependency = jobs.get(id).state();
                    return dependency != ProjectQueue.State.DONE
                            && (job.work().kind() != ProjectQueue.Kind.SETTLEMENT_BARRIER
                                || dependency != ProjectQueue.State.FAILED);
                })) continue;
                var previous = jobs.get(job.work().sameWorkerAs());
                String worker = job.worker();
                if (worker == null && state == ProjectQueue.State.WAITING && previous != null) {
                    worker = previous.worker();
                }
                entries.add(new Entry(job, requirement(job.work()), worker, next(job, worker, jobs)));
            }
        }
        return List.copyOf(entries);
    }

    private static String next(ProjectQueue.Job job, String worker, Map<String, ProjectQueue.Job> jobs) {
        if (ProjectQueue.FURNACE_BATCH_STAGED.equals(job.evidence())) {
            if (job.state() == ProjectQueue.State.WAITING) return "An available headless bot can finish this furnace batch.";
            if (job.state() == ProjectQueue.State.LEASED) return "Let " + worker + " collect the full batch from the furnace.";
        }
        if ((job.state() == ProjectQueue.State.WAITING || job.state() == ProjectQueue.State.FAILED)
                && job.evidence() != null) {
            var evidence = job.evidence();
            if (ProjectQueue.LEASE_EXPIRED.equals(evidence)) {
                return worker == null ? "Check Fleet and keep a bot available for this order."
                        : "Check " + worker + " in Fleet. Keep another bot available for this order.";
            }
            if (evidence.startsWith("No reachable ") && evidence.contains(" while scouting loaded terrain")) {
                return "Move an available bot to a reachable source for this item.";
            }
            if (evidence.startsWith("Need ") && evidence.contains(" pickaxe or better to gather ")) {
                return "Equip an available bot with the required pickaxe.";
            }
        }
        return switch (job.state()) {
            case DONE -> "";
            case LEASED -> "Let " + worker + " finish this step.";
            case WAITING -> job.work().kind().coordinatorOnly() ? "The coordinator will settle this step."
                    : worker != null ? "Keep " + worker + " connected with the previous step’s supplies."
                    : "Assign available bots if none are working on this order.";
            case FAILED -> failureAction(job, jobs);
        };
    }

    private static String failureAction(ProjectQueue.Job job, Map<String, ProjectQueue.Job> jobs) {
        String evidence = job.evidence();
        if (evidence == null) return "Check Activity for the worker’s last report.";
        if (job.work().kind() == ProjectQueue.Kind.GATHER && evidence.startsWith(ProjectQueue.GATHER_TOOLS_NEEDED)) {
            String required = miningRecoveryEquipment(job, jobs);
            if (required != null) return "Equip a bot with " + required + ", then assign it to this order.";
        }
        if (evidence.startsWith(ProjectQueue.GATHER_TOOLS_NEEDED)
                || evidence.startsWith(ProjectQueue.EXCAVATION_TOOL_NEEDED)) {
            return "Equip an available bot with a suitable tool and check whether recovery resumes.";
        }
        if (evidence.equals(ProjectQueue.TARGET_TIMEOUT) || evidence.startsWith(ProjectQueue.PLACEMENT_REJECTED)) {
            return "Check the target and make sure a bot can reach it.";
        }
        if (evidence.startsWith("Missing furnace fuel")) return "Check fuel at the furnace.";
        if (evidence.startsWith("Missing furnace input") || evidence.startsWith("Missing ingredients")) {
            return "Check that the worker still has the required ingredients.";
        }
        if (evidence.startsWith("Delivery is short by")) return "Check the counted stock in Chests and the bot’s cargo.";
        if (ProjectQueue.GATHER_PICKUP_MISSED.equals(evidence)) return "Keep any bot carrying the drop available for this order.";
        return "Check Activity for details before changing the order.";
    }

    private static String miningRecoveryEquipment(ProjectQueue.Job job, Map<String, ProjectQueue.Job> jobs) {
        String resource = field(payload(job.work().payload()), "resource");
        boolean deep = resource != null && SubsurfaceAccess.deepResource(resource);
        for (var step = jobs.get(job.work().sameWorkerAs()); step != null;
             step = jobs.get(step.work().sameWorkerAs())) {
            String pickaxe = ProjectQueue.plannedPickaxe(step);
            if (pickaxe != null) {
                String tool = pickaxe.substring("minecraft:".length()).replace('_', ' ');
                String article = tool.startsWith("iron ") ? "an " : "a ";
                return article + tool + " or better" + (deep ? " and spare stone pickaxes for tunneling" : "");
            }
        }
        return null;
    }

    private static String requirement(ProjectQueue.Work work) {
        var payload = payload(work.payload());
        String count = field(payload, "count");
        String item = field(payload, "item");
        String target = coordinates(payload, "target");
        String title = switch (work.kind()) {
            case GATHER -> describe("Gather", count, item == null ? field(payload, "resource") : item);
            case CRAFT -> describe("Craft", count, field(payload, "recipe"));
            case SMELT -> describe("Smelt", count, field(payload, "input"));
            case TRANSFER -> switch (String.valueOf(field(payload, "direction"))) {
                case "TAKE" -> describe("Take", field(payload, "stock"), item);
                case "DEPOSIT" -> field(payload, "stock") == null ? describe("Deposit", count, item)
                        : describe("Stock to", field(payload, "stock"), item);
                case "VERIFY_STOCK" -> describe("Verify stock", field(payload, "stock"), item);
                default -> describe("Transfer", count, item);
            };
            case BUILD -> describe("Place", null, field(payload, "block"));
            case EXCAVATE -> work.cleanup() == null ? "Excavate" : "Remove temporary support";
            case VERIFY -> describe("Verify", null, field(payload, "block"));
            case INSPECT -> "Inspect build cells";
            case FILL_BUCKETS -> describe("Fill buckets", count, item);
            case BARRIER -> "Confirm prerequisites";
            case SETTLEMENT_BARRIER -> "Settle completed work";
            case RESUPPLY -> describe("Resupply", count, item);
        };
        return target == null ? title : title + " at " + target;
    }

    private static JsonObject payload(String source) {
        try {
            var json = JsonParser.parseString(source);
            return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
        } catch (com.google.gson.JsonParseException invalid) {
            return new JsonObject();
        }
    }

    private static String field(JsonObject json, String key) {
        var value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String coordinates(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonObject()) return null;
        var pos = value.getAsJsonObject();
        String x = field(pos, "x"), y = field(pos, "y"), z = field(pos, "z");
        return x == null || y == null || z == null ? null : x + ", " + y + ", " + z;
    }

    private static String describe(String action, String count, String item) {
        return action + (count == null ? "" : " " + count + " ×")
                + (item == null ? "" : " " + item.replaceFirst("^minecraft:", "").replace('_', ' '));
    }
}
