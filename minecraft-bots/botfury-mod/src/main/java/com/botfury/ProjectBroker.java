package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Local coordinator API. Workers must stop acting when their lease can no longer be renewed. */
final class ProjectBroker {
    private static final Gson JSON = new Gson();
    private static final int MAX_BODY = 512 * 1024;
    private static ProjectQueue queue;

    private record Request(String action, String project, String worker, Set<ProjectQueue.Kind> kinds,
                           List<ProjectQueue.Work> work, String job, UUID token, Boolean success,
                           String evidence, ProjectQueue.ChestReport chest, ProjectQueue.ResourceSite resourceSite, boolean pickupShortfall) {}

    private static synchronized ProjectQueue queue() throws IOException {
        if (queue == null) {
            queue = new ProjectQueue(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("botfury-projects.json"));
        }
        return queue;
    }

    static void submitStructure(String id, LocalFormation.Peer here, StructureProject.Plan plan,
                                StructureSupply.Supplied supply) throws IOException {
        String world = here.server() + "|" + here.dimension();
        if (supply == null) queue().submit(id, world, plan.jobs());
        else queue().submitSupplied(id, world, supply.jobs(), supply.chest());
    }

    static void submitOutput(String id, LocalFormation.Peer here, OutputOrder.Plan plan) throws IOException {
        queue().submitOutput(id, here.server() + "|" + here.dimension(), plan.jobs(), plan.repeat());
    }

    static boolean cancelOutput(LocalFormation.Peer here, String chest) throws IOException {
        return queue().cancelOutput(here.server() + "|" + here.dimension(), chest);
    }

    static void handle(HttpExchange exchange) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                || host == null || !host.matches("(127\\.0\\.0\\.1|localhost)(:[0-9]+)?")
                || exchange.getRequestHeaders().containsKey("Origin")) {
            reply(exchange, 403, "Local project clients only");
            return;
        }
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                if ("summary".equals(query)) {
                    var board = queue();
                    var details = summarize(board.snapshot(System.currentTimeMillis()));
                    int archived = board.history().size();
                    text(exchange, archived == 0 ? details : details + "\nArchived projects: " + archived);
                    return;
                }
                if ("history".equals(query)) {
                    reply(exchange, 200, queue().history());
                    return;
                }
                if (query != null && query.startsWith("history=")) {
                    var archived = queue().archived(query.substring("history=".length()));
                    if (archived == null) reply(exchange, 404, "Archived project not found");
                    else reply(exchange, 200, archived);
                    return;
                }
                if ("stock".equals(query) || "stock-summary".equals(query)) {
                    var here = LocalFormation.here();
                    if (here == null) {
                        reply(exchange, 409, "Join the project world first");
                        return;
                    }
                    var stock = queue().chestStock(here.server() + "|" + here.dimension());
                    if ("stock-summary".equals(query)) {
                        var lines = new java.util.ArrayList<String>();
                        stock.forEach((chest, observation) -> lines.add(chest + " " + observation.report().items()
                                + " (observed " + java.time.Instant.ofEpochMilli(observation.observedAt()) + ")"));
                        text(exchange, lines.isEmpty() ? "No observed chest stock" : String.join("\n", lines));
                    } else reply(exchange, 200, stock);
                } else reply(exchange, 200, queue().snapshot(System.currentTimeMillis()));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                reply(exchange, 405, "Use GET or POST");
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                reply(exchange, 413, "Project request is too large");
                return;
            }
            Request request = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Request.class);
            if (request == null || request.action == null) {
                reply(exchange, 400, "Missing project action");
                return;
            }
            var here = LocalFormation.here();
            if (here == null) {
                reply(exchange, 409, "Join the project world first");
                return;
            }
            String world = here.server() + "|" + here.dimension();
            ProjectQueue board = queue();
            long now = System.currentTimeMillis();
            switch (request.action) {
                case "submit" -> {
                    if (request.work == null) throw new IllegalArgumentException("Missing project jobs");
                    var work = request.work.stream().map(item -> {
                        if (item == null) throw new IllegalArgumentException("Missing project job");
                        if (item.kind() == ProjectQueue.Kind.GATHER) {
                            ProjectGather.parse(item.payload());
                            return item;
                        }
                        if (item.kind() == ProjectQueue.Kind.INSPECT) {
                            return ProjectInspection.parse(item.payload()).reserve(item);
                        }
                        if (item.kind() == ProjectQueue.Kind.TRANSFER) {
                            return ProjectChest.parse(item.payload()).reserve(item);
                        }
                        if (item.kind() == ProjectQueue.Kind.CRAFT || item.kind() == ProjectQueue.Kind.SMELT) {
                            return ProjectProduction.parse(item.payload(), item.kind()).station().reserve(item);
                        }
                        return ProjectWorker.supports(item.kind()) ? ProjectBlock.parse(item.payload()).reserve(item) : item;
                    }).toList();
                    board.submit(request.project, world, work);
                    reply(exchange, 201, request.project);
                }
                case "claim" -> reply(exchange, 200, board.claim(world, request.worker, request.kinds, now));
                case "renew" -> {
                    var renewed = board.renew(request.project, request.job, request.token, request.resourceSite, now);
                    reply(exchange, renewed == null ? 409 : 200, renewed);
                }
                case "finish" -> {
                    if (request.success == null) throw new IllegalArgumentException("Report a success or failure");
                    boolean accepted = board.finish(request.project, request.job, request.token,
                            request.success, request.evidence, request.chest, request.resourceSite, request.pickupShortfall, now);
                    reply(exchange, accepted ? 200 : 409, accepted);
                }
                default -> reply(exchange, 400, "Unknown project action");
            }
        } catch (JsonParseException | IllegalArgumentException error) {
            reply(exchange, 400, error instanceof JsonParseException ? "Invalid project request" : error.getMessage());
        } catch (IOException error) {
            BotEvents.add("warn", "projects", "Could not read or save the project journal");
            reply(exchange, 503, "Project journal unavailable");
        }
    }

    private static String summarize(List<ProjectQueue.Project> projects) {
        var lines = new java.util.ArrayList<String>();
        for (var project : projects) {
            long done = project.jobs().stream().filter(job -> job.state() == ProjectQueue.State.DONE).count();
            var repeat = project.repeat();
            String label = project.id() + " " + done + "/" + project.jobs().size() + " done";
            if (repeat != null) label += ", " + repeat.completed() + " batches" + (repeat.cancelled() ? " (cancelled)" : "");
            lines.add(label);
            for (var job : project.jobs()) {
                if (job.state() == ProjectQueue.State.LEASED) lines.add("  " + job.worker() + ": " + job.work().id());
                if (job.state() == ProjectQueue.State.FAILED) lines.add("  Failed " + job.work().id() + ": " + job.evidence());
            }
        }
        return lines.isEmpty() ? "No queued projects" : String.join("\n", lines);
    }

    private static void text(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "text/plain; charset=utf-8", body);
    }

    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json", JSON.toJson(body));
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
