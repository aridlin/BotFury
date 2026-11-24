package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local coordinator API. Workers must stop acting when their lease can no longer be renewed. */
final class ProjectBroker {
    private static final Gson JSON = new Gson();
    private static final int MAX_BODY = 512 * 1024;
    private static ProjectQueue queue;
    private static long nextStationCheck;
    private static int ownedPlacementCursor;
    record Overview(List<ProjectQueue.Project> projects, Map<String, ProjectQueue.ChestObservation> stock) {}

    private record Request(String action, String project, String worker, Set<ProjectQueue.Kind> kinds,
                           List<ProjectQueue.Work> work, String job, UUID token, Boolean success,
                           String evidence, ProjectQueue.ChestReport chest, ProjectQueue.ResourceSite resourceSite,
                           boolean pickupShortfall, Map<String, Integer> toolUses) {}

    private static synchronized ProjectQueue queue() throws IOException {
        if (queue == null) {
            queue = new ProjectQueue(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("botfury-projects.json"));
        }
        return queue;
    }

    static Overview overview(String world) throws IOException {
        var board = queue();
        var projects = board.snapshot(System.currentTimeMillis()).stream()
                .filter(project -> project.world().equals(world)).toList();
        return new Overview(projects, board.chestStock(world));
    }

    static void submitStructure(String id, LocalFormation.Peer here, StructureProject.Plan plan,
                                StructureSupply.Supplied supply) throws IOException {
        String world = here.server() + "|" + here.dimension();
        boolean mutatesWorld = plan.jobs().stream().anyMatch(job ->
                job.kind() == ProjectQueue.Kind.BUILD || job.kind() == ProjectQueue.Kind.EXCAVATE);
        if (mutatesWorld) BuildProtection.reserve(world, id, plan.footprint());
        try {
            if (supply == null) queue().submit(id, world, plan.jobs());
            else queue().submitSupplied(id, world, supply.jobs(), supply.chest());
        } catch (IOException | RuntimeException failure) {
            if (mutatesWorld) {
                try { BuildProtection.remove(world, id); }
                catch (IOException | RuntimeException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    static void submitManifest(String id, LocalFormation.Peer here, List<ProjectQueue.Work> jobs, String chest) throws IOException {
        queue().submitSupplied(id, here.server() + "|" + here.dimension(), jobs, chest);
    }

    static void checkOwnedWork(net.minecraft.client.MinecraftClient client) {
        if (Boolean.getBoolean("botfury.managedHttp") || client.world == null || client.player == null) return;
        long now = System.nanoTime();
        if (nextStationCheck != 0 && now < nextStationCheck) return;
        nextStationCheck = now + java.time.Duration.ofSeconds(5).toNanos();
        var here = LocalFormation.here();
        if (here == null) return;
        String world = here.server() + "|" + here.dimension();
        try {
            var board = queue();
            for (var station : board.ownedStations(world)) {
                var block = ProjectBlock.parse(station.payload());
                var target = block.target();
                if (!client.world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)
                        || !client.world.isAir(target)) continue;
                if (BuildProtection.blocked(world, target, station.project())) continue;
                var footing = ProjectWorkstations.repairFooting(client, world, station, block);
                if (footing == null) continue;
                if (board.restoreOwnedStation(world, station.project(), station.job(), footing)) {
                    BotEvents.add("info", "projects", "Rebuilding missing station at " + target.toShortString());
                }
            }
            checkOwnedPlacements(client, board, world);
        } catch (IOException | IllegalArgumentException failure) {
            BotEvents.add("warn", "projects", "Could not check owned stations. " + failure.getMessage());
        }
    }

    private static void checkOwnedPlacements(net.minecraft.client.MinecraftClient client, ProjectQueue board, String world)
            throws IOException {
        var placements = board.ownedPlacements(world);
        if (placements.isEmpty()) return;
        int repaired = 0;
        int limit = Math.min(128, placements.size());
        for (int checked = 0; checked < limit; checked++) {
            ownedPlacementCursor = Math.floorMod(ownedPlacementCursor, placements.size());
            var placement = placements.get(ownedPlacementCursor++);
            var block = ProjectBlock.parse(placement.payload());
            var target = block.target();
            if (!client.world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)
                    || !client.world.isAir(target) || BuildProtection.blocked(world, target, placement.project())) continue;
            var support = block.support();
            if (!ProjectPlacement.support(client.world.getBlockState(support), block.desired(), block.face(), client.world, support)) continue;
            if (board.repairMissingPlacement(world, placement.project(), placement.job())) {
                BotEvents.add("info", "projects", "Rebuilding missing schematic block at " + target.toShortString());
                if (++repaired >= 4) return;
            }
        }
    }

    static int retryExcavation(net.minecraft.client.MinecraftClient client, String project) {
        var here = LocalFormation.here();
        if (here == null || Boolean.getBoolean("botfury.managedHttp")) return 0;
        try {
            int count = queue().retryExcavation(here.server() + "|" + here.dimension(), project);
            ClientActions.message(client, "Reopened " + count + " failed excavation jobs");
            return count;
        } catch (IOException | IllegalArgumentException failure) {
            ClientActions.message(client, "Could not retry excavation. " + failure.getMessage());
            return 0;
        }
    }

    static int retryDeliveries(net.minecraft.client.MinecraftClient client, String project) {
        var here = LocalFormation.here();
        if (here == null || Boolean.getBoolean("botfury.managedHttp")) return 0;
        try {
            int count = queue().retryDeliveries(here.server() + "|" + here.dimension(), project);
            ClientActions.message(client, "Reopened " + count + " failed delivery jobs");
            return count;
        } catch (IOException | IllegalArgumentException failure) {
            ClientActions.message(client, "Could not retry delivery. " + failure.getMessage());
            return 0;
        }
    }

    static int retryInterruptedGathering(net.minecraft.client.MinecraftClient client, String project) {
        var here = LocalFormation.here();
        if (here == null || Boolean.getBoolean("botfury.managedHttp")) return 0;
        try {
            int count = queue().retryInterruptedGathering(here.server() + "|" + here.dimension(), project);
            ClientActions.message(client, "Reopened " + count + " interrupted gathering jobs");
            return count;
        } catch (IOException | IllegalArgumentException failure) {
            ClientActions.message(client, "Could not retry gathering. " + failure.getMessage());
            return 0;
        }
    }

    static int retryPlacementApproaches(net.minecraft.client.MinecraftClient client, String project) {
        var here = LocalFormation.here();
        if (here == null || Boolean.getBoolean("botfury.managedHttp")) return 0;
        try {
            int count = queue().retryPlacementApproaches(here.server() + "|" + here.dimension(), project);
            ClientActions.message(client, "Reopened " + count + " failed placement approaches");
            return count;
        } catch (IOException | IllegalArgumentException failure) {
            ClientActions.message(client, "Could not retry placement approaches. " + failure.getMessage());
            return 0;
        }
    }

    static int retryInterruptedProduction(net.minecraft.client.MinecraftClient client, String project) {
        var here = LocalFormation.here();
        if (here == null || Boolean.getBoolean("botfury.managedHttp")) return 0;
        try {
            int count = queue().retryInterruptedProduction(here.server() + "|" + here.dimension(), project);
            ClientActions.message(client, "Reopened " + count + " interrupted production jobs");
            return count;
        } catch (IOException | IllegalArgumentException failure) {
            ClientActions.message(client, "Could not retry production. " + failure.getMessage());
            return 0;
        }
    }

    static void submitOutput(String id, LocalFormation.Peer here, OutputOrder.Plan plan) throws IOException {
        queue().submitOutput(id, here.server() + "|" + here.dimension(), plan.jobs(), plan.repeat());
    }

    static boolean cancelOutput(LocalFormation.Peer here, String chest) throws IOException {
        return queue().cancelOutput(here.server() + "|" + here.dimension(), chest);
    }

    static boolean cancelOutput(LocalFormation.Peer here, ProjectQueue.Project project) throws IOException {
        return queue().cancelOutput(here.server() + "|" + here.dimension(), project.repeat().chest(), project.id());
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
                        if (item.kind() == ProjectQueue.Kind.FILL_BUCKETS) {
                            return ProjectWater.Fill.parse(item.payload()).reserve(item);
                        }
                        if (item.kind() == ProjectQueue.Kind.INSPECT) {
                            return ProjectInspection.parse(item.payload()).reserve(item);
                        }
                        if (item.kind() == ProjectQueue.Kind.TRANSFER) {
                            return ProjectChest.parse(item.payload()).reserve(item);
                        }
                        if (item.kind() == ProjectQueue.Kind.CRAFT || item.kind() == ProjectQueue.Kind.SMELT) {
                            var station = ProjectProduction.parse(item.payload(), item.kind()).station();
                            return station == null ? item : station.reserve(item);
                        }
                        return ProjectWorker.supports(item.kind()) ? ProjectBlock.parse(item.payload()).reserve(item) : item;
                    }).toList();
                    board.submit(request.project, world, work);
                    reply(exchange, 201, request.project);
                }
                case "claim" -> {
                    var assignment = board.claim(world, request.worker, request.kinds, request.project, now);
                    if (assignment == null && request.kinds.contains(ProjectQueue.Kind.GATHER)) {
                        assignment = board.offerEquippedMiner(world, request.worker, request.project, request.toolUses, now);
                        if (assignment == null) {
                            assignment = board.offerGatheringHandoff(world, request.worker, request.project, now, ProjectBroker::heldCount);
                        }
                    }
                    if (assignment == null && request.kinds.contains(ProjectQueue.Kind.BUILD)
                            && board.offerConstructionHandoff(world, request.worker, request.project,
                                    request.kinds, now, ProjectBroker::workerPosition, ProjectBroker::heldCount)) {
                        assignment = board.claim(world, request.worker, request.kinds, request.project, now);
                    }
                    reply(exchange, 200, assignment);
                }
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

    private static int heldCount(String worker, String item) {
        var bot = liveWorker(worker);
        if (bot == null || bot.items() == null) return -1;
        return ProjectItem.parse(item).count(bot.items());
    }

    private static ProjectQueue.ResourceSite workerPosition(String worker) {
        var bot = liveWorker(worker);
        return bot == null ? null : new ProjectQueue.ResourceSite(
                (int)Math.floor(bot.x()), (int)Math.floor(bot.y()), (int)Math.floor(bot.z()));
    }

    private static BotTracker.Bot liveWorker(String worker) {
        var bot = BotTracker.trackedPlayer(worker);
        return bot != null && bot.fresh() && bot.connected() && bot.alive() ? bot : null;
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
