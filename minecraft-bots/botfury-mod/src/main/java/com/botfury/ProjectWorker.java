package com.botfury;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;

import com.google.gson.Gson;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
    private static final double STAND_CENTER_TOLERANCE = .075;
    private static final double[] JUMP_HEIGHTS = {.42, .76, 1, 1.15};
    private static final BreathingRecovery breathing = new BreathingRecovery();
    private static final Gson JSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(750)).build();
    private static final Set<ProjectQueue.Kind> KINDS = Set.of(ProjectQueue.Kind.EXCAVATE, ProjectQueue.Kind.VERIFY, ProjectQueue.Kind.INSPECT, ProjectQueue.Kind.FILL_BUCKETS, ProjectQueue.Kind.TRANSFER, ProjectQueue.Kind.BUILD, ProjectQueue.Kind.CRAFT, ProjectQueue.Kind.SMELT, ProjectQueue.Kind.GATHER, ProjectQueue.Kind.RESUPPLY);
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
    private static boolean carriedUnstartedSmeltingInput;
    private static java.util.UUID interruptedSmeltingLease;
    private static FieldWorkstation fieldWorkstation;
    private static final Set<BlockPos> rejectedFieldWorkbenches = new HashSet<>();
    private static long nextWorkstationSearch;
    private static final Identifier MINING_SWORD = Identifier.of("minecraft", "stone_sword");
    private static final Identifier ROUTE_STICKS = Identifier.of("minecraft", "stick");
    private static final Identifier ROUTE_PICKAXE = Identifier.of("minecraft", "stone_pickaxe");
    private static final Identifier WOODEN_PICKAXE = Identifier.of("minecraft", "wooden_pickaxe");
    private static Identifier miningRecipe;
    private static ProjectProduction miningCraft;
    private static boolean preparingMiningIngredients;
    private static ProjectGather.Area miningCraftArea;
    private static ProjectBlock portableWorkbench, productionWorkbench;
    private static ProjectProduction.Result pendingProductionResult;
    private static boolean resumesLoadedFurnace;
    private static ProjectQueue.FurnaceSite loadedFurnace;
    private static WorkbenchPickup workbenchPickup;
    private static long nextSafetyReport;
    private static boolean waitingForSafety;
    private static long nextMiningWeaponCheck, nextRoutePickaxeCheck;
    private static boolean pausedForMiningCraft;
    private static boolean checkedExcavationTool;
    private static ProjectGather gather;
    private static boolean checkedCarriedFuel;
    private static ProjectQueue.ResourceSite gatherSite;
    private static final GatherProgress gatherProgress = new GatherProgress();
    private static boolean gatherAtSurface;
    private static int checkpointAt, cargoHighWater;
    private static long lastCargoIncrease;
    private static DroppedItemSearch pickup;
    private static ProjectQueue.ChestReport chestReport;
    private static boolean pickupShortfall;
    private static BaritoneSettings approach;
    private static BlockPos recoveryOrigin;
    private static BlockPos interactionStand;
    private static List<BlockPos> interactionGoals = List.of();
    private static final Set<BlockPos> rejectedInteractionStands = new HashSet<>();
    private static final Set<BlockPos> triedJumpStands = new HashSet<>();
    private static final Set<BlockPos> triedCanopyStands = new HashSet<>();
    private static long nextInteractionStandSearch;
    private static long canopyApproachEnds;
    private static BlockPos elevatedPlacementStand;
    private static long lowerPlacementEnds;
    private static boolean triedLowerPlacement;
    private static int waypoint;
    private static long waypointEnds;
    private static long jumpEnds;
    private static boolean jumpPressed;
    private static BlockPos openingJumpHeadroom;
    private static boolean attemptedJumpHeadroom;
    private static final StallRecovery approachRecovery = new StallRecovery();
    private static final StallRecovery idleRecovery = new StallRecovery();
    private static final WorkerReturnTrail returnTrail = new WorkerReturnTrail();
    private static final WorkerCargoRecovery cargoRecovery = new WorkerCargoRecovery();
    private static ProjectItem projectCargoItem;
    private static boolean travelingContribution, readyToBank;
    private static final Map<String, Integer> recentInventory = new HashMap<>();
    private static boolean observedDeath;
    private static List<ProjectQueue.ResourceSite> returnWaypoints;
    private static long nextTrailSave, idleSince, nextIdleAscent, nextIdleToolCheck, nextStarterSurvey;
    private static BlockPos idleSurfaceGoal;
    private static long idleAscentDeadline, idleAscentProgressAt;
    private static int idleAscentHeight, nextIdleRouteProbe;
    private static final SubsurfaceAccess stationReturn = new SubsurfaceAccess();
    private static final SubsurfaceAccess stationDescent = new SubsurfaceAccess();
    private static int stationDescentSteps;
    private static BlockPos stationToolAttempt;
    private static boolean needsRouteTool;
    private static boolean needsRouteSticks;
    private static boolean reportingRouteSticks, acknowledgedRouteSticks;
    private static boolean allowedResupplyWait;
    private static boolean needsRouteBench, needsProductionBench;
    private static boolean receivedRouteBench;
    private static PortableWoodPreparation routeWorkbench;
    private static PortableWoodPreparation stickPreparation;
    private static final int TOOL_DROP_RADIUS = 5;
    private static final DroppedItemSearch routeToolPickup = new DroppedItemSearch(TOOL_DROP_RADIUS);
    private static boolean pickingUpRouteTool;
    private static boolean deliveringRouteTool;
    private static ProjectQueue.RouteToolDelivery craftingRouteDelivery;
    private static FieldForage fieldForage;
    private static int approachAge;
    private static Vec3d approachProgress;
    private static long retryApproach;
    private static long nextRequest, deadline, taskEnd, stableSince, nextPlacement, inspectionStarted;
    private static long stationReturnDeadline;
    private static long fieldStoneUntil, nextFieldStoneSearch;
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
        nextTrailSave = 0;
        try {
            returnTrail.load(trailPath(), enrollment);
        } catch (IOException error) {
            BotEvents.add("warn", "projects", "Could not read saved worker return trail");
        }
        cargoRecovery.load(cargoPath(), enrollment);
        status = "Waiting for project jobs";
        BotEvents.add("info", "projects", event);
    }

    static void suspend(MinecraftClient client) {
        interruptedSmeltingLease = null;
        cargoRecovery.stop();
        recentInventory.clear();
        breathing.stop(client);
        generation++;
        port = 0;
        reset(client);
        status = "";
    }

    static void stop(MinecraftClient client) {
        suspend(client);
        if (!Boolean.getBoolean("botfury.managedHttp")) return;
        cargoRecovery.clear(cargoPath());
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

    private static Path trailPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("botfury-return-trail.json");
    }

    private static Path cargoPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("botfury-dropped-cargo.json");
    }

    private static void saveTrail() {
        try {
            returnTrail.save(trailPath(), enrollment);
        } catch (IOException error) {
            BotEvents.add("warn", "projects", "Could not save worker return trail");
        }
    }

    private static ProjectQueue.ResourceSite routePoint(BlockPos pos) {
        return new ProjectQueue.ResourceSite(pos.getX(), pos.getY(), pos.getZ());
    }

    static void interrupt(MinecraftClient client) {
        if (observedDeath) return;
        observedDeath = true;
        cargoRecovery.onDeath(cargoPath(), enrollment, projectCargoItem, recentInventory, client.player.getBlockPos());
        recentInventory.clear();
        if (assignment == null && !pending && idleSurfaceGoal == null && miningRecipe == null && workbenchPickup == null) return;
        if (assignment != null && production != null && success == null && assignment.job().attempts() == 1
                && carriedUnstartedSmeltingInput && !production.contactedStation()) {
            interruptedSmeltingLease = assignment.job().lease();
        }
        generation++;
        reset(client);
        status = "Waiting to respawn before claiming project work";
    }

    private static void reset(MinecraftClient client) {
        waitingForSafety = false;
        nextSafetyReport = 0;
        idleRecovery.reset();
        breathing.stop(client);
        halt(client);
        assignment = null;
        block = null;
        interactionStand = null;
        interactionGoals = List.of();
        rejectedInteractionStands.clear();
        triedJumpStands.clear();
        triedCanopyStands.clear();
        waypoint = 0;
        returnWaypoints = null;
        idleSurfaceGoal = null;
        idleSince = nextIdleAscent = nextIdleToolCheck = 0;
        attemptedJumpHeadroom = false;
        triedLowerPlacement = false;
        elevatedPlacementStand = null;
        lowerPlacementEnds = 0;
        nextInteractionStandSearch = 0;
        approachAge = 0;
        stationReturn.reset();
        stationDescent.reset();
        stationDescentSteps = 0;
        stationToolAttempt = null;
        needsRouteTool = needsRouteBench = needsProductionBench = receivedRouteBench = allowedResupplyWait = false;
        needsRouteSticks = false;
        reportingRouteSticks = acknowledgedRouteSticks = false;
        craftingRouteDelivery = null;
        inspection = null;
        fill = null;
        chest = null;
        production = null;
        carriedUnstartedSmeltingInput = false;
        fieldWorkstation = null;
        rejectedFieldWorkbenches.clear();
        nextWorkstationSearch = 0;
        nextFieldStoneSearch = 0;
        miningRecipe = null;
        miningCraft = null;
        preparingMiningIngredients = false;
        miningCraftArea = null;
        portableWorkbench = productionWorkbench = null;
        pendingProductionResult = null;
        resumesLoadedFurnace = false;
        loadedFurnace = null;
        workbenchPickup = null;
        nextMiningWeaponCheck = nextRoutePickaxeCheck = 0;
        pausedForMiningCraft = false;
        checkedExcavationTool = false;
        gather = null;
        projectCargoItem = null;
        travelingContribution = readyToBank = false;
        checkedCarriedFuel = false;
        gatherSite = null;
        gatherProgress.reset();
        checkpointAt = cargoHighWater = 0;
        lastCargoIncrease = 0;
        pickup = null;
        chestReport = null;
        pickupShortfall = false;
        success = null;
        evidence = null;
        pending = false;
        nextRequest = deadline = taskEnd = stableSince = nextPlacement = inspectionStarted = 0;
    }

    private static void halt(MinecraftClient client) {
        if (fieldForage != null) {
            fieldForage.close();
            fieldForage = null;
        }
        approachRecovery.reset();
        routeToolPickup.reset();
        pickingUpRouteTool = false;
        if (routeWorkbench != null) {
            routeWorkbench.close(client);
            routeWorkbench = null;
        }
        if (stickPreparation != null) {
            stickPreparation.close(client);
            stickPreparation = null;
        }
        if (deliveringRouteTool) {
            BotInventory.hold();
            deliveringRouteTool = false;
        }
        stationReturn.stop();
        stationDescent.stop();
        releaseCentering(client);
        releaseJump(client);
        jumpEnds = 0;
        openingJumpHeadroom = null;
        if (chest != null) chest.close(client);
        if (production != null) production.close(client);
        if (fieldWorkstation != null) fieldWorkstation.close(client);
        if (miningCraft != null) miningCraft.close(client);
        if (workbenchPickup != null) workbenchPickup.close(client);
        if (pickup != null) pickup.stop();
        if ((gather != null || fieldStoneUntil != 0) && MiningTask.active()) MiningTask.stop();
        fieldStoneUntil = 0;
        if (breaking && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        breaking = false;
        if (approach != null) {
            BaritoneClient.execute("stop");
            approach.close();
            approach = null;
            approachProgress = null;
        }
        recoveryOrigin = null;
        canopyApproachEnds = 0;
        waypointEnds = 0;
    }

    static boolean blocksRecoveryBreak(BlockPos pos) {
        var client = MinecraftClient.getInstance();
        if (stationReturn.blocksBreaking(client, pos) || stationDescent.blocksBreaking(client, pos)) return true;
        if (recoveryOrigin == null) return false;
        boolean stationJob = chest != null || production != null;
        boolean canopy = canopyApproachEnds != 0;
        if (client.world == null || block == null || !pos.isWithinDistance(recoveryOrigin, canopy ? 8 : stationJob ? 9 : 5)
                || pos.getY() < recoveryOrigin.getY()
                || pos.getY() > (canopy ? recoveryOrigin.getY() + 6
                    : stationJob ? block.stand().getY() + 1 : recoveryOrigin.getY() + 2)
                || pos.equals(block.target()) || pos.equals(block.support()) || pos.equals(block.target().down())
                || pos.equals(block.stand().down())
                || (interactionStand != null && pos.equals(interactionStand.down()))
                // The station worker can route through the floors of unused candidate stands.
                || !stationJob && interactionGoals.contains(pos.up())) return true;
        var world = client.world;
        var state = world.getBlockState(pos);
        if (canopy || elevatedPlacementStand != null && state.getBlock() instanceof LeavesBlock) {
            if (!canClearNaturalLeaves(client, pos)) return true;
        } else if (!BaritoneSettings.recoverableTerrain(state.getBlock())) return true;
        return client.world.getPlayers().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                && player.getBlockPos().down().equals(pos));
    }

    static void tick(MinecraftClient client, LocalFormation.Peer here) {
        if (!enabled()) return;
        observedDeath = false;
        releaseJump(client);
        long now = System.nanoTime();
        if (client.player.isAlive()) WorkerCargoRecovery.snapshotInventory(client, recentInventory);
        if (enrollment.matches(here.name(), world(here)) && client.player.isAlive() && client.player.isOnGround()
                && !client.player.isTouchingWater() && idleSurfaceGoal == null && !MiningTask.retracingSurfaceTrail()
                && (returnWaypoints == null || waypoint >= returnWaypoints.size())) {
            var feet = client.player.getBlockPos();
            if (knownTrailFooting(client, feet)) returnTrail.record(routePoint(feet));
            if (now >= nextTrailSave) {
                saveTrail();
                nextTrailSave = now + seconds(15);
            }
        }
        if (assignment == null) {
            if (idleSince == 0) idleSince = now;
            boolean busy = true;
            if (recoverBreathing(client)) status = "Swimming toward air";
            else if (TaskDefense.evade(client, true)) {
                idleRecovery.reset();
                status = TaskDefense.evasionStatus();
            } else if (TaskDefense.defendProject(client)) {
                idleRecovery.reset();
                status = "Defending or recovering";
            } else {
                if (cargoRecovery.pending()) stopIdleAscent(client);
                busy = cargoRecovery.tick(client, cargoPath());
                if (busy) {
                    idleRecovery.reset();
                    status = "Recovering dropped project cargo";
                }
            }
            if (busy) stopIdleAscent(client);
            else if (!pending && pickupRouteTool(client)) busy = true;
            else if (prepareIdleRouteTool(client, now)) busy = true;
            else if ((!pending || idleSurfaceGoal != null) && followIdleAscent(client, here, now)) {
                if (!pending && now >= nextRequest) request(client, here, "claim-return-cargo", now);
                return;
            }
            else if (!pending && idleRecovery.yieldWhileIdle(client)) {
                status = "Yielding the work area";
                busy = true;
            }
            if (busy) {
                waitingForSafety = true;
                if (!pending && now >= nextSafetyReport) {
                    nextSafetyReport = now + seconds(10);
                    request(client, here, "safety-wait", now);
                }
                return;
            }
            if (waitingForSafety) {
                waitingForSafety = false;
                status = "Waiting for project jobs";
            }
            if (!pending && now >= nextRequest) request(client, here, "claim", now);
            return;
        }
        idleSince = nextIdleAscent = nextIdleToolCheck = 0;
        if (!assignment.world().equals(world(here)) || now >= deadline) {
            generation++;
            reset(client);
            nextRequest = now + seconds(1);
            status = "Project lease lost, waiting for a new assignment";
            return;
        }
        if (!pending && now >= nextRequest) request(client, here, success == null ? "renew" : "finish", now);
        readyToBank = false;
        if (recoverBreathing(client)) return;
        if (success != null) {
            TaskDefense.evade(client, false);
            return;
        }
        if (production != null) {
            if (assignment.job().lease().equals(interruptedSmeltingLease)) {
                interruptedSmeltingLease = null;
                var result = production.failedIfSmeltingInputLost(client);
                if (result != null) {
                    finish(client, result.success(), result.detail());
                    return;
                }
            }
            carriedUnstartedSmeltingInput = production.carryingUnstartedSmeltingInput(client);
        }
        if (now >= taskEnd) {
            if (pendingProductionResult != null) {
                finish(client, pendingProductionResult.success(), pendingProductionResult.detail());
                return;
            }
            String reason = craftingRouteDelivery == null ? ProjectQueue.TARGET_TIMEOUT
                    : ProjectQueue.ROUTE_PREPARATION_FAILED + "timed out sourcing or crafting the item";
            finish(client, false, reason);
            return;
        }
        if (TaskDefense.evade(client, true)) {
            releaseCentering(client);
            jumpEnds = 0;
            status = TaskDefense.evasionStatus();
            return;
        }
        if (!centering && TaskDefense.defendProject(client)) {
            if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
            return;
        }
        if (workbenchPickup != null) {
            status = "Picking up the portable workbench";
            if (workbenchPickup.tick(client, now)) return;
            workbenchPickup.close(client);
            workbenchPickup = null;
            if (pendingProductionResult != null) {
                var result = pendingProductionResult;
                pendingProductionResult = null;
                finish(client, result.success(), result.detail());
                return;
            }
        }
        if (pickupRouteTool(client)) return;
        if (fieldForage != null) {
            var result = fieldForage.tick(client);
            status = "Foraging for " + craftingRouteDelivery.recipient();
            if (result == FieldForage.Result.WORKING) return;
            fieldForage.close();
            fieldForage = null;
            if (result == FieldForage.Result.READY) startRouteDelivery(client, craftingRouteDelivery);
            else finish(client, false, "Could not collect food from the reserved animal");
            craftingRouteDelivery = null;
        } else if (routeWorkbench != null) {
            var result = routeWorkbench.tick(client, now, port);
            status = routeWorkbench.status();
            if (result == null) return;
            routeWorkbench.close(client);
            routeWorkbench = null;
            if (result.success()) startRouteDelivery(client, craftingRouteDelivery);
            else finish(client, false, ProjectQueue.ROUTE_PREPARATION_FAILED + result.detail());
            craftingRouteDelivery = null;
        } else if (deliveringRouteTool) {
            BotInventory.tick(client);
            status = BotInventory.status();
            if (!BotInventory.working()) {
                boolean dropped = BotInventory.deliveredItems() > 0;
                finish(client, dropped, dropped ? "Dropped the requested resupply beside the worker" : status);
            }
        } else if (miningRecipe != null && gather == null) {
            tickMiningCraft(client, now);
        } else if (gather != null) {
            if (stickPreparation != null) {
                if (!gatherProgress(client, client.player.getInventory().count(Items.STICK), now)) {
                    finish(client, false, ProjectQueue.GATHER_STALLED);
                    return;
                }
                var result = stickPreparation.tick(client, now, port);
                status = assignment.project() + "/" + assignment.job().work().id() + ": " + stickPreparation.status();
                var observation = MiningTask.observedSite();
                if (observation != null) gatherSite = observation;
                if (result == null) return;
                stickPreparation.close(client);
                stickPreparation = null;
                if (result.success()) {
                    gatherSite = null;
                    finish(client, true, ProjectQueue.PREPARED_COMPATIBLE_STICKS);
                    status = ProjectQueue.PREPARED_REQUIRED_STICKS;
                }
                return;
            }
            int held = gather.inventoryCount(client);
            if (checkpointAt == 0 && assignment.cargoCheckpoint() > 0) {
                checkpointAt = held <= assignment.previousCheckpointCargo()
                        ? held + assignment.cargoCheckpoint() : assignment.cargoCheckpoint();
            }
            if (checkpointAt > 0 && held > cargoHighWater) {
                cargoHighWater = held;
                lastCargoIncrease = now;
            }
            if (!checkedCarriedFuel && held < gather.count() && assignment.fuelFor() != null) {
                checkedCarriedFuel = true;
                var fuel = ProjectProduction.alternativeFuel(client, assignment.fuelFor());
                if (fuel != null) {
                    finish(client, true, ProjectQueue.ALTERNATIVE_FUEL + fuel);
                    return;
                }
            }
            boolean lowHealth = client.player.getHealth() <= client.player.getMaxHealth() / 2;
            boolean deep = client.player.getBlockY() <= 8;
            boolean wood = checkpointAt > 0 && ResourceCatalog.isWood(gather.resource());
            boolean drySpell = deep && !wood && lastCargoIncrease != 0 && now - lastCargoIncrease >= seconds(60);
            if (held >= gather.count()) {
                finish(client, true, "Observed " + held + " " + gather.item() + " in worker inventory");
            } else if (checkpointAt > 0 && held > assignment.previousCheckpointCargo() && (deep || wood)
                    && (held >= checkpointAt || lowHealth || drySpell)) {
                if (held < checkpointAt) BotEvents.add("info", "projects", lowHealth
                        ? "Banking gathered cargo early at low health"
                        : "Banking mining cargo after 60 seconds without a new drop");
                finish(client, false, ProjectQueue.CARGO_CHECKPOINT);
            } else if (miningRecipe != null) {
                tickMiningCraft(client, now);
            } else if (!gatherProgress(client, held, now)) {
                finish(client, false, ProjectQueue.GATHER_STALLED);
            } else if (pickup != null) {
                var result = pickup.tick(client, gather.item()::matches, null);
                if (result == DroppedItemSearch.State.UNAVAILABLE) finish(client, false, "Baritone unavailable for pickup");
                else status = (result == DroppedItemSearch.State.SEARCHING ? "Looking for dropped " : "Collecting ")
                        + gather.item() + " (" + held + "/" + gather.count() + ")";
            } else if (prepareMiningWeapon(client, now) || prepareProspectingPickaxe(client, now)) {
                pausedForMiningCraft = MiningTask.working();
                if (pausedForMiningCraft) MiningTask.pause(true);
            } else if (!MiningTask.active()) {
                MiningTask.startProject(client, gather.mining(port), gather.exclude(), gatherSite, gather.item(),
                        assignment.cargoCheckpoint());
                if (!MiningTask.active()) finish(client, false, MiningTask.lastStatus());
            } else {
                if (pausedForMiningCraft) {
                    MiningTask.pause(false);
                    pausedForMiningCraft = false;
                }
                status = assignment.project() + "/" + assignment.job().work().id() + " (" + held + "/" + gather.count()
                        + "): " + MiningTask.status();
                MiningTask.tick(client);
                var observation = MiningTask.observedSite();
                if (observation != null) gatherSite = observation;
                if (MiningTask.toolShortfall()) {
                    if (MiningTask.routeToolShortfall() && startMiningCraft(client, now, ROUTE_PICKAXE, gather.exclude())) {
                        MiningTask.stop();
                    } else finish(client, false, ProjectQueue.GATHER_TOOLS_NEEDED);
                }
            }
        } else if (production != null) {
            var result = block == null ? production.tick(client, now) : production.beforeApproach(client, now);
            if (result != null) finishProduction(client, result, now);
            else if (block != null && !prepareFieldWorkstation(client, now)) execute(client, now);
        } else execute(client, now);
    }

    private static boolean recoverBreathing(MinecraftClient client) {
        boolean wasSwimmingAshore = breathing.reachingShore();
        boolean stationApproach = success == null && block != null && (production != null || chest != null);
        if (!breathing.tick(client, stationApproach)) {
            if (wasSwimmingAshore && BreathingRecovery.onDryFooting(client)) {
                halt(client);
                nextWorkstationSearch = retryApproach = 0;
            }
            return false;
        }
        releaseCentering(client);
        jumpEnds = 0;
        status = breathing.reachingShore() ? "Swimming to dry footing" : "Swimming toward air";
        return true;
    }

    private static boolean pickupRouteTool(MinecraftClient client) {
        if ((needsRouteBench || needsProductionBench) && client.player.getInventory().contains(net.minecraft.item.Items.CRAFTING_TABLE.getDefaultStack())) {
            needsRouteBench = needsProductionBench = false;
            receivedRouteBench = true;
            stationToolAttempt = null;
        }
        boolean needsSticks = needsRouteSticks && client.player.getInventory().count(Items.STICK) < 2
                || stickPreparation != null && stickPreparation.needsSticks(client);
        boolean needsMeal = TaskDefense.requestsMeal(client);
        if (!needsSticks && !needsMeal && !needsProductionBench && (!needsRouteTool || SubsurfaceAccess.canDigRoute(client))) {
            if (pickingUpRouteTool) {
                routeToolPickup.reset();
                pickingUpRouteTool = false;
            }
            return false;
        }
        if (!pickingUpRouteTool) {
            var player = client.player;
            if (client.world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                    player.getBoundingBox().expand(TOOL_DROP_RADIUS),
                    entity -> entity.isAlive() && usableRouteTool(entity.getStack(), needsSticks, needsMeal)).isEmpty()) return false;
            if (assignment == null) stopIdleAscent(client);
            if (stickPreparation != null) stickPreparation.pauseGathering();
            else halt(client);
            pickingUpRouteTool = true;
        }
        if (routeToolPickup.tick(client, stack -> usableRouteTool(stack, needsSticks, needsMeal), null)
                == DroppedItemSearch.State.APPROACHING) {
            status = assignment == null ? "Collecting emergency supplies"
                    : assignment.project() + "/" + assignment.job().work().id() + ": Collecting emergency supplies";
            return true;
        }
        pickingUpRouteTool = false;
        return false;
    }

    private static boolean usableRouteTool(net.minecraft.item.ItemStack stack, boolean needsSticks, boolean needsMeal) {
        return needsSticks && stack.isOf(Items.STICK)
                || needsMeal && TaskDefense.emergencyFood(stack.getItem())
                || (needsRouteBench || needsProductionBench) && stack.isOf(net.minecraft.item.Items.CRAFTING_TABLE)
                || needsRouteTool && stack.getItem() instanceof net.minecraft.item.PickaxeItem
                        && stack.getMaxDamage() - stack.getDamage() >= SubsurfaceAccess.MIN_ROUTE_TOOL_USES;
    }

    private static boolean prepareIdleRouteTool(MinecraftClient client, long now) {
        if (workbenchPickup != null) {
            if (workbenchPickup.tick(client, now)) {
                status = "Recovering the portable workbench";
                return true;
            }
            workbenchPickup.close(client);
            workbenchPickup = null;
        }
        if (miningRecipe != null) {
            tickMiningCraft(client, now);
            return true;
        }
        if (pending || idleSurfaceGoal != null || MiningTask.active() || now - idleSince < seconds(6)
                || now < nextIdleToolCheck || SubsurfaceAccess.canDigRoute(client)
                || !client.world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)
                || !SubsurfaceAccess.hasFooting(client)) return false;
        nextIdleToolCheck = now + seconds(30);
        var inventory = client.player.getInventory();
        if (!inventory.contains(Items.CRAFTING_TABLE.getDefaultStack())) return false;
        int stone = inventory.count(Items.COBBLESTONE) + inventory.count(Items.COBBLED_DEEPSLATE)
                + inventory.count(Items.BLACKSTONE);
        if (stone < 6 || MiningTask.localSurfaceExit(client, client.player.getBlockPos(), null) == null) return false;
        return startMiningCraft(client, now, ROUTE_PICKAXE, null);
    }

    private static boolean prepareMiningWeapon(MinecraftClient client, long now) {
        if (now < nextMiningWeaponCheck || !TaskDefense.enabled()) return false;
        nextMiningWeaponCheck = now + seconds(10);
        if (!ResourceCatalog.isOre(gather.resource()) || !SubsurfaceAccess.hasFooting(client)
                || client.player.isTouchingWater() || client.player.currentScreenHandler != client.player.playerScreenHandler) return false;
        var ore = ResourceCatalog.blocks(gather.resource(), gather.variant()).get(0);
        if (!MiningTask.hasTool(client, Registries.BLOCK.get(new Identifier(ore)).getDefaultState())) return false;
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            var item = inventory.getStack(slot).getItem();
            if (item instanceof net.minecraft.item.SwordItem || item instanceof net.minecraft.item.AxeItem) return false;
        }
        return startMiningCraft(client, now, MINING_SWORD, gather.exclude());
    }

    private static boolean isPickaxeRecipe(Identifier recipe) {
        return recipe.equals(ROUTE_PICKAXE) || recipe.equals(WOODEN_PICKAXE);
    }

    private static boolean prepareProspectingPickaxe(MinecraftClient client, long now) {
        if (now < nextRoutePickaxeCheck || !MiningTask.seekingDeepOre(client)) return false;
        nextRoutePickaxeCheck = now + seconds(15);
        return !SubsurfaceAccess.hasBasicRoutePickaxe(client)
                && SubsurfaceAccess.canDigRoute(client)
                && startMiningCraft(client, now, ROUTE_PICKAXE, gather.exclude());
    }

    private static boolean startMiningCraft(MinecraftClient client, long now, Identifier recipe, ProjectGather.Area excluded) {
        if (ProjectProduction.canCraft(client, recipe)) {
            fieldWorkstation = FieldWorkstation.findForMiningTool(client, now, excluded);
            if (fieldWorkstation == null) return false;
        } else {
            if (!ProjectProduction.canCraft(client, recipe, new net.minecraft.item.ItemStack(Items.STICK, 4))) return false;
            ProjectProduction.Spec ingredients;
            if (ProjectProduction.canCraft(client, ROUTE_STICKS)) {
                int sticks = client.player.getInventory().count(Items.STICK);
                ingredients = new ProjectProduction.Spec(null, ProjectQueue.Kind.CRAFT, ROUTE_STICKS, null, null, sticks + 1);
            } else {
                ingredients = FieldWorkstation.plankRecipe(client, 2);
                if (ingredients == null) return false;
            }
            miningCraft = new ProjectProduction(ingredients);
            preparingMiningIngredients = true;
            miningCraftArea = excluded;
        }
        miningRecipe = recipe;
        return true;
    }

    private static void tickMiningCraft(MinecraftClient client, long now) {
        var delivery = craftingRouteDelivery;
        var recipe = miningRecipe;
        boolean replacement = isPickaxeRecipe(recipe);
        boolean excavation = assignment != null && assignment.job().work().kind() == ProjectQueue.Kind.EXCAVATE;
        if (preparingMiningIngredients) {
            status = replacement ? "Preparing materials for a replacement pickaxe" : "Preparing materials for a stone sword";
            var ingredients = miningCraft.tick(client, now);
            if (ingredients == null) return;
            miningCraft.close(client);
            miningCraft = null;
            preparingMiningIngredients = false;
            var area = miningCraftArea;
            miningCraftArea = null;
            miningRecipe = null;
            if (ingredients.success() && startMiningCraft(client, now, recipe, area)) return;
            if (delivery != null) finish(client, false, ProjectQueue.ROUTE_PREPARATION_FAILED + "could not prepare pickaxe ingredients");
            else if (replacement && gather != null && !SubsurfaceAccess.canDigRoute(client)) {
                finish(client, false, ProjectQueue.GATHER_TOOLS_NEEDED);
            }
            else if (replacement && ingredients.success()) stationToolAttempt = null;
            return;
        }
        if (miningCraft == null) {
            var state = fieldWorkstation.tick(client, now);
            if (state == FieldWorkstation.State.WORKING) {
                status = replacement ? "Setting up a workbench to replace a worn pickaxe"
                        : "Setting up a workbench for a mining weapon";
                return;
            }
            var station = fieldWorkstation.station();
            if (delivery == null && fieldWorkstation.placedByWorker(client)) portableWorkbench = station;
            fieldWorkstation.close(client);
            fieldWorkstation = null;
            if (state != FieldWorkstation.State.READY) {
                miningRecipe = null;
                if (delivery != null) finish(client, false, ProjectQueue.ROUTE_PREPARATION_FAILED + "could not prepare a workbench");
                else if (replacement && gather != null && !SubsurfaceAccess.canDigRoute(client)) {
                    finish(client, false, ProjectQueue.GATHER_TOOLS_NEEDED);
                }
                return;
            }
            miningCraft = new ProjectProduction(new ProjectProduction.Spec(station, ProjectQueue.Kind.CRAFT,
                    miningRecipe, null, null, delivery == null ? 1 : delivery.craftTarget()));
        }
        status = !replacement ? "Crafting a stone sword for mining"
                : delivery != null ? "Crafting a replacement pickaxe for " + delivery.recipient()
                : excavation ? "Crafting a pickaxe for excavation"
                : gather == null ? "Crafting a pickaxe for the return route" : "Crafting a replacement prospecting pickaxe";
        var result = miningCraft.tick(client, now);
        if (result == null) return;
        miningCraft.close(client);
        miningCraft = null;
        miningRecipe = null;
        if (portableWorkbench != null) {
            workbenchPickup = new WorkbenchPickup(portableWorkbench.target(), now, client);
            portableWorkbench = null;
        }
        if (delivery != null) {
            craftingRouteDelivery = null;
            if (result.success()) startRouteDelivery(client, delivery);
            else finish(client, false, ProjectQueue.ROUTE_PREPARATION_FAILED + result.detail());
        } else if (replacement) {
            if (result.success() && SubsurfaceAccess.canDigRoute(client)) {
                gatherProgress.recordPreparation(now);
                BotEvents.add("info", "mining", excavation ? "Crafted a pickaxe for excavation"
                        : gather == null ? "Crafted a pickaxe for the return route" : "Crafted a replacement prospecting pickaxe");
            } else if (gather != null && !SubsurfaceAccess.canDigRoute(client)) {
                finish(client, false, ProjectQueue.GATHER_TOOLS_NEEDED);
            }
        } else if (result.success()) BotEvents.add("info", "defense", "Crafted a stone sword for mining");
    }

    private static int freshToolCount(MinecraftClient client, net.minecraft.item.Item item) {
        var inventory = client.player.getInventory();
        var output = item.getDefaultStack();
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (net.minecraft.item.ItemStack.canCombine(inventory.getStack(slot), output)) count++;
        }
        return count;
    }

    private static void startRouteDelivery(MinecraftClient client, ProjectQueue.RouteToolDelivery delivery) {
        var here = LocalFormation.here();
        var position = delivery.position();
        var destination = new BotInventory.Destination(here.server(), here.dimension(),
                new Vec3d(position.x() + .5, position.y(), position.z() + .5), port);
        var item = Registries.ITEM.get(new Identifier(delivery.item()));
        var lease = new BotInventory.DeliveryLease(assignment.project(), assignment.job().work().id(), assignment.job().lease());
        BotInventory.startProjectDelivery(client, item, delivery.quantity(), delivery.recipient(),
                destination, delivery.waypoints(), lease);
        deliveringRouteTool = true;
        taskEnd = System.nanoTime() + seconds(180);
    }

    private static boolean prepareFieldWorkstation(MinecraftClient client, long now) {
        if (resumesLoadedFurnace) return false;
        var kind = assignment.job().work().kind();
        String stationName = kind == ProjectQueue.Kind.SMELT ? "furnace" : "workbench";
        if (fieldStoneUntil != 0) {
            if (now < fieldStoneUntil && production.carryingUnstartedSmeltingInput(client)
                    && MiningTask.active() && !MiningTask.toolShortfall()
                    && !production.canPrepareFieldFurnace(client)) {
                status = assignment.project() + "/" + assignment.job().work().id() + ": Gathering stone for a field furnace";
                MiningTask.tick(client);
                return true;
            }
            MiningTask.stop();
            fieldStoneUntil = nextWorkstationSearch = 0;
        }
        if (fieldWorkstation == null && now >= nextWorkstationSearch
                && FieldWorkstation.shouldUseFieldStation(block.target(), client.player.getBlockPos())) {
            nextWorkstationSearch = now + seconds(8);
            var nearby = FieldWorkstation.find(client, now, kind, production, rejectedFieldWorkbenches);
            needsProductionBench = nearby == null && production.canUsePortableWorkbench(client);
            if (nearby != null) {
                halt(client);
                fieldWorkstation = nearby;
            } else if (kind == ProjectQueue.Kind.SMELT && startFieldStoneGather(client, now)) {
                return true;
            }
        }
        if (fieldWorkstation == null) return false;
        var state = fieldWorkstation.tick(client, now);
        if (state == FieldWorkstation.State.WORKING) {
            status = assignment.project() + "/" + assignment.job().work().id()
                    + (fieldWorkstation.walkingToExistingWorkbench() ? ": Walking to a nearby " : ": Setting up a nearby ")
                    + stationName;
            return true;
        }
        if (state == FieldWorkstation.State.READY) {
            productionWorkbench = fieldWorkstation.placedWorkbench(client);
            block = fieldWorkstation.station();
            production = production.atStation(block);
            interactionStand = null;
            interactionGoals = List.of();
            rejectedInteractionStands.clear();
            BotEvents.add("info", "projects", "Using nearby " + stationName + " at " + block.target().toShortString());
        } else if (fieldWorkstation.walkingToExistingWorkbench()) {
            rejectedFieldWorkbenches.add(fieldWorkstation.station().target());
        }
        fieldWorkstation.close(client);
        fieldWorkstation = null;
        nextWorkstationSearch = now + seconds(15);
        return false;
    }

    private static boolean startFieldStoneGather(MinecraftClient client, long now) {
        if (now < nextFieldStoneSearch || !production.carryingUnstartedSmeltingInput(client)
                || client.player.getInventory().contains(Items.FURNACE.getDefaultStack())
                || !SubsurfaceAccess.hasFooting(client) || client.player.isTouchingWater()
                || client.player.currentScreenHandler != client.player.playerScreenHandler
                || !SubsurfaceAccess.canDigRoute(client) || production.canPrepareFieldFurnace(client)) {
            return false;
        }
        nextFieldStoneSearch = now + seconds(90);
        var resource = ResourceCatalog.FURNACE_STONE;
        if (!MiningTask.hasExposedLocalBlock(client, resource)) return false;
        halt(client);
        var area = new ProjectGather.Area(block.target(), block.target());
        MiningTask.startLocalProject(client, new MiningTask.Job(resource, "both", port), area, ProjectItem.parse(resource));
        if (!MiningTask.active()) return false;
        fieldStoneUntil = now + seconds(35);
        BotEvents.add("info", "projects", "Gathering local stone for a field furnace");
        return true;
    }

    private static boolean gatherProgress(MinecraftClient client, int held, long now) {
        var feet = client.player.getBlockPos();
        return gatherProgress.active(feet.getX(), feet.getY(), feet.getZ(),
                gatherAtSurface, MiningTask.climbingToSurface() && SubsurfaceAccess.hasFooting(client),
                held, now, seconds(90));
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
        boolean verify = kind == ProjectQueue.Kind.VERIFY;
        if ((kind == ProjectQueue.Kind.EXCAVATE || kind == ProjectQueue.Kind.BUILD || kind == ProjectQueue.Kind.FILL_BUCKETS)
                && BuildProtection.blocked(assignment.world(), target, assignment.project())) {
            finish(client, false, "Block is in an exclusion zone at " + target.toShortString());
            return;
        }
        boolean targetLoaded = client.world.getChunkManager().isChunkLoaded(target.getX() >> 4, target.getZ() >> 4);
        if ((excavate || build || verify) && targetLoaded && client.world.isInBuildLimit(target)) {
            var observed = client.world.getBlockState(target);
            if (excavate ? observed.isAir() : block.matches(observed)) {
                // Loaded state can prove the result without reaching a stand used for physical work.
                if (approach != null || breaking || centering) halt(client);
                if (stableSince == 0) stableSince = now;
                if (now - stableSince >= seconds(1)) finish(client, true, blockObservation(observed));
                return;
            }
        }
        if (excavate || build || verify) stableSince = 0;
        var currentCell = player.getBlockPos();
        boolean reachedAlternative = interactionGoals.contains(currentCell);
        if (reachedAlternative) interactionStand = currentCell;
        BlockPos stand = interactionStand == null ? block.stand() : interactionStand;
        boolean canExcavateHere = excavate && targetLoaded && player.isOnGround() && !centering
                && !player.getBlockPos().down().equals(target) && excavationHit(client, target) != null;
        boolean ordinaryBuild = build && !block.edgePlacement();
        if (jumpEnds != 0 && tickPlacementJump(client, now)) return;
        boolean canBuildHere = ordinaryBuild && targetLoaded && player.isOnGround() && !centering
                && !player.getBoundingBox().intersects(new Box(target))
                && ProjectPlacement.supportHit(client, block) != null;
        if (ordinaryBuild && targetLoaded && !canBuildHere && followWaypoints(client, now, assignment.accessRoute(), true)) return;
        if (ordinaryBuild && targetLoaded && !canBuildHere && player.isOnGround() && !centering
                && !player.isSneaking() && client.world.isAir(target) && canJumpToPlace(client, player.getPos(), currentCell)) {
            halt(client);
            triedJumpStands.add(currentCell);
            player.setSprinting(false);
            client.options.jumpKey.setPressed(true);
            jumpPressed = true;
            jumpEnds = now + seconds(1);
            nextPlacement = now;
            status = assignment.project() + "/" + work.id() + ": Jumping to reach the placement face";
            return;
        }
        boolean stationJob = production != null || chest != null;
        boolean stationNearby = targetLoaded && (production != null && production.reachableFromHere(client)
                || chest != null && chest.reachableFromHere(client));
        boolean needsStationApproach = stationJob && targetLoaded && !stationNearby
                && block.matches(client.world.getBlockState(target));
        boolean belowStation = stationJob && stand.getY() - player.getBlockY() >= 5;
        boolean searchingForStand = excavate && !canExcavateHere || ordinaryBuild && !canBuildHere
                && client.world.getBlockState(target).isAir()
                || needsStationApproach;
        if (searchingForStand && (excavate || ordinaryBuild) && targetLoaded && currentCell.equals(stand)
                && player.isOnGround() && canCenterForInteraction(client, target, stand, kind)) {
            if (approach != null) halt(client);
            centerOnStand(client, Vec3d.ofBottomCenter(stand));
            return;
        }
        if (needsStationApproach && currentCell.equals(stand) && player.isOnGround()
                && usableInteractionStand(client, stand, target)) {
            var center = Vec3d.ofBottomCenter(stand);
            if (ProjectProduction.workstationHit(client, target, center.add(0, 1.62, 0)) != null) {
                if (approach != null) halt(client);
                if (centerOnStand(client, center)) return;
            }
        }
        boolean routeFailed = approach != null && now >= retryApproach && !BaritoneClient.pursuing(stand);
        if (needsStationApproach && tryLowerStationApproach(client, stand, routeFailed, now)) {
            approachAge++;
            return;
        }
        int riseToStation = stand.getY() - currentCell.getY();
        boolean shortStationClimb = needsStationApproach && riseToStation > 0 && riseToStation < 5
                && (stationReturn.active() || stationReturn.continuingLocalAscent() || stationReturn.openingReturnStep()
                    || routeFailed && SubsurfaceAccess.hasFooting(client) && !client.world.isSkyVisible(currentCell.up()));
        if (shortStationClimb && tryElevatedApproach(client, stand, now, false, true)) {
            approachAge++;
            return;
        }
        if (ordinaryBuild && targetLoaded && !canBuildHere && client.world.isAir(target)
                && (openingJumpHeadroom != null || !attemptedJumpHeadroom && (routeFailed || reachedAlternative))
                && openJumpHeadroom(client, currentCell)) return;
        if (elevatedPlacementStand != null && !canBuildHere
                && (routeFailed && recoveryOrigin != null || now >= lowerPlacementEnds)) {
            halt(client);
            interactionStand = stand = elevatedPlacementStand;
            elevatedPlacementStand = null;
            interactionGoals = List.of();
            routeFailed = false;
        }
        boolean belowPlacement = ordinaryBuild && targetLoaded && !canBuildHere && client.world.isAir(target)
                && stand.getY() - currentCell.getY() >= 3 && currentCell.isWithinDistance(stand, 16);
        if (belowPlacement && !triedLowerPlacement && (routeFailed || stationReturn.active())) {
            triedLowerPlacement = true;
            var lower = nextInteractionStands(client, target, stand, kind, currentCell.getY() + 1);
            if (!lower.isEmpty()) {
                halt(client);
                elevatedPlacementStand = stand;
                lowerPlacementEnds = now + seconds(20);
                interactionStand = stand = lower.get(0);
                interactionGoals = lower;
                belowPlacement = routeFailed = false;
                BotEvents.add("info", "movement", "Trying a lower stand for the placement face");
            }
        }
        if ((canopyApproachEnds != 0 || belowPlacement)
                && tryCanopyApproach(client, stand, now, searchingForStand)) return;
        if (belowPlacement && tryElevatedApproach(client, stand, now, true, false)) {
            approachAge++;
            return;
        }
        if (ordinaryBuild && !belowPlacement && stationReturn.active()) stationReturn.stop();
        // Try a protected exit once after both the planned and an alternate approach fail.
        boolean localStation = stationJob && currentCell.isWithinDistance(target, 12);
        boolean recoverBeforeSwitching = (excavate || ordinaryBuild || localStation) && interactionStand != null
                && recoveryOrigin == null && routeFailed;
        if (searchingForStand && elevatedPlacementStand == null && !recoverBeforeSwitching && !belowStation && !stationReturn.active()
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
                interactionGoals = alternatives;
                status = assignment.project() + "/" + work.id() + ": Approaching from another side";
            }
        }
        Vec3d feet = Vec3d.ofBottomCenter(stand);
        boolean inspectNearby = verify && targetLoaded
                && player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(target)) <= 16;
        boolean exactFooting = excavate || kind == ProjectQueue.Kind.BUILD;
        boolean edge = kind == ProjectQueue.Kind.BUILD && block.edgePlacement();
        boolean nearStand = player.getPos().squaredDistanceTo(feet) <= 1.21;
        boolean canCleanFromHere = nearStand && work.cleanup() != null && targetLoaded
                && (client.world.getBlockState(target).isAir() || excavationHit(client, target) != null);
        boolean atStand = nearStand && (!exactFooting || player.getBlockPos().equals(stand) || canCleanFromHere
                || edge && centering && player.getBlockPos().equals(target.up()));
        boolean onFooting = stationJob ? SubsurfaceAccess.hasFooting(client) : player.isOnGround();
        if (!onFooting || needsStationApproach
                || !atStand && !inspectNearby && !stationNearby && !canExcavateHere && !canBuildHere) {
            releaseCentering(client);
            stableSince = 0;
            approachAge++;
            if (stationJob && belowStation && returnWaypoints == null) {
                returnWaypoints = returnTrail.backtrack(routePoint(currentCell), routePoint(stand));
                if (!returnWaypoints.isEmpty()) {
                    BotEvents.add("info", "movement", "Retracing " + returnWaypoints.size() + " recorded worker waypoints");
                }
            }
            if (stationJob && returnWaypoints != null && followWaypoints(client, now, returnWaypoints, false)) {
                if (currentCell.getY() >= stationReturnHeight + 4) {
                    stationReturnHeight = currentCell.getY();
                    taskEnd = Math.max(taskEnd, Math.min(stationReturnDeadline, now + seconds(90)));
                }
                return;
            }
            if (stationJob && tryElevatedApproach(client, stand, now, false, false)) return;
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
                    if (approach != null && recoveryOrigin == null && (localWork || localStation)
                            && !BaritoneClient.pursuing(pos)) {
                        BaritoneClient.execute("stop");
                        approach.close();
                        approach = null;
                        if (localStation) approach = BaritoneSettings.explorationExit(SubsurfaceAccess.canDigRoute(client));
                        else approach = elevatedPlacementStand == null ? new BaritoneSettings(true, true, true)
                                : BaritoneSettings.lowerStandApproach();
                        recoveryOrigin = feetBlock;
                        String detail = elevatedPlacementStand == null ? "Opening a short approach through natural terrain"
                                : "Opening protected terrain to reach the lower placement stand";
                        BotEvents.add("info", "movement", detail);
                    }
                    if (approach == null) approach = new BaritoneSettings(false, true);
                    approachProgress = current;
                    retryApproach = now + seconds(5);
                    var goals = interactionGoals.isEmpty() ? List.of(pos) : interactionGoals;
                    if (!BaritoneClient.pursuing(pos) && !approachProjectStand(client, goals)) {
                        finish(client, false, "Baritone could not start project approach");
                    }
                } catch (ReflectiveOperationException error) {
                    finish(client, false, "Baritone unavailable for project approach");
                }
            }
            return;
        }
        if (approach != null || stationReturn.active() || stationReturn.openingReturnStep() || stationDescentSteps > 0) halt(client);
        else if (stationJob) releaseCentering(client);
        stationDescentSteps = 0;
        needsRouteSticks = false;
        reportingRouteSticks = acknowledgedRouteSticks = false;
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
            if (!inspection.loaded(client.world)) {
                inspectionStarted = stableSince = 0;
                status = "Waiting for the inspection chunk";
                return;
            }
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
            if (travelingContribution) {
                readyToBank = true;
                status = "Waiting for the project chest reservation";
                return;
            }
            var result = chest.tick(client, now);
            if (result != null) {
                if (result.stock() != null) chestReport = new ProjectQueue.ChestReport(ProjectBlock.key(target), result.stock(), result.free());
                if (work.kind() == ProjectQueue.Kind.RESUPPLY) {
                    chest.close(client);
                    chest = null;
                    block = null;
                    if (result.success()) {
                        routeWorkbench = new PortableWoodPreparation();
                        taskEnd = now + seconds(90);
                    } else finish(client, false, ProjectQueue.ROUTE_PREPARATION_FAILED + result.detail());
                } else {
                    pickupShortfall = result.pickupShortfall();
                    finish(client, result.success(), result.detail());
                }
            }
            return;
        }
        if (production != null) {
            var result = production.tick(client, now);
            if (result != null) finishProduction(client, result, now);
            return;
        }
        var state = client.world.getBlockState(target);
        var actual = Registries.BLOCK.getId(state.getBlock());
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
        if (verify) {
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
            boolean temporaryCobblestone = work.cleanup() != null && work.cleanup().site().equals(ProjectBlock.key(target))
                    && state.isOf(Blocks.COBBLESTONE);
            // Excavation only needs the cell cleared. Vanilla's unsuitable-tool penalty still applies.
            boolean clearsWithinTwoSeconds = state.calcBlockBreakingDelta(player, client.world, target) >= 1f / 40;
            if (slot == 36 && !clearsWithinTwoSeconds) {
                if (!checkedExcavationTool) {
                    checkedExcavationTool = true;
                    var excluded = StructureSupply.union(new ProjectGather.Area(target, target), block);
                    if (net.minecraft.item.Items.STONE_PICKAXE.getDefaultStack().isSuitableFor(state)
                            && startMiningCraft(client, now, ROUTE_PICKAXE, excluded)) return;
                    if (temporaryCobblestone && startMiningCraft(client, now, WOODEN_PICKAXE, excluded)) return;
                }
                if (!temporaryCobblestone) {
                    finish(client, false, ProjectQueue.EXCAVATION_TOOL_NEEDED + actual);
                    return;
                }
            }
            if (slot >= 9 && slot < 36) {
                if (player.currentScreenHandler != player.playerScreenHandler
                        || !player.playerScreenHandler.getCursorStack().isEmpty()) {
                    finish(client, false, "Close the container or clear the cursor to equip an excavation tool");
                    return;
                }
                client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot,
                        inventory.selectedSlot, SlotActionType.SWAP, player);
                return;
            }
            if (slot < 9) inventory.selectedSlot = slot;
        }
        player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, ray.getPos());
        if (BuildProtection.projectAction(assignment.project(),
                () -> client.interactionManager.updateBlockBreakingProgress(target, ray.getSide()))) player.swingHand(Hand.MAIN_HAND);
        breaking = true;
    }

    private static boolean approachProjectStand(MinecraftClient client, List<BlockPos> goals) {
        if (!ProjectQueue.reservedBatchStep(assignment.job())) return BaritoneClient.goToAny(goals);
        var occupied = new HashSet<BlockPos>();
        for (var stand : assignment.peerStands()) {
            var feet = new BlockPos(stand.x(), stand.y(), stand.z());
            occupied.add(feet);
            occupied.add(feet.up());
        }
        for (var peer : client.world.getPlayers()) {
            if (peer == client.player || !peer.isAlive() || peer.isSpectator()) continue;
            var feet = peer.getBlockPos();
            occupied.add(feet);
            occupied.add(feet.up());
        }
        return BaritoneClient.goToCooperativeStand(goals, occupied);
    }

    private static boolean tryCanopyApproach(MinecraftClient client, BlockPos stand, long now, boolean searching) {
        var position = client.player.getBlockPos();
        if (canopyApproachEnds != 0) {
            if (searching && !stand.equals(position) && now < canopyApproachEnds) return true;
            halt(client);
            return false;
        }
        if (!searching || approach == null || now < retryApproach || !position.isWithinDistance(stand, 7)
                || !SubsurfaceAccess.hasFooting(client)
                || !naturalLeaves(client.world.getBlockState(stand.down()))
                || !triedCanopyStands.add(stand)) return false;
        halt(client);
        try {
            approach = BaritoneSettings.canopyApproach();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
        recoveryOrigin = position;
        canopyApproachEnds = now + seconds(12);
        if (!BaritoneClient.goTo(stand)) {
            halt(client);
            return false;
        }
        status = assignment.project() + "/" + assignment.job().work().id() + ": Approaching through natural foliage";
        BotEvents.add("info", "movement", "Opening a protected canopy approach to " + stand.toShortString());
        return true;
    }

    private static boolean followWaypoints(MinecraftClient client, long now,
                                           List<ProjectQueue.ResourceSite> route, boolean scaffold) {
        if (waypoint >= route.size()) return false;
        var feet = client.player.getBlockPos();
        if (SubsurfaceAccess.hasFooting(client)) {
            for (int i = route.size() - 1; i >= waypoint; i--) {
                var step = route.get(i);
                if (feet.getX() == step.x() && feet.getY() == step.y() && feet.getZ() == step.z()) {
                    halt(client);
                    waypoint = i + 1;
                    break;
                }
            }
        }
        if (waypoint >= route.size()) return false;
        var step = route.get(waypoint);
        var next = new BlockPos(step.x(), step.y(), step.z());
        var world = client.world;
        if (!scaffold && !knownTrailFooting(client, next)) {
            halt(client);
            // An edge position can be recorded while the player's body is supported by the next block.
            // Look for the next nearby stand, then let Baritone find a safe path to it.
            for (int i = waypoint + 1; i < route.size(); i++) {
                var candidate = route.get(i);
                var stand = new BlockPos(candidate.x(), candidate.y(), candidate.z());
                if (!stand.isWithinDistance(feet, 12) || Math.abs(stand.getY() - feet.getY()) > 6) break;
                if (!knownTrailFooting(client, stand)) continue;
                waypoint = i;
                next = stand;
                break;
            }
        }
        if (!world.isChunkLoaded(next.getX() >> 4, next.getZ() >> 4)) {
            if (!scaffold) {
                halt(client);
                waypoint = route.size();
            }
            return false;
        }
        boolean valid = scaffold ? world.getBlockState(next.down()).isOf(Blocks.COBBLESTONE)
                && world.isAir(next) && world.isAir(next.up())
                : knownTrailFooting(client, next);
        if (!valid || waypointEnds != 0 && now >= waypointEnds) {
            halt(client);
            waypoint = route.size();
            return false;
        }
        if (waypointEnds == 0) {
            halt(client);
            try {
                approach = new BaritoneSettings(false, true);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                waypoint = route.size();
                return false;
            }
            if (!BaritoneClient.goTo(next)) {
                halt(client);
                waypoint = route.size();
                return false;
            }
            waypointEnds = now + seconds(!scaffold || waypoint == 0 ? 18 : 8);
            retryApproach = now + seconds(3);
        } else if (now >= retryApproach && !BaritoneClient.pursuing(next)) {
            BaritoneClient.goTo(next);
            retryApproach = now + seconds(3);
        }
        String stepLabel = assignment == null ? "Returning to the surface "
                : assignment.project() + "/" + assignment.job().work().id()
                    + (scaffold ? ": Scaffold step " : ": Retracing return route ");
        status = stepLabel + (waypoint + 1) + "/" + route.size();
        return true;
    }

    private static boolean followIdleAscent(MinecraftClient client, LocalFormation.Peer here, long now) {
        var feet = client.player.getBlockPos();
        if (idleSurfaceGoal == null) {
            if (now - idleSince < seconds(6) || now < nextIdleAscent || !enrollment.matches(here.name(), world(here))
                    || !client.world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)
                    || MiningTask.active() || !SubsurfaceAccess.hasFooting(client)) return false;
            nextIdleAscent = now + seconds(30);
            var surface = MiningTask.localSurfaceExit(client, feet, null);
            if (surface == null) return false;
            var route = recordedSurfaceRoute(client, surface);
            idleSurfaceGoal = surface;
            idleAscentDeadline = now + seconds(180);
            idleAscentProgressAt = now;
            idleAscentHeight = feet.getY();
            nextIdleRouteProbe = approachAge = waypoint = 0;
            returnWaypoints = route;
            if (!route.isEmpty()) {
                BotEvents.add("info", "movement", "Idle worker retracing " + route.size() + " waypoints toward the surface");
            }
        }
        if (feet.getY() > idleAscentHeight && SubsurfaceAccess.hasFooting(client)) {
            idleAscentHeight = feet.getY();
            idleAscentProgressAt = now;
        }
        if (feet.getY() >= idleSurfaceGoal.getY() || now >= idleAscentDeadline || now - idleAscentProgressAt >= seconds(35)) {
            stopIdleAscent(client);
            nextIdleAscent = now + seconds(90);
            return false;
        }
        if (returnWaypoints != null) {
            if (followWaypoints(client, now, returnWaypoints, false)) return true;
            returnWaypoints = null;
            if (SubsurfaceAccess.canDigRoute(client)) {
                BotEvents.add("info", "movement", "Idle worker switching to a protected cave ascent");
            }
        }
        approachAge++;
        if (!SubsurfaceAccess.canDigRoute(client)) {
            if (stationReturn.walkTowardSurface(client, approachAge, idleSurfaceGoal)) {
                status = "Following a natural cave exit";
                return true;
            }
            // A checkpoint can finish as the player lands. Give the next step a tick to start.
            if (stationReturn.followingNaturalAscent() || !SubsurfaceAccess.hasFooting(client)) return true;
            stopIdleAscent(client);
            nextIdleAscent = now + seconds(90);
            return false;
        }
        if (approachAge >= nextIdleRouteProbe && !stationReturn.continuePassage(client, approachAge, null)
                && !stationReturn.advanceToward(client, approachAge, idleSurfaceGoal, null, true)) {
            nextIdleRouteProbe = approachAge + 10;
        }
        status = "Opening a protected route to the surface";
        return true;
    }

    private static void stopIdleAscent(MinecraftClient client) {
        if (idleSurfaceGoal == null) return;
        halt(client);
        stationReturn.reset();
        idleSurfaceGoal = null;
        returnWaypoints = null;
        waypoint = 0;
    }

    static List<ProjectQueue.ResourceSite> recordedSurfaceRoute(MinecraftClient client, BlockPos destination) {
        var here = LocalFormation.here();
        if (here == null || !enrollment.matches(here.name(), world(here))) return List.of();
        return returnTrail.backtrackToSurface(routePoint(client.player.getBlockPos()), routePoint(destination));
    }

    static boolean knownTrailFooting(MinecraftClient client, BlockPos pos) {
        var world = client.world;
        return world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                && world.isInBuildLimit(pos.up()) && world.getWorldBorder().contains(pos)
                && !world.getBlockState(pos.down()).isOf(Blocks.MAGMA_BLOCK)
                && ProjectPlacement.flushFloor(world.getBlockState(pos.down()), world, pos.down())
                && world.getFluidState(pos).isEmpty() && world.getFluidState(pos.up()).isEmpty()
                && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty();
    }

    private static boolean naturalLeaves(net.minecraft.block.BlockState state) {
        return state.getBlock() instanceof LeavesBlock && !state.get(LeavesBlock.PERSISTENT);
    }

    static boolean canClearNaturalLeaves(MinecraftClient client, BlockPos pos) {
        var world = client.world;
        var state = world.getBlockState(pos);
        if (!naturalLeaves(state) || !state.getFluidState().isEmpty()
                || world.getBlockState(pos.up()).getBlock() instanceof FallingBlock) return false;
        for (var side : Direction.values()) {
            var neighbor = pos.offset(side);
            if (!world.isChunkLoaded(neighbor.getX() >> 4, neighbor.getZ() >> 4)
                    || !world.getFluidState(neighbor).isEmpty()) return false;
        }
        return true;
    }

    private static boolean tryLowerStationApproach(MinecraftClient client, BlockPos destination, boolean routeFailed, long now) {
        var target = block.target();
        var protectedStation = StructureSupply.union(new ProjectGather.Area(target, target), block);
        if (needsRouteSticks) {
            if (client.player.getInventory().count(Items.STICK) < 2 && !SubsurfaceAccess.canDigRoute(client)) {
                status = assignment.project() + "/" + assignment.job().work().id() + ": Waiting for a stick delivery";
                return true;
            }
            if (!SubsurfaceAccess.canDigRoute(client) && !acknowledgedRouteSticks) {
                if (!reportingRouteSticks) {
                    reportingRouteSticks = true;
                    nextRequest = 0;
                }
                status = assignment.project() + "/" + assignment.job().work().id() + ": Confirming receipt of sticks";
                return true;
            }
            needsRouteSticks = false;
            reportingRouteSticks = acknowledgedRouteSticks = false;
            if (!SubsurfaceAccess.canDigRoute(client) && startMiningCraft(client, now, ROUTE_PICKAXE, protectedStation)) return true;
        }
        if (stationDescent.continuePassage(client, approachAge, protectedStation)
                || stationDescent.continueLocalApproach(client, approachAge)) return true;
        var current = client.player.getBlockPos();
        int drop = current.getY() - destination.getY();
        if (routeFailed && drop >= 2 && drop <= 16 && current.isWithinDistance(destination, 32)
                && !SubsurfaceAccess.canDigRoute(client) && prepareLowerRouteTool(client, current, protectedStation, now)) {
            return true;
        }
        if (stationDescentSteps >= 12 || !current.isWithinDistance(destination, 12)
                || drop < 0 || drop > 8 || stationDescentSteps == 0 && (!routeFailed || drop < 2)) return false;
        if (approach != null && !routeFailed) return false;
        if (approach != null) halt(client);
        if (!stationDescent.openLowerStationStep(client, approachAge, destination, protectedStation)) return false;
        stationDescentSteps++;
        status = assignment.project() + "/" + assignment.job().work().id() + ": Opening a protected route to the lower station";
        BotEvents.add("debug", "movement", "Opening a protected route to the lower station at " + destination.toShortString());
        return true;
    }

    private static boolean prepareLowerRouteTool(MinecraftClient client, BlockPos current,
                                                 ProjectGather.Area protectedStation, long now) {
        if (!SubsurfaceAccess.hasFooting(client) || current.equals(stationToolAttempt)) return false;
        stationToolAttempt = current;
        halt(client);
        if (startMiningCraft(client, now, ROUTE_PICKAXE, protectedStation)) return true;
        if (client.player.getInventory().count(Items.STICK) >= 2
                || !ProjectProduction.canCraft(client, ROUTE_PICKAXE, new net.minecraft.item.ItemStack(Items.STICK, 4))
                || FieldWorkstation.findForMiningTool(client, now, protectedStation) == null) return false;
        needsRouteSticks = true;
        nextRequest = 0;
        taskEnd = Math.max(taskEnd, Math.min(stationReturnDeadline, now + seconds(150)));
        status = assignment.project() + "/" + assignment.job().work().id() + ": Requesting sticks for a route pickaxe";
        BotEvents.add("info", "projects", "Requesting sticks for a route pickaxe");
        return true;
    }

    private static boolean tryElevatedApproach(MinecraftClient client, BlockPos destination, long now,
                                               boolean placement, boolean shortStationClimb) {
        var position = client.player.getBlockPos();
        int heightRemaining = destination.getY() - position.getY();
        boolean finishingStairs = !placement && heightRemaining > 0 && stationReturn.continuingLocalAscent();
        if (!finishingStairs && !shortStationClimb && heightRemaining < (placement ? 3 : 5)) {
            needsRouteTool = needsRouteBench = false;
            stationReturn.stop();
            return false;
        }
        if (!placement && position.getY() >= stationReturnHeight + 4) {
            stationReturnHeight = position.getY();
            taskEnd = Math.max(taskEnd, Math.min(stationReturnDeadline, now + seconds(90)));
        }
        var target = block.target();
        var excluded = StructureSupply.union(new ProjectGather.Area(target, target), block);
        if (!placement && stationReturn.openingReturnStep() && stationReturn.continuePassage(client, approachAge, excluded)) {
            status = assignment.project() + "/" + assignment.job().work().id() + ": Opening a protected return step";
            return true;
        }
        if (!stationReturn.active() && !stationReturn.continuingLocalAscent()) {
            if (!placement && approach == null && stationReturn.tryNaturalAscent(client, approachAge, destination.getY())) {
                status = assignment.project() + "/" + assignment.job().work().id() + ": Following a natural ascent to the station";
                return true;
            }
            if (approach == null || now < retryApproach || BaritoneClient.pursuing(destination)) return false;
            BaritoneClient.execute("stop");
            approachRecovery.reset();
            approach.close();
            approach = null;
            recoveryOrigin = null;
        }
        // Placement cargo can include the very planks needed for a new workbench.
        boolean hasRouteTool = SubsurfaceAccess.canDigRoute(client);
        if (hasRouteTool) needsRouteTool = needsRouteBench = false;
        boolean canSwitchTools = !stationReturn.followingNaturalAscent() && !stationReturn.walkingLocalAscent(client);
        if (!placement && (!hasRouteTool || canSwitchTools && !SubsurfaceAccess.hasBasicRoutePickaxe(client))
                && (stationToolAttempt == null || !position.isWithinDistance(stationToolAttempt, 4))
                && SubsurfaceAccess.hasFooting(client)) {
            stationToolAttempt = position;
            if (startMiningCraft(client, now, ROUTE_PICKAXE, excluded)) {
                stationReturn.pauseReturnForToolPreparation();
                return true;
            }
            needsRouteTool = !hasRouteTool && chest != null && chest.depositing();
            if (needsRouteTool && !allowedResupplyWait) {
                allowedResupplyWait = true;
                taskEnd = Math.max(taskEnd, Math.min(stationReturnDeadline, now + seconds(390)));
            }
            needsRouteBench = needsRouteTool && ProjectProduction.canCraft(client, ROUTE_PICKAXE)
                    && !client.player.getInventory().contains(net.minecraft.item.Items.CRAFTING_TABLE.getDefaultStack());
        }
        boolean alreadyClimbing = stationReturn.active();
        boolean carryingDeposit = !placement && chest != null && chest.depositing()
                && projectCargoItem != null && projectCargoItem.count(client) > 0;
        if (!stationReturn.advanceToward(client, approachAge, destination, excluded, !placement, carryingDeposit)) return false;
        String detail = placement ? "Opening a short climb to the placement stand"
                : stationReturn.followingNaturalAscent() ? "Following a natural ascent to the station"
                : stationReturn.openingReturnStep() ? "Opening a protected return step" : "Opening a return route to the station";
        if (!alreadyClimbing && (placement || shortStationClimb)) {
            BotEvents.add("info", "movement", shortStationClimb ? "Recovering a short climb to the station" : detail);
        }
        status = assignment.project() + "/" + assignment.job().work().id() + ": " + detail;
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
        return nextInteractionStands(client, target, previous, kind, Integer.MAX_VALUE);
    }

    private static List<BlockPos> nextInteractionStands(MinecraftClient client, BlockPos target, BlockPos previous,
                                                       ProjectQueue.Kind kind, int highestStand) {
        var candidates = new ArrayList<BlockPos>();
        for (var pos : BlockPos.iterateOutwards(target, 4, 4, 4)) {
            if (pos.getY() > highestStand || pos.equals(previous) || rejectedInteractionStands.contains(pos)
                    || !client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || !usableInteractionStand(client, pos, target)) continue;
            var feet = Vec3d.ofBottomCenter(pos);
            var eye = feet.add(0, 1.62, 0);
            var hit = switch (kind) {
                case EXCAVATE -> excavationHit(client, target, eye);
                case BUILD -> ProjectPlacement.supportHit(client, block, eye);
                default -> ProjectProduction.workstationHit(client, target, eye);
            };
            if (hit != null || kind == ProjectQueue.Kind.BUILD && (canJumpToPlace(client, feet, pos)
                    || !attemptedJumpHeadroom && canClearJumpHeadroom(client, pos))) {
                candidates.add(pos.toImmutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> client.player.squaredDistanceTo(Vec3d.ofBottomCenter(pos))));
        return candidates.stream().limit(24).toList();
    }

    private static boolean canJumpToPlace(MinecraftClient client, Vec3d feet, BlockPos stand) {
        if (triedJumpStands.contains(stand) || block.edgePlacement() || ProjectWater.source(block.desired())
                || !client.world.getFluidState(stand).isEmpty()
                || feet.squaredDistanceTo(Vec3d.ofCenter(block.target())) > 25) return false;
        var player = client.player;
        double halfWidth = player.getWidth() / 2;
        var body = new Box(feet.x - halfWidth, feet.y, feet.z - halfWidth,
                feet.x + halfWidth, feet.y + 1.8, feet.z + halfWidth);
        var target = new Box(block.target());
        for (double lift : JUMP_HEIGHTS) {
            var sweep = body.stretch(0, lift, 0);
            if (sweep.intersects(target) || client.world.getBlockCollisions(player, sweep).iterator().hasNext()) break;
            if (ProjectPlacement.supportHit(client, block, feet.add(0, 1.62 + lift, 0)) != null) return true;
        }
        return false;
    }

    private static boolean openJumpHeadroom(MinecraftClient client, BlockPos feet) {
        var player = client.player;
        var obstacle = feet.up(2);
        if (!player.isOnGround() || openingJumpHeadroom != null && !obstacle.equals(openingJumpHeadroom)
                || !canClearJumpHeadroom(client, feet)) {
            if (openingJumpHeadroom != null) halt(client);
            return false;
        }
        var ray = excavationHit(client, obstacle);
        if (ray == null) {
            if (openingJumpHeadroom != null) halt(client);
            return false;
        }
        if (openingJumpHeadroom == null) {
            halt(client);
            attemptedJumpHeadroom = true;
            openingJumpHeadroom = obstacle;
            status = assignment.project() + "/" + assignment.job().work().id() + ": Clearing one block of jump headroom";
            BotEvents.add("info", "movement", "Clearing placement headroom at " + obstacle.toShortString());
        }
        player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, ray.getPos());
        breaking = true;
        if (client.interactionManager.updateBlockBreakingProgress(ray.getBlockPos(), ray.getSide())) {
            player.swingHand(Hand.MAIN_HAND);
        }
        return true;
    }

    private static boolean canClearJumpHeadroom(MinecraftClient client, BlockPos feet) {
        var world = client.world;
        var obstacle = feet.up(2);
        if (block.edgePlacement() || !feet.isWithinDistance(block.target(), 5)
                || !world.isInBuildLimit(obstacle.up()) || !world.getWorldBorder().contains(obstacle)
                || !usableInteractionStand(client, feet, block.target())
                || ProjectWater.source(block.desired()) || obstacle.equals(block.target())) return false;
        var state = world.getBlockState(obstacle);
        if (!naturalLeaves(state) && !BaritoneSettings.recoverableTerrain(state.getBlock())
                || state.hasBlockEntity() || !state.getFluidState().isEmpty() || state.getHardness(world, obstacle) < 0
                || BuildProtection.blocked(obstacle)
                || world.getBlockState(obstacle.up()).getBlock() instanceof FallingBlock
                || !world.getOtherEntities(client.player, new Box(obstacle).stretch(0, 1, 0),
                    entity -> entity instanceof net.minecraft.entity.LivingEntity && entity.isAlive()).isEmpty()) return false;
        for (var side : Direction.values()) {
            var neighbor = obstacle.offset(side);
            if (!world.isChunkLoaded(neighbor.getX() >> 4, neighbor.getZ() >> 4)
                    || !world.getFluidState(neighbor).isEmpty()) return false;
        }
        var sight = Vec3d.ofBottomCenter(feet).add(0, 2.62, 0);
        return StructureProject.clearBuildFace(client, sight, block, Map.of(obstacle, Blocks.AIR.getDefaultState()));
    }

    private static boolean canCenterForInteraction(MinecraftClient client, BlockPos target, BlockPos stand,
                                                   ProjectQueue.Kind kind) {
        var feet = Vec3d.ofBottomCenter(stand);
        var position = client.player.getPos();
        double dx = position.x - feet.x;
        double dz = position.z - feet.z;
        if (dx * dx + dz * dz <= STAND_CENTER_TOLERANCE * STAND_CENTER_TOLERANCE) {
            return centering;
        }
        if (!usableInteractionStand(client, stand, target)) return false;
        var eye = feet.add(0, 1.62, 0);
        return kind == ProjectQueue.Kind.EXCAVATE ? excavationHit(client, target, eye) != null
                : ProjectPlacement.supportHit(client, block, eye) != null || canJumpToPlace(client, feet, stand);
    }

    private static boolean tickPlacementJump(MinecraftClient client, long now) {
        if (now >= jumpEnds) {
            jumpEnds = 0;
            return false;
        }
        var player = client.player;
        if (now >= nextPlacement && !player.isOnGround() && client.world.isAir(block.target())
                && !player.getBoundingBox().intersects(new Box(block.target()))
                && ProjectPlacement.supportHit(client, block) != null) {
            nextPlacement = now + TimeUnit.MILLISECONDS.toNanos(75);
            var failure = BuildProtection.projectAction(assignment.project(), () -> ProjectPlacement.attempt(client, block));
            if (failure != null) finish(client, false, failure);
        }
        return true;
    }

    private static void releaseJump(MinecraftClient client) {
        if (!jumpPressed) return;
        client.options.jumpKey.setPressed(false);
        jumpPressed = false;
    }

    static boolean usableInteractionStand(MinecraftClient client, BlockPos pos, BlockPos target) {
        return !pos.equals(target) && !pos.up().equals(target) && !pos.down().equals(target)
                && client.world.isInBuildLimit(pos.up()) && client.world.getWorldBorder().contains(pos)
                && ProjectPlacement.flushFloor(client.world.getBlockState(pos.down()), client.world, pos.down())
                && client.world.isAir(pos) && client.world.isAir(pos.up());
    }

    static BlockHitResult excavationHit(MinecraftClient client, BlockPos target) {
        return excavationHit(client, target, client.player.getEyePos());
    }

    static BlockHitResult excavationHit(MinecraftClient client, BlockPos target,
                                        java.util.function.Predicate<BlockPos> accepted) {
        return excavationHit(client, target, client.player.getEyePos(), accepted);
    }

    private static BlockHitResult excavationHit(MinecraftClient client, BlockPos target, Vec3d eye) {
        return excavationHit(client, target, eye, target::equals);
    }

    private static BlockHitResult excavationHit(MinecraftClient client, BlockPos target, Vec3d eye,
                                                java.util.function.Predicate<BlockPos> accepted) {
        double reach = client.interactionManager.getReachDistance() - .1;
        if (eye.squaredDistanceTo(Vec3d.ofCenter(target)) > (reach + 1) * (reach + 1)) return null;
        var shape = client.world.getBlockState(target).getOutlineShape(client.world, target);
        return ProjectPlacement.findExcavationHit(target, eye, shape,
                point -> excavationRay(client, eye, point, reach, accepted));
    }

    private static BlockHitResult excavationRay(MinecraftClient client, Vec3d eye, Vec3d point, double reach,
                                                java.util.function.Predicate<BlockPos> accepted) {
        if (eye.squaredDistanceTo(point) > reach * reach) return null;
        var hit = client.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == HitResult.Type.BLOCK && accepted.test(hit.getBlockPos()) ? hit : null;
    }

    private static boolean centerOnStand(MinecraftClient client, Vec3d stand) {
        return centerOnStand(client, stand, false);
    }

    private static boolean centerOnStand(MinecraftClient client, Vec3d stand, boolean staySneaking) {
        var player = client.player;
        double dx = stand.x - player.getX();
        double dz = stand.z - player.getZ();
        // Match the eye position used to check reach in the planner.
        double tolerance = staySneaking ? .04 : STAND_CENTER_TOLERANCE;
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

    private static void finishProduction(MinecraftClient client, ProjectProduction.Result result, long now) {
        if (result.loadedFurnace() && block != null) {
            loadedFurnace = new ProjectQueue.FurnaceSite(routePoint(block.target()), routePoint(block.stand()));
        }
        if (result.success() && productionWorkbench != null) {
            halt(client);
            pendingProductionResult = result;
            workbenchPickup = new WorkbenchPickup(productionWorkbench.target(), now, client);
            productionWorkbench = null;
        } else finish(client, result.success(), result.detail());
    }

    private static void finish(MinecraftClient client, boolean result, String observation) {
        halt(client);
        saveTrail();
        success = result;
        evidence = observation;
        nextRequest = 0;
        status = observation;
    }

    private static Map<String, Integer> carriedToolUses(MinecraftClient client) {
        var tools = new HashMap<String, Integer>();
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isDamageable()) {
                tools.merge(Registries.ITEM.getId(stack.getItem()).toString(),
                        stack.getMaxDamage() - stack.getDamage(), Integer::sum);
            }
        }
        return tools;
    }

    private static void request(MinecraftClient client, LocalFormation.Peer here, String action, long now) {
        int epoch = generation;
        var args = new HashMap<String, Object>();
        args.put("action", action);
        if (action.equals("safety-wait")) {
            args.put("worker", here.name());
            if (enrollment.project() != null) args.put("project", enrollment.project());
        } else if (action.equals("claim-return-cargo")) {
            args.put("worker", here.name());
            if (enrollment.project() != null) args.put("project", enrollment.project());
            args.put("returnRouteRevision", SubsurfaceAccess.RETURN_ROUTE_REVISION);
        } else if (assignment == null) {
            args.put("worker", here.name());
            if (enrollment.project() != null) args.put("project", enrollment.project());
            args.put("kinds", KINDS);
            args.put("claimItems", ProjectInventory.transferableItems(client));
            if (client.player.isOnGround() && idleRecovery.readyToClearPlacement(now)) {
                var box = client.player.getBoundingBox();
                var min = BlockPos.ofFloored(box.minX, box.minY, box.minZ);
                var max = BlockPos.ofFloored(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ));
                var occupied = new ArrayList<ProjectQueue.ResourceSite>();
                for (var cell : BlockPos.iterate(min, max)) occupied.add(routePoint(cell));
                args.put("occupiedCells", occupied);
            }
            args.put("returnRouteRevision", SubsurfaceAccess.RETURN_ROUTE_REVISION);
            args.put("supportsStickResupply", true);
            if (ProjectProduction.canCraft(client, ROUTE_STICKS)) {
                var planks = new HashSet<String>();
                var inventory = client.player.getInventory();
                for (int slot = 0; slot < 36; slot++) {
                    var stack = inventory.getStack(slot);
                    if (stack.isIn(ItemTags.PLANKS)) planks.add(Registries.ITEM.getId(stack.getItem()).toString());
                }
                if (!planks.isEmpty()) args.put("stickCraftInputs", planks);
            }
            args.put("supportsMealTrail", true);
            args.put("checkpointApproach", true);
            var meal = TaskDefense.spareMeal(client);
            if (meal != null) args.put("spareMeal", meal);
            else {
                var source = TaskDefense.accessibleFoodSource(client);
                if (source != null) args.put("forageSource", source);
            }
            args.put("supportsPickupReserve", true);
            var tools = carriedToolUses(client);
            if (!tools.isEmpty()) args.put("toolUses", tools);
            if (tools.keySet().stream().anyMatch(item -> item.endsWith("_pickaxe"))) {
                if (SubsurfaceAccess.hasFooting(client)) args.put("groundedGatherRetry", true);
                var ore = MiningTask.reachableOre(client);
                if (!ore.isEmpty()) args.put("reachableOre", ore);
            }
            if (!SubsurfaceAccess.canDigRoute(client) && now >= nextStarterSurvey) {
                nextStarterSurvey = now + seconds(6);
                var logs = MiningTask.localStarterLogs(client);
                if (!logs.isEmpty()) args.put("localStarterLogs", logs);
            }
            if (PortableWoodPreparation.canSupply(client)) args.put("canSupplyBench", true);
            if (ProjectProduction.canCraft(client, ROUTE_PICKAXE)
                    && FieldWorkstation.findForMiningTool(client, now, null) != null) {
                args.put("routeCraftTarget", freshToolCount(client, net.minecraft.item.Items.STONE_PICKAXE) + 1);
            }
        } else {
            args.put("project", assignment.project());
            args.put("job", assignment.job().work().id());
            args.put("token", assignment.job().lease());
            if (action.equals("renew")) {
                if (readyToBank) args.put("readyToBank", true);
                if (assignment.job().work().kind() == ProjectQueue.Kind.GATHER) {
                    var tools = carriedToolUses(client);
                    if (!tools.isEmpty()) args.put("toolUses", tools);
                }
                if (TaskDefense.requestsMeal(client)) {
                    args.put("needsMeal", true);
                    args.put("mealTrail", returnTrail.supplyTrail(routePoint(client.player.getBlockPos())));
                }
                if (TaskDefense.recentlyAteMeal(client) || TaskDefense.hasMeal(client)) args.put("carryingMeal", true);
                if (needsRouteSticks) args.put("needsRouteSticks", true);
                if (reportingRouteSticks) args.put("receivedRouteSticks", true);
            }
            if (receivedRouteBench) args.put("receivedRouteBench", true);
            if (needsRouteTool && !SubsurfaceAccess.canDigRoute(client)) {
                args.put("needsRouteTool", true);
            }
            if (needsProductionBench || needsRouteTool && needsRouteBench && !SubsurfaceAccess.canDigRoute(client)) {
                args.put("needsRouteBench", true);
            }
            if (action.equals("finish") && Boolean.FALSE.equals(success) && ProjectQueue.GATHER_STALLED.equals(evidence)
                    && gather != null && ResourceCatalog.isOre(gather.resource())) {
                args.put("resourceSite", routePoint(client.player.getBlockPos()));
            } else if (gatherSite != null && !gather.fromDrops()) args.put("resourceSite", gatherSite);
            boolean resupply = assignment.job().work().kind() == ProjectQueue.Kind.RESUPPLY;
            if (chestReport != null && resupply) args.put("routeStock", chestReport);
            if (success != null && action.equals("finish")) {
                args.put("success", success);
                args.put("evidence", evidence);
                if (loadedFurnace != null) args.put("loadedFurnace", loadedFurnace);
                if (chestReport != null && !resupply) args.put("chest", chestReport);
                if (pickupShortfall) args.put("pickupShortfall", true);
            }
        }
        pending = true;
        if (!action.equals("safety-wait")) nextRequest = now + seconds(action.equals("claim-return-cargo") ? 4 : 1);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/projects"))
                .timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(args))).build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((reply, error) -> client.execute(() -> {
            if (generation != epoch) return;
            pending = false;
            if (action.equals("safety-wait") || error != null || reply.statusCode() >= 500) return;
            if (reply.statusCode() == 202 && action.equals("finish")) {
                try {
                    var renewed = JSON.fromJson(reply.body(), ProjectQueue.Job.class);
                    if (renewed != null) deadline = deadline(renewed, now);
                    if (System.nanoTime() >= taskEnd) {
                        finish(client, false, "Recipient did not confirm pickup of the resupply item");
                    } else status = "Waiting for the recipient to pick up supplies";
                } catch (com.google.gson.JsonParseException ignored) {
                    // Older coordinators send a plain-text receipt response.
                }
                return;
            }
            boolean continuation = action.equals("finish") && reply.statusCode() == 200 && reply.body().startsWith("{");
            if (reply.statusCode() == 409 || action.equals("finish") && reply.statusCode() == 200 && !continuation) {
                reset(client);
                status = "Waiting for project jobs";
                nextRequest = System.nanoTime() + seconds(1);
                return;
            }
            if (reply.statusCode() != 200) return;
            try {
                if (action.equals("claim") || action.equals("claim-return-cargo") || continuation) {
                    if (continuation) reset(client);
                    assignment = JSON.fromJson(reply.body(), ProjectQueue.Assignment.class);
                    if (assignment == null) {
                        var clearance = reply.headers().firstValue(ProjectBroker.CLEAR_PLACEMENT_HEADER).orElse(null);
                        if (action.equals("claim") && clearance != null && idleRecovery.clearPlacement(
                                client, BlockPos.fromLong(Long.parseLong(clearance)), System.nanoTime())) {
                            status = "Clearing a teammate’s placement";
                        }
                        return;
                    }
                    if (enrollment.project() != null && !enrollment.project().equals(assignment.project())) {
                        assignment = null;
                        status = "Coordinator did not honor the selected project";
                        return;
                    }
                    if (action.equals("claim-return-cargo")) {
                        stopIdleAscent(client);
                        waitingForSafety = false;
                        BotEvents.add("info", "projects", "Returning carried cargo to the project chest");
                    }
                    deadline = deadline(assignment.job(), now);
                    travelingContribution = ProjectQueue.travelingContribution(assignment.job());

                    var work = assignment.job().work();
                    long started = System.nanoTime();
                    stationReturnDeadline = started + seconds(900);
                    stationReturnHeight = client.player.getBlockY();
                    taskEnd = started + seconds(switch (work.kind()) {
                        case GATHER -> 1800;
                        case SMELT -> 300;
                        case CRAFT, TRANSFER, FILL_BUCKETS -> 180;
                        case RESUPPLY -> 90;
                        default -> 90;
                    });
                    if (work.kind() == ProjectQueue.Kind.RESUPPLY) {
                        var delivery = JSON.fromJson(work.payload(), ProjectQueue.RouteToolDelivery.class);
                        var item = Registries.ITEM.get(new Identifier(delivery.item()));
                        if (delivery.forage() != null) {
                            craftingRouteDelivery = delivery;
                            fieldForage = new FieldForage(delivery.forage());
                            BotEvents.add("info", "projects", "Foraging for " + delivery.recipient());
                        } else if (delivery.benchStock() != null) {
                            craftingRouteDelivery = delivery;
                            var spec = ProjectChest.parse(delivery.benchStock().payload());
                            block = spec.location();
                            chest = new ProjectChest(spec, delivery.benchStock().reserve());
                        } else if (item == net.minecraft.item.Items.CRAFTING_TABLE
                                && !client.player.getInventory().contains(item.getDefaultStack())) {
                            craftingRouteDelivery = delivery;
                            routeWorkbench = new PortableWoodPreparation();
                        } else if (item == Items.STICK) {
                            if (client.player.getInventory().count(Items.STICK) >= delivery.craftTarget()) {
                                startRouteDelivery(client, delivery);
                            } else {
                                craftingRouteDelivery = delivery;
                                routeWorkbench = new PortableWoodPreparation(Items.STICK, delivery.craftTarget(), null, null, false);
                                BotEvents.add("info", "projects", "Crafting sticks for " + delivery.recipient());
                            }
                        } else if (freshToolCount(client, item) < delivery.craftTarget()) {
                            if (startMiningCraft(client, started, ROUTE_PICKAXE, null)) craftingRouteDelivery = delivery;
                            else finish(client, false, ProjectQueue.ROUTE_PREPARATION_FAILED + "ingredients or workbench became unavailable");
                        } else startRouteDelivery(client, delivery);
                    } else if (work.kind() == ProjectQueue.Kind.GATHER) {
                        gather = ProjectGather.parse(work.payload());
                        gatherAtSurface = !gather.fromDrops() && MiningTask.isSurfaceGathering(client,
                                ResourceCatalog.blocks(gather.resource(), gather.variant()));
                        projectCargoItem = gather.item();
                        gatherSite = assignment.job().resourceSite();
                        if (assignment.flexibleStickCount() > 0) {
                            stickPreparation = new PortableWoodPreparation(Items.STICK,
                                    assignment.flexibleStickCount(), gather.exclude(), gatherSite);
                        }
                        if (gather.fromDrops()) pickup = new DroppedItemSearch();
                    } else if (work.kind() == ProjectQueue.Kind.TRANSFER) {
                        var spec = ProjectChest.parse(work.payload());
                        block = spec.location();
                        chest = new ProjectChest(spec);
                        if (chest.depositing()) projectCargoItem = spec.item();
                    } else if (work.kind() == ProjectQueue.Kind.CRAFT || work.kind() == ProjectQueue.Kind.SMELT) {
                        resumesLoadedFurnace = work.kind() == ProjectQueue.Kind.SMELT
                                && ProjectProduction.resumesLoadedFurnace(work.payload());
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
                    travelingContribution = ProjectQueue.travelingContribution(renewed);
                    if (args.containsKey("receivedRouteSticks")) acknowledgedRouteSticks = true;
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
