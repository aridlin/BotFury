package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One opt-in job loop per managed client. All Minecraft actions run on its client thread. */
final class ProjectWorker {
    private static final Gson JSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(750)).build();
    private static final Set<ProjectQueue.Kind> KINDS = Set.of(ProjectQueue.Kind.EXCAVATE, ProjectQueue.Kind.VERIFY, ProjectQueue.Kind.TRANSFER, ProjectQueue.Kind.BUILD, ProjectQueue.Kind.CRAFT, ProjectQueue.Kind.SMELT);
    private static int port;
    private static int generation;
    private static boolean pending;
    private static ProjectQueue.Assignment assignment;
    private static ProjectBlock block;
    private static ProjectChest chest;
    private static ProjectProduction production;
    private static ProjectQueue.ChestReport chestReport;
    private static BaritoneSettings approach;
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
        if (port == coordinator) return;
        LocalFormation.pauseMovement();
        BotInventory.hold();
        port = coordinator;
        status = "Waiting for project jobs";
    }

    static void stop(MinecraftClient client) {
        generation++;
        port = 0;
        reset(client);
        status = "";
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
        chestReport = null;
        success = null;
        evidence = null;
        pending = false;
        nextRequest = deadline = taskEnd = stableSince = nextPlacement = 0;
    }

    private static void halt(MinecraftClient client) {
        if (chest != null) chest.close(client);
        if (production != null) production.close(client);
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
        execute(client, now);
    }

    private static void execute(MinecraftClient client, long now) {
        var player = client.player;
        BlockPos target = block.target();
        if (!client.world.getWorldBorder().contains(target) || !client.world.getWorldBorder().contains(block.stand())) {
            finish(client, false, "Project block or stand outside world border");
            return;
        }
        Vec3d feet = Vec3d.ofBottomCenter(block.stand());
        if (player.getPos().squaredDistanceTo(feet) > 1.21) {
            stableSince = 0;
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
                if (result.stock() != null) chestReport = new ProjectQueue.ChestReport(ProjectBlock.key(target), result.stock());
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
        boolean matches = excavate || block.block().equals(net.minecraft.util.Identifier.of("minecraft", "air"))
                ? state.isAir() : actual.equals(block.block());
        if (matches) {
            halt(client);
            if (stableSince == 0) stableSince = now;
            if (now - stableSince >= seconds(1)) finish(client, true, "Observed " + actual + " at " + target.toShortString());
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
        if (!excavate || !actual.equals(block.block()) || state.hasBlockEntity()
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
            if (success != null && action.equals("finish")) {
                args.put("success", success);
                args.put("evidence", evidence);
                if (chestReport != null) args.put("chest", chestReport);
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
                    taskEnd = System.nanoTime() + seconds(work.kind() == ProjectQueue.Kind.SMELT ? 300 : 45);
                    if (work.kind() == ProjectQueue.Kind.TRANSFER) {
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
