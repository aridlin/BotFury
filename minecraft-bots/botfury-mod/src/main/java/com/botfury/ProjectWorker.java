package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One opt-in job loop per managed client. All Minecraft actions run on its client thread. */
final class ProjectWorker {
    private static final Gson JSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(750)).build();
    private static final Set<ProjectQueue.Kind> KINDS = Set.of(ProjectQueue.Kind.EXCAVATE, ProjectQueue.Kind.VERIFY, ProjectQueue.Kind.TRANSFER, ProjectQueue.Kind.BUILD, ProjectQueue.Kind.CRAFT, ProjectQueue.Kind.SMELT, ProjectQueue.Kind.GATHER);
    private static WorkerEnrollment enrollment;
    private static boolean enrollmentLoaded;
    private static int port;
    private static int generation;
    private static boolean pending;
    private static ProjectQueue.Assignment assignment;
    private static ProjectBlock block;
    private static ProjectChest chest;
    private static ProjectProduction production;
    private static ProjectGather gather;
    private static ProjectQueue.ResourceSite gatherSite;
    private static DroppedItemSearch pickup;
    private static ProjectQueue.ChestReport chestReport;
    private static boolean pickupShortfall;
    private static BaritoneSettings approach;
    private static final StallRecovery approachRecovery = new StallRecovery();
    private static Vec3d approachProgress;
    private static long retryApproach;
    private static long nextRequest, deadline, taskEnd, stableSince, nextPlacement;
    private static Boolean success;
    private static String evidence;
    private static boolean breaking;
    private static String status = "";

    static boolean enabled() { return port != 0; }
    static String status() { return status; }
    static boolean supports(ProjectQueue.Kind kind) { return KINDS.contains(kind); }

    static void start(MinecraftClient client, int coordinator) {
        var here = LocalFormation.snapshot(client);
        if (here == null || coordinator == BotFuryMod.controlPort()) {
            BotEvents.add("warn", "projects", "Join the project world before enabling the worker");
            return;
        }
        WorkerEnrollment next;
        try {
            next = new WorkerEnrollment(here.name(), world(here), coordinator);
            if (next.equals(enrollment) && enabled()) return;
            WorkerEnrollment.save(enrollmentPath(), next);
        } catch (IOException | IllegalArgumentException error) {
            BotEvents.add("warn", "projects", "Could not save project worker enrollment");
            return;
        }
        enrollment = next;
        enrollmentLoaded = true;
        attach(client, "Project worker enabled");
    }

    static void restore(MinecraftClient client, LocalFormation.Peer here) {
        if (enabled() || !Boolean.getBoolean("botfury.managedHttp")) return;
        loadEnrollment();
        if (enrollment == null || enrollment.port() == BotFuryMod.controlPort()) return;
        if (enrollment.matches(here.name(), world(here))) {
            if (BaritoneClient.available()) attach(client, "Restored project worker enrollment");
        } else {
            status = "Waiting for enrolled project world";
        }
    }

    private static void attach(MinecraftClient client, String event) {
        suspend(client);
        LocalFormation.clearMovement();
        BotInventory.hold();
        port = enrollment.port();
        status = "Waiting for project jobs";
        BotEvents.add("info", "projects", event);
    }

    static void suspend(MinecraftClient client) {
        generation++;
        port = 0;
        reset(client);
        status = "";
    }

    static void stop(MinecraftClient client) {
        suspend(client);
        if (!Boolean.getBoolean("botfury.managedHttp")) return;
        loadEnrollment();
        enrollment = null;
        try {
            Files.deleteIfExists(enrollmentPath());
        } catch (IOException error) {
            try {
                WorkerEnrollment.save(enrollmentPath(), null);
            } catch (IOException writeError) {
                BotEvents.add("warn", "projects", "Could not clear saved worker enrollment");
            }
        }
    }

    private static void loadEnrollment() {
        if (enrollmentLoaded) return;
        enrollmentLoaded = true;
        try {
            enrollment = WorkerEnrollment.read(enrollmentPath());
        } catch (IOException error) {
            BotEvents.add("warn", "projects", "Could not read saved worker enrollment");
        }
    }

    private static Path enrollmentPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("botfury-worker.json");
    }

    static void interrupt(MinecraftClient client) {
        if (assignment == null && !pending) return;
        generation++;
        reset(client);
        status = "Waiting to respawn before claiming project work";
    }

    private static void reset(MinecraftClient client) {
        halt(client);
        assignment = null;
        block = null;
        chest = null;
        production = null;
        gather = null;
        gatherSite = null;
        pickup = null;
        chestReport = null;
        pickupShortfall = false;
        success = null;
        evidence = null;
        pending = false;
        nextRequest = deadline = taskEnd = stableSince = nextPlacement = 0;
    }

    private static void halt(MinecraftClient client) {
        approachRecovery.reset();
        if (chest != null) chest.close(client);
        if (production != null) production.close(client);
        if (pickup != null) pickup.stop();
        if (gather != null && MiningTask.active()) MiningTask.stop();
        if (breaking && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        breaking = false;
        if (approach != null) {
            BaritoneClient.execute("stop");
            approach.close();
            approach = null;
            approachProgress = null;
        }
    }

    static void tick(MinecraftClient client, LocalFormation.Peer here) {
        if (!enabled()) return;
        long now = System.nanoTime();
        if (assignment == null) {
            if (!pending && now >= nextRequest) request(client, here, "claim", now);
            return;
        }
        if (!assignment.world().equals(world(here)) || now >= deadline) {
            generation++;
            reset(client);
            nextRequest = now + seconds(1);
            status = "Project lease lost, waiting for a new assignment";
            return;
        }
        if (!pending && now >= nextRequest) request(client, here, success == null ? "renew" : "finish", now);
        if (success != null) return;
        if (now >= taskEnd) {
            finish(client, false, "Timed out approaching or changing the target");
            return;
        }
        if (gather != null) {
            int held = gather.inventoryCount(client);
            if (held >= gather.count()) {
                finish(client, true, "Observed " + held + " " + gather.item() + " in worker inventory");
            } else if (pickup != null) {
                var result = pickup.tick(client, Registries.ITEM.get(gather.item()));
                if (result == DroppedItemSearch.State.UNAVAILABLE) finish(client, false, "Baritone unavailable for pickup");
                else status = (result == DroppedItemSearch.State.SEARCHING ? "Looking for dropped " : "Collecting ")
                        + gather.item() + " (" + held + "/" + gather.count() + ")";
            } else if (!MiningTask.active()) {
                MiningTask.startProject(client, gather.mining(port), gather.exclude(), gatherSite);
                if (!MiningTask.active()) finish(client, false, MiningTask.lastStatus());
            } else {
                status = assignment.project() + "/" + assignment.job().work().id() + " (" + held + "/" + gather.count()
                        + "): " + MiningTask.status();
                MiningTask.tick(client);
                var observation = MiningTask.observedSite();
                if (observation != null) gatherSite = observation;
            }
        } else execute(client, now);
    }

    private static void execute(MinecraftClient client, long now) {
        var player = client.player;
        BlockPos target = block.target();
        if (!client.world.getWorldBorder().contains(target) || !client.world.getWorldBorder().contains(block.stand())) {
            finish(client, false, "Project block or stand outside world border");
            return;
        }
        Vec3d feet = Vec3d.ofBottomCenter(block.stand());
        if (player.getPos().squaredDistanceTo(feet) > 1.21
                || assignment.job().work().kind() == ProjectQueue.Kind.EXCAVATE && !player.getBlockPos().equals(block.stand())) {
            stableSince = 0;
            var recovery = approachRecovery.tick(client, block.stand(),
                    pos -> !pos.equals(target) && !pos.up().equals(target));
            if (recovery == StallRecovery.State.DETOUR) {
                status = assignment.project() + "/" + assignment.job().work().id() + ": Clearing a stalled route";
                return;
            }
            if (recovery == StallRecovery.State.REPLAN) {
                retryApproach = 0;
                status = assignment.project() + "/" + assignment.job().work().id() + ": Retrying approach";
            }
            Vec3d current = player.getPos();
            if (approachProgress != null && current.squaredDistanceTo(approachProgress) >= 1) {
                approachProgress = current;
                retryApproach = now + seconds(5);
            }
            if (approach == null || now >= retryApproach) {
                try {
                    if (approach == null) approach = new BaritoneSettings(false);
                    approachProgress = current;
                    retryApproach = now + seconds(5);
                    BlockPos pos = block.stand();
                    if (!BaritoneClient.execute("goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ())) {
                        finish(client, false, "Baritone could not start project approach");
                    }
                } catch (ReflectiveOperationException error) {
                    finish(client, false, "Baritone unavailable for project approach");
                }
            }
            return;
        }
        if (approach != null) halt(client);
        if (!client.world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) return;
        if (chest != null) {
            var result = chest.tick(client, now);
            if (result != null) {
                pickupShortfall = result.pickupShortfall();
                if (result.stock() != null) chestReport = new ProjectQueue.ChestReport(ProjectBlock.key(target), result.stock(), result.free());
                finish(client, result.success(), result.detail());
            }
            return;
        }
        if (production != null) {
            var result = production.tick(client, now);
            if (result != null) finish(client, result.success(), result.detail());
            return;
        }
        var state = client.world.getBlockState(target);
        var actual = Registries.BLOCK.getId(state.getBlock());
        var kind = assignment.job().work().kind();
        boolean excavate = kind == ProjectQueue.Kind.EXCAVATE;
        boolean matches = excavate ? state.isAir() : block.matches(state);
        if (matches) {
            halt(client);
            if (stableSince == 0) stableSince = now;
            if (now - stableSince >= seconds(1)) finish(client, true, "Observed " + actual + (block.properties().isEmpty() ? "" : ProjectBlock.properties(state))
                    + " at " + target.toShortString());
            return;
        }
        stableSince = 0;
        if (kind == ProjectQueue.Kind.BUILD) {
            if (now >= nextPlacement) {
                var failure = ProjectPlacement.attempt(client, block);
                nextPlacement = now + seconds(1);
                if (failure != null) finish(client, false, failure);
            }
            return;
        }
        if (!excavate || !block.matches(state) || state.hasBlockEntity()
                || state.getHardness(client.world, target) < 0) {
            finish(client, false, "Unexpected or protected block " + actual + " at " + target.toShortString());
            return;
        }
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        var ray = client.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        double reach = client.interactionManager.getReachDistance() - .1;
        if (eye.squaredDistanceTo(center) > reach * reach || ray.getType() != HitResult.Type.BLOCK
                || !ray.getBlockPos().equals(target)) {
            finish(client, false, "Target obstructed or out of reach from the approved stand");
            return;
        }
        if (state.isToolRequired() && !player.getMainHandStack().isSuitableFor(state)) {
            int slot = 0;
            while (slot < 9 && !player.getInventory().getStack(slot).isSuitableFor(state)) slot++;
            if (slot == 9) {
                finish(client, false, "No suitable tool in hotbar for " + actual);
                return;
            }
            player.getInventory().selectedSlot = slot;
        }
        player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, center);
        if (client.interactionManager.updateBlockBreakingProgress(target, ray.getSide())) player.swingHand(Hand.MAIN_HAND);
        breaking = true;
    }

    private static void finish(MinecraftClient client, boolean result, String observation) {
        halt(client);
        success = result;
        evidence = observation;
        nextRequest = 0;
        status = observation;
    }

    private static void request(MinecraftClient client, LocalFormation.Peer here, String action, long now) {
        int epoch = generation;
        var args = new HashMap<String, Object>();
        args.put("action", action);
        if (assignment == null) {
            args.put("worker", here.name());
            args.put("kinds", KINDS);
        } else {
            args.put("project", assignment.project());
            args.put("job", assignment.job().work().id());
            args.put("token", assignment.job().lease());
            if (gatherSite != null && !gather.fromDrops()) args.put("resourceSite", gatherSite);
            if (success != null && action.equals("finish")) {
                args.put("success", success);
                args.put("evidence", evidence);
                if (chestReport != null) args.put("chest", chestReport);
                if (pickupShortfall) args.put("pickupShortfall", true);
            }
        }
        pending = true;
        nextRequest = now + seconds(1);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/projects"))
                .timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(args))).build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((reply, error) -> client.execute(() -> {
            if (generation != epoch) return;
            pending = false;
            if (error != null || reply.statusCode() >= 500) return;
            if (reply.statusCode() == 409 || action.equals("finish") && reply.statusCode() == 200) {
                reset(client);
                status = "Waiting for project jobs";
                nextRequest = System.nanoTime() + seconds(1);
                return;
            }
            if (reply.statusCode() != 200) return;
            try {
                if (action.equals("claim")) {
                    assignment = JSON.fromJson(reply.body(), ProjectQueue.Assignment.class);
                    if (assignment == null) return;
                    deadline = deadline(assignment.job(), now);

                    var work = assignment.job().work();
                    taskEnd = System.nanoTime() + seconds(switch (work.kind()) {
                        case GATHER -> 1800;
                        case SMELT -> 300;
                        case CRAFT, TRANSFER -> 180;
                        default -> 90;
                    });
                    if (work.kind() == ProjectQueue.Kind.GATHER) {
                        gather = ProjectGather.parse(work.payload());
                        gatherSite = assignment.job().resourceSite();
                        if (gather.fromDrops()) pickup = new DroppedItemSearch();
                    } else if (work.kind() == ProjectQueue.Kind.TRANSFER) {
                        var spec = ProjectChest.parse(work.payload());
                        block = spec.location();
                        chest = new ProjectChest(spec);
                    } else if (work.kind() == ProjectQueue.Kind.CRAFT || work.kind() == ProjectQueue.Kind.SMELT) {
                        var spec = ProjectProduction.parse(work.payload(), work.kind());
                        block = spec.station();
                        production = new ProjectProduction(spec);
                    } else block = ProjectBlock.parse(work.payload());
                    status = assignment.project() + "/" + assignment.job().work().id();
                } else {
                    var renewed = JSON.fromJson(reply.body(), ProjectQueue.Job.class);
                    if (renewed == null) return;
                    deadline = deadline(renewed, now);
                }
                if (success == null) nextRequest = now + seconds(4);
            } catch (IllegalArgumentException | com.google.gson.JsonParseException invalid) {
                if (assignment != null) finish(client, false, "Invalid project job payload");
            }
        }));
    }

    private static long deadline(ProjectQueue.Job lease, long sentAt) {
        long remaining = Math.max(0, lease.expiresAt() - System.currentTimeMillis() - 1500);
        return Math.min(sentAt + seconds(12), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remaining));
    }

    private static String world(LocalFormation.Peer peer) { return peer.server() + "|" + peer.dimension(); }
    private static long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }
}
