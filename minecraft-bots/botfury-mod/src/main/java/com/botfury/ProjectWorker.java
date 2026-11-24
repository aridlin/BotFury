package com.botfury;

import net.minecraft.block.BlockState;

import com.google.gson.Gson;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
    private static final Set<ProjectQueue.Kind> KINDS = Set.of(ProjectQueue.Kind.EXCAVATE, ProjectQueue.Kind.VERIFY, ProjectQueue.Kind.INSPECT, ProjectQueue.Kind.FILL_BUCKETS, ProjectQueue.Kind.TRANSFER, ProjectQueue.Kind.BUILD, ProjectQueue.Kind.CRAFT, ProjectQueue.Kind.SMELT, ProjectQueue.Kind.GATHER);
    private static WorkerEnrollment enrollment;
    private static boolean enrollmentLoaded;
    private static int port;
    private static int generation;
    private static boolean pending;
    private static ProjectQueue.Assignment assignment;
    private static ProjectBlock block;
    private static ProjectInspection inspection;
    private static ProjectWater.Fill fill;
    private static ProjectChest chest;
    private static ProjectProduction production;
    private static FieldWorkbench fieldWorkbench;
    private static long nextWorkbenchSearch;
    private static ProjectGather gather;
    private static ProjectQueue.ResourceSite gatherSite;
    private static long lastGatherProgress;
    private static int gatheredHighWater;
    private static Vec3d gatherProgressPosition;
    private static DroppedItemSearch pickup;
    private static ProjectQueue.ChestReport chestReport;
    private static boolean pickupShortfall;
    private static BaritoneSettings approach;
    private static BlockPos recoveryOrigin;
    private static BlockPos interactionStand;
    private static List<BlockPos> interactionGoals = List.of();
    private static final Set<BlockPos> rejectedInteractionStands = new HashSet<>();
    private static long nextInteractionStandSearch;
    private static final StallRecovery approachRecovery = new StallRecovery();
    private static final SubsurfaceAccess stationReturn = new SubsurfaceAccess();
    private static int approachAge;
    private static Vec3d approachProgress;
    private static long retryApproach;
    private static long nextRequest, deadline, taskEnd, stableSince, nextPlacement, inspectionStarted;
    private static long stationReturnDeadline;
    private static int stationReturnHeight;
    private static Boolean success;
    private static String evidence;
    private static boolean breaking;
    private static boolean centering;
    private static String status = "";

    static boolean enabled() { return port != 0; }
    static String status() { return status; }
    static String taskKind() {
        return assignment == null || success != null ? "" : assignment.job().work().kind().name();
    }
    static boolean supports(ProjectQueue.Kind kind) { return KINDS.contains(kind); }

    static void start(MinecraftClient client, int coordinator, String project) {
        var here = LocalFormation.snapshot(client);
        if (here == null || coordinator == BotFuryMod.controlPort()) {
            BotEvents.add("warn", "projects", "Join the project world before enabling the worker");
            return;
        }
        WorkerEnrollment next;
        try {
            next = new WorkerEnrollment(here.name(), world(here), coordinator, project);
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
        interactionStand = null;
        interactionGoals = List.of();
        rejectedInteractionStands.clear();
        nextInteractionStandSearch = 0;
        approachAge = 0;
        stationReturn.reset();
        inspection = null;
        fill = null;
        chest = null;
        production = null;
        fieldWorkbench = null;
        nextWorkbenchSearch = 0;
        gather = null;
        gatherSite = null;
        lastGatherProgress = 0;
        gatheredHighWater = 0;
        gatherProgressPosition = null;
        pickup = null;
        chestReport = null;
        pickupShortfall = false;
        success = null;
        evidence = null;
        pending = false;
        nextRequest = deadline = taskEnd = stableSince = nextPlacement = inspectionStarted = 0;
    }

    private static void halt(MinecraftClient client) {
        approachRecovery.reset();
        stationReturn.stop();
        releaseCentering(client);
        if (chest != null) chest.close(client);
        if (production != null) production.close(client);
        if (fieldWorkbench != null) fieldWorkbench.close(client);
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
        recoveryOrigin = null;
    }

    static boolean blocksRecoveryBreak(BlockPos pos) {
        var client = MinecraftClient.getInstance();
        if (stationReturn.blocksBreaking(client, pos)) return true;
        if (recoveryOrigin == null) return false;
        boolean stationJob = chest != null || production != null;
        if (client.world == null || block == null || !pos.isWithinDistance(recoveryOrigin, stationJob ? 9 : 5)
                || pos.getY() < recoveryOrigin.getY()
                || pos.getY() > (stationJob ? block.stand().getY() + 1 : recoveryOrigin.getY() + 2)
                || pos.equals(block.target()) || pos.equals(block.support()) || pos.equals(block.target().down())
                || pos.equals(block.stand().down())
                || (interactionStand != null && pos.equals(interactionStand.down()))
                || interactionGoals.contains(pos.up())
                || !BaritoneSettings.recoverableTerrain(client.world.getBlockState(pos).getBlock())) return true;
        return client.world.getPlayers().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                && player.getBlockPos().down().equals(pos));
    }

    static void tick(MinecraftClient client, LocalFormation.Peer here) {
        if (!enabled()) return;
        long now = System.nanoTime();
        if (assignment == null) {
            TaskDefense.evade(client, false);
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
        if (success != null) {
            TaskDefense.evade(client, false);
            return;
        }
        if (now >= taskEnd) {
            finish(client, false, ProjectQueue.TARGET_TIMEOUT);
            return;
        }
        if (TaskDefense.evade(client, true)) {
            releaseCentering(client);
            return;
        }
        if (!centering && TaskDefense.defendProject(client)) {
            if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
            return;
        }
        if (gather != null) {
            int held = gather.inventoryCount(client);
            if (held >= gather.count()) {
                finish(client, true, "Observed " + held + " " + gather.item() + " in worker inventory");
            } else if (!gatherProgress(client, held, now)) {
                finish(client, false, ProjectQueue.GATHER_STALLED);
            } else if (pickup != null) {
                var result = pickup.tick(client, gather.item()::matches, null);
                if (result == DroppedItemSearch.State.UNAVAILABLE) finish(client, false, "Baritone unavailable for pickup");
                else status = (result == DroppedItemSearch.State.SEARCHING ? "Looking for dropped " : "Collecting ")
                        + gather.item() + " (" + held + "/" + gather.count() + ")";
            } else if (!MiningTask.active()) {
                MiningTask.startProject(client, gather.mining(port), gather.exclude(), gatherSite, gather.item());
                if (!MiningTask.active()) finish(client, false, MiningTask.lastStatus());
            } else {
                status = assignment.project() + "/" + assignment.job().work().id() + " (" + held + "/" + gather.count()
                        + "): " + MiningTask.status();
                MiningTask.tick(client);
                var observation = MiningTask.observedSite();
                if (observation != null) gatherSite = observation;
                if (MiningTask.toolShortfall()) finish(client, false, ProjectQueue.GATHER_TOOLS_NEEDED);
            }
        } else if (production != null) {
            var result = block == null ? production.tick(client, now) : production.finishedFromInventory(client);
            if (result != null) finish(client, result.success(), result.detail());
            else if (block != null && !prepareLocalWorkbench(client, now)) execute(client, now);
        } else execute(client, now);
    }

    private static boolean prepareLocalWorkbench(MinecraftClient client, long now) {
        if (assignment.job().work().kind() != ProjectQueue.Kind.CRAFT) return false;
        if (fieldWorkbench == null && now >= nextWorkbenchSearch
                && !block.target().isWithinDistance(client.player.getBlockPos(), 16)) {
            nextWorkbenchSearch = now + seconds(8);
            var nearby = FieldWorkbench.find(client, now);
            if (nearby != null) {
                halt(client);
                fieldWorkbench = nearby;
            }
        }
        if (fieldWorkbench == null) return false;
        var state = fieldWorkbench.tick(client, now);
        if (state == FieldWorkbench.State.WORKING) {
            status = assignment.project() + "/" + assignment.job().work().id() + ": Setting up a nearby workbench";
            return true;
        }
        if (state == FieldWorkbench.State.READY) {
            block = fieldWorkbench.station();
            production = production.atStation(block);
            interactionStand = null;
            interactionGoals = List.of();
            rejectedInteractionStands.clear();
            BotEvents.add("info", "projects", "Using nearby workbench at " + block.target().toShortString());
        }
        fieldWorkbench.close(client);
        fieldWorkbench = null;
        nextWorkbenchSearch = now + seconds(15);
        return false;
    }

    private static boolean gatherProgress(MinecraftClient client, int held, long now) {
        var position = client.player.getPos();
        if (gatherProgressPosition == null || held > gatheredHighWater
                || position.squaredDistanceTo(gatherProgressPosition) >= 9) {
            lastGatherProgress = now;
            gatheredHighWater = Math.max(held, gatheredHighWater);
            gatherProgressPosition = position;
        }
        return now - lastGatherProgress < seconds(90);
    }

    private static void execute(MinecraftClient client, long now) {
        var player = client.player;
        BlockPos target = block.target();
        if (!client.world.getWorldBorder().contains(target) || !client.world.getWorldBorder().contains(block.stand())) {
            finish(client, false, "Project block or stand outside world border");
            return;
        }
        var work = assignment.job().work();
        var kind = work.kind();
        boolean excavate = kind == ProjectQueue.Kind.EXCAVATE;
        boolean build = kind == ProjectQueue.Kind.BUILD;
        if ((kind == ProjectQueue.Kind.EXCAVATE || kind == ProjectQueue.Kind.BUILD || kind == ProjectQueue.Kind.FILL_BUCKETS)
                && BuildProtection.blocked(assignment.world(), target, assignment.project())) {
            finish(client, false, "Block is in an exclusion zone at " + target.toShortString());
            return;
        }
        boolean targetLoaded = client.world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4);
        if ((excavate || build) && targetLoaded) {
            var observed = client.world.getBlockState(target);
            if (excavate ? observed.isAir() : block.matches(observed)) {
                // The loaded block state proves completed work even when the old approach is gone.
                if (approach != null || breaking || centering) halt(client);
                if (stableSince == 0) stableSince = now;
                if (now - stableSince >= seconds(1)) finish(client, true, blockObservation(observed));
                return;
            }
            stableSince = 0;
        }
        BlockPos stand = interactionStand == null ? block.stand() : interactionStand;
        boolean canExcavateHere = excavate && targetLoaded && player.isOnGround() && !centering
                && !player.getBlockPos().down().equals(target) && excavationHit(client, target) != null;
        boolean ordinaryBuild = build && !block.edgePlacement();
        boolean canBuildHere = ordinaryBuild && targetLoaded && player.isOnGround() && !centering
                && !player.getBoundingBox().intersects(new net.minecraft.util.math.Box(target))
                && ProjectPlacement.supportHit(client, block) != null;
        boolean stationJob = production != null || chest != null;
        boolean stationNearby = targetLoaded && (production != null && production.reachableFromHere(client)
                || chest != null && chest.reachableFromHere(client));
        boolean belowStation = stationJob && stand.getY() - player.getBlockY() >= 5;
        boolean searchingForStand = excavate && !canExcavateHere || ordinaryBuild && !canBuildHere
                && client.world.getBlockState(target).isAir()
                || stationJob && !stationNearby && block.matches(client.world.getBlockState(target));
        boolean routeFailed = approach != null && now >= retryApproach && !BaritoneClient.pursuing(stand);
        // Try a protected exit once after both the planned and an alternate approach fail.
        boolean recoverBeforeSwitching = (excavate || ordinaryBuild) && interactionStand != null
                && recoveryOrigin == null && routeFailed;
        if (searchingForStand && !recoverBeforeSwitching && !belowStation && !stationReturn.active()
                && targetLoaded && now >= nextInteractionStandSearch
                && client.world.isChunkLoaded(stand.getX() >> 4, stand.getZ() >> 4)
                && (!usableInteractionStand(client, stand, target) || !centering && player.getBlockPos().equals(stand)
                    || routeFailed)) {
            nextInteractionStandSearch = now + seconds(1);
            var alternatives = nextInteractionStands(client, target, stand, kind);
            if (!alternatives.isEmpty()) {
                rejectedInteractionStands.add(stand);
                halt(client);
                interactionStand = stand = alternatives.get(0);
                interactionGoals = excavate || ordinaryBuild ? alternatives : List.of();
                status = assignment.project() + "/" + work.id() + ": Approaching from another side";
            }
        }
        Vec3d feet = Vec3d.ofBottomCenter(stand);
        boolean inspectNearby = kind == ProjectQueue.Kind.VERIFY && targetLoaded
                && player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(target)) <= 16;
        boolean exactFooting = excavate || kind == ProjectQueue.Kind.BUILD;
        boolean edge = kind == ProjectQueue.Kind.BUILD && block.edgePlacement();
        boolean nearStand = player.getPos().squaredDistanceTo(feet) <= 1.21;
        boolean canCleanFromHere = nearStand && work.cleanup() != null && targetLoaded
                && (client.world.getBlockState(target).isAir() || excavationHit(client, target) != null);
        boolean atStand = nearStand && (!exactFooting || player.getBlockPos().equals(stand) || canCleanFromHere
                || edge && centering && player.getBlockPos().equals(target.up()));
        boolean onFooting = stationJob ? SubsurfaceAccess.hasFooting(client) : player.isOnGround();
        if (!onFooting || !atStand && !inspectNearby && !stationNearby && !canExcavateHere && !canBuildHere) {
            releaseCentering(client);
            stableSince = 0;
            approachAge++;
            if (tryStationReturn(client, stand, now)) return;
            var recovery = approachRecovery.tick(client, stand,
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
                    var pos = stand;
                    var feetBlock = player.getBlockPos();
                    boolean localWork = excavate || build;
                    boolean stationClimb = stationJob && pos.getY() - feetBlock.getY() >= 2
                            && feetBlock.isWithinDistance(pos, 12);
                    if (approach != null && recoveryOrigin == null && (localWork || stationClimb)
                            && !BaritoneClient.pursuing(pos)) {
                        BaritoneClient.execute("stop");
                        approach.close();
                        approach = null;
                        approach = new BaritoneSettings(true, true, true);
                        recoveryOrigin = feetBlock;
                        BotEvents.add("info", "movement", "Opening a short approach through natural terrain");
                    }
                    if (approach == null) approach = new BaritoneSettings(false, true);
                    approachProgress = current;
                    retryApproach = now + seconds(5);
                    var goals = interactionGoals.isEmpty() ? List.of(pos) : interactionGoals;
                    if (!BaritoneClient.pursuing(pos) && !BaritoneClient.goToAny(goals)) {
                        finish(client, false, "Baritone could not start project approach");
                    }
                } catch (ReflectiveOperationException error) {
                    finish(client, false, "Baritone unavailable for project approach");
                }
            }
            return;
        }
        if (approach != null || stationReturn.active()) halt(client);
        if (!targetLoaded) {
            stableSince = 0;
            return;
        }
        if (fill != null) {
            int held = 0;
            for (int slot = 0; slot < 36; slot++) {
                var stack = player.getInventory().getStack(slot);
                if (stack.isOf(net.minecraft.item.Items.WATER_BUCKET)) held += stack.getCount();
            }
            if (held >= fill.count()) {
                finish(client, true, "Observed " + held + " water buckets in worker inventory");
            } else if (now >= nextPlacement) {
                nextPlacement = now + seconds(1);
                String failure = fill.attempt(client);
                if (failure != null) finish(client, false, failure);
            }
            return;
        }
        if (inspection != null) {
            if (inspectionStarted == 0) inspectionStarted = now;
            for (var pos : inspection.expected().keySet()) {
                if (!client.world.isInBuildLimit(pos) || !client.world.getWorldBorder().contains(pos)) {
                    finish(client, false, "Inspection cell outside world bounds at " + pos.toShortString());
                    return;
                }
            }
            String mismatch = inspection.mismatch(client.world);
            if (mismatch != null) {
                stableSince = 0;
                if (!inspection.settleFlow() || now - inspectionStarted >= seconds(12)) finish(client, false, mismatch);
            } else {
                if (stableSince == 0) stableSince = now;
                if (now - stableSince >= seconds(inspection.settleFlow() ? 3 : 1)) {
                    finish(client, true, "Verified " + inspection.expected().size() + " block states");
                }
            }
            return;
        }
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
        if (kind == ProjectQueue.Kind.VERIFY && targetLoaded && block.matches(state)) {
            halt(client);
            if (stableSince == 0) stableSince = now;
            if (now - stableSince >= seconds(1)) finish(client, true, blockObservation(state));
            return;
        }
        stableSince = 0;
        if (kind == ProjectQueue.Kind.BUILD) {
            if (edge && (!ProjectPlacement.flushFloor(client.world.getBlockState(block.support()), client.world, block.support())
                    || !client.world.getBlockState(target.up()).isAir()
                    || !client.world.getBlockState(target.up(2)).isAir())) {
                finish(client, false, "Edge placement needs a solid floor and clear space");
                return;
            }
            var placementFeet = edge ? ProjectBlock.edgePosition(block.stand(), block.face()) : feet;
            // A bucket only needs the approved face in reach. Avoid micro-adjustments on narrow banks.
            if (!edge && ProjectWater.source(block.desired()) && ProjectPlacement.supportHit(client, block) != null) {
                if (centering) {
                    releaseCentering(client);
                    return;
                }
            } else if (!canBuildHere && centerOnStand(client, placementFeet, edge)) return;
            if (ordinaryBuild && !canBuildHere && state.isAir() && ProjectPlacement.supportHit(client, block) == null) {
                status = assignment.project() + "/" + work.id() + ": Looking for a reachable support face";
                return;
            }
            if (now >= nextPlacement) {
                var failure = BuildProtection.projectAction(assignment.project(), () -> ProjectPlacement.attempt(client, block));
                nextPlacement = now + seconds(1);
                if (failure != null) finish(client, false, failure);
            }
            return;
        }
        if (kind == ProjectQueue.Kind.VERIFY) {
            String expected = block.block() + (block.properties().isEmpty() ? "" : block.properties().toString());
            String observed = actual + (block.properties().isEmpty() ? "" : ProjectBlock.properties(state).toString());
            finish(client, false, "Expected " + expected + " but observed " + observed + " at " + target.toShortString());
            return;
        }
        if (!excavate || !matchesExcavationState(state) || state.hasBlockEntity()
                || state.getHardness(client.world, target) < 0) {
            finish(client, false, "Unexpected or protected block " + actual + " at " + target.toShortString());
            return;
        }
        if (client.world.getPlayers().stream().anyMatch(other -> other.isAlive() && !other.isSpectator()
                && other.getBlockPos().down().equals(target))) {
            client.interactionManager.cancelBlockBreaking();
            return;
        }
        if (centering && centerOnStand(client, feet)) return;
        var ray = excavationHit(client, target);
        if (ray == null) {
            if (player.getBlockPos().equals(stand) && centerOnStand(client, feet)) return;
            finish(client, false, "Target obstructed or out of reach from the approved stand");
            return;
        }
        if (state.isToolRequired() && !player.getMainHandStack().isSuitableFor(state)) {
            var inventory = player.getInventory();
            int slot = 0;
            while (slot < 36 && !inventory.getStack(slot).isSuitableFor(state)) slot++;
            if (slot == 36) {
                finish(client, false, "No suitable tool in inventory for " + actual);
                return;
            }
            if (slot >= 9) {
                if (player.currentScreenHandler != player.playerScreenHandler
                        || !player.playerScreenHandler.getCursorStack().isEmpty()) {
                    finish(client, false, "Close the container or clear the cursor to equip an excavation tool");
                    return;
                }
                client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot,
                        inventory.selectedSlot, SlotActionType.SWAP, player);
                return;
            }
            inventory.selectedSlot = slot;
        }
        player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, ray.getPos());
        if (BuildProtection.projectAction(assignment.project(),
                () -> client.interactionManager.updateBlockBreakingProgress(target, ray.getSide()))) player.swingHand(Hand.MAIN_HAND);
        breaking = true;
    }

    private static boolean tryStationReturn(MinecraftClient client, BlockPos destination, long now) {
        if (chest == null && production == null) return false;
        var position = client.player.getBlockPos();
        // Each segment is bounded around the worker. A distant station can still need that local climb.
        if (destination.getY() - position.getY() < 5) {
            stationReturn.stop();
            return false;
        }
        if (position.getY() >= stationReturnHeight + 4) {
            stationReturnHeight = position.getY();
            taskEnd = Math.max(taskEnd, Math.min(stationReturnDeadline, now + seconds(90)));
        }
        if (!stationReturn.active()) {
            if (approach == null || now < retryApproach || BaritoneClient.pursuing(destination)) return false;
            BaritoneClient.execute("stop");
            approachRecovery.reset();
            approach.close();
            approach = null;
            recoveryOrigin = null;
        }
        var target = block.target();
        var excluded = StructureSupply.union(new ProjectGather.Area(target, target), block);
        if (!stationReturn.advanceToward(client, approachAge, destination, excluded)) return false;
        status = assignment.project() + "/" + assignment.job().work().id() + ": Opening a return route to the station";
        return true;
    }

    private static boolean matchesExcavationState(net.minecraft.block.BlockState state) {
        if (block.matches(state)) return true;
        var expected = block.desired();
        return expected.getBlock() instanceof LeavesBlock && state.isOf(expected.getBlock())
                && expected.with(LeavesBlock.DISTANCE, state.get(LeavesBlock.DISTANCE)).equals(state);
    }

    private static List<BlockPos> nextInteractionStands(MinecraftClient client, BlockPos target, BlockPos previous,
                                                       ProjectQueue.Kind kind) {
        var candidates = new ArrayList<BlockPos>();
        for (var pos : BlockPos.iterateOutwards(target, 4, 4, 4)) {
            if (pos.equals(previous) || rejectedInteractionStands.contains(pos)
                    || !client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || !usableInteractionStand(client, pos, target)) continue;
            var feet = Vec3d.ofBottomCenter(pos);
            var eye = feet.add(0, 1.62, 0);
            var hit = switch (kind) {
                case EXCAVATE -> excavationHit(client, target, eye);
                case BUILD -> ProjectPlacement.supportHit(client, block, eye);
                default -> ProjectProduction.workstationHit(client, target, eye);
            };
            if (hit != null) candidates.add(pos.toImmutable());
        }
        candidates.sort(Comparator.comparingDouble(pos -> client.player.squaredDistanceTo(Vec3d.ofBottomCenter(pos))));
        return candidates.stream().limit(24).toList();
    }

    private static boolean usableInteractionStand(MinecraftClient client, BlockPos pos, BlockPos target) {
        return !pos.equals(target) && !pos.up().equals(target) && !pos.down().equals(target)
                && client.world.isInBuildLimit(pos.up()) && client.world.getWorldBorder().contains(pos)
                && ProjectPlacement.flushFloor(client.world.getBlockState(pos.down()), client.world, pos.down())
                && client.world.isAir(pos) && client.world.isAir(pos.up());
    }

    private static BlockHitResult excavationHit(MinecraftClient client, BlockPos target) {
        return excavationHit(client, target, client.player.getEyePos());
    }

    private static BlockHitResult excavationHit(MinecraftClient client, BlockPos target, Vec3d eye) {
        double reach = client.interactionManager.getReachDistance() - .1;
        if (eye.squaredDistanceTo(Vec3d.ofCenter(target)) > (reach + 1) * (reach + 1)) return null;
        var shape = client.world.getBlockState(target).getOutlineShape(client.world, target);
        for (var box : shape.getBoundingBoxes()) {
            var center = box.getCenter().add(target.getX(), target.getY(), target.getZ());
            var hit = excavationRay(client, target, eye, center, reach);
            if (hit != null) return hit;
            for (var face : Direction.values()) {
                var normal = Vec3d.of(face.getVector());
                double halfSize = switch (face.getAxis()) {
                    case X -> (box.maxX - box.minX) / 2;
                    case Y -> (box.maxY - box.minY) / 2;
                    case Z -> (box.maxZ - box.minZ) / 2;
                };
                var point = center.add(normal.multiply(halfSize * .98));
                if (eye.subtract(point).dotProduct(normal) <= 0) continue;
                hit = excavationRay(client, target, eye, point, reach);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static BlockHitResult excavationRay(MinecraftClient client, BlockPos target, Vec3d eye, Vec3d point,
                                                double reach) {
        if (eye.squaredDistanceTo(point) > reach * reach) return null;
        var hit = client.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target) ? hit : null;
    }

    private static boolean centerOnStand(MinecraftClient client, Vec3d stand) {
        return centerOnStand(client, stand, false);
    }

    private static boolean centerOnStand(MinecraftClient client, Vec3d stand, boolean staySneaking) {
        var player = client.player;
        double dx = stand.x - player.getX();
        double dz = stand.z - player.getZ();
        // Match the eye position used to check reach in the planner.
        double tolerance = staySneaking ? .04 : .075;
        if (dx * dx + dz * dz <= tolerance * tolerance) {
            if (staySneaking) {
                client.options.forwardKey.setPressed(false);
                client.options.sneakKey.setPressed(true);
                centering = true;
                return !player.isSneaking();
            }
            releaseCentering(client);
            return player.isSneaking();
        }
        player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
                new Vec3d(stand.x, player.getEyeY(), stand.z));
        player.setSprinting(false);
        client.options.sneakKey.setPressed(true);
        client.options.forwardKey.setPressed(true);
        centering = true;
        return true;
    }

    private static void releaseCentering(MinecraftClient client) {
        if (!centering) return;
        client.options.forwardKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        centering = false;
    }

    private static String blockObservation(BlockState observed) {
        return "Observed " + Registries.BLOCK.getId(observed.getBlock())
                + (block.properties().isEmpty() ? "" : ProjectBlock.properties(observed)) + " at " + block.target().toShortString();
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
            if (enrollment.project() != null) args.put("project", enrollment.project());
            args.put("kinds", KINDS);
            var tools = new HashMap<String, Integer>();
            var inventory = client.player.getInventory();
            for (int slot = 0; slot < 36; slot++) {
                var stack = inventory.getStack(slot);
                if (stack.getItem() instanceof net.minecraft.item.PickaxeItem) {
                    tools.merge(Registries.ITEM.getId(stack.getItem()).toString(),
                            stack.getMaxDamage() - stack.getDamage(), Integer::sum);
                }
            }
            if (!tools.isEmpty()) args.put("toolUses", tools);
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
                    if (enrollment.project() != null && !enrollment.project().equals(assignment.project())) {
                        assignment = null;
                        status = "Coordinator did not honor the selected project";
                        return;
                    }
                    deadline = deadline(assignment.job(), now);

                    var work = assignment.job().work();
                    long started = System.nanoTime();
                    stationReturnDeadline = started + seconds(900);
                    stationReturnHeight = client.player.getBlockY();
                    taskEnd = started + seconds(switch (work.kind()) {
                        case GATHER -> 1800;
                        case SMELT -> 300;
                        case CRAFT, TRANSFER, FILL_BUCKETS -> 180;
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
                        var spec = ProjectProduction.useInventoryGrid(client, ProjectProduction.parse(work.payload(), work.kind()));
                        block = spec.station();
                        production = new ProjectProduction(spec);
                    } else if (work.kind() == ProjectQueue.Kind.FILL_BUCKETS) {
                        fill = ProjectWater.Fill.parse(work.payload());
                        block = fill.location();
                    } else if (work.kind() == ProjectQueue.Kind.INSPECT) {
                        inspection = ProjectInspection.parse(work.payload());
                        BlockPos target = inspection.expected().keySet().iterator().next();
                        block = new ProjectBlock(target, inspection.stand(), Identifier.of("minecraft", "air"));
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
