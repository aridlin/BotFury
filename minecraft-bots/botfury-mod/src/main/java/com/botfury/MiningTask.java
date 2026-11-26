package com.botfury;

import com.google.gson.Gson;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FlowerBlock;
import net.minecraft.block.TallFlowerBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.screen.slot.SlotActionType;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

final class MiningTask {
    record Job(String resource,String variant,int broker) {}
    private static final int STANDS_PER_TICK = 8;
    private static final int MAX_ALTERNATE_STANDS = 3;
    private static final int DISTANT_ORE_STAND = 32;
    private static final int MAX_PROTECTED_APPROACH_STEPS = 16;
    private static final int MAX_EXPLORATION_EXITS = 3;
    private static final int MAX_CANOPY_LEAVES = 6;
    private static final int MAX_ORE_COVER = 2;
    private static final int EXPOSED_ORE_PICKUP_STEPS = 4;
    private static final int COVERED_ORE_WALK_RADIUS = 8;
    private static final int REACHABLE_SCAN_RADIUS = 5;
    private static final int REACHABLE_SCAN_HEIGHT = 4;
    private static final int SURFACE_ROUTE_DEPTH = 12;
    private static final int ELEVATED_STAND_MIN_RISE = 4;
    private static final int ELEVATED_STAND_RANGE = 16;
    private static final int SURFACE_EXIT_STALL_TICKS = 280;
    private static final int SURFACE_EXIT_BACKOFF_TICKS = 1200;
    private static final int RECORDED_SITE_RETRY_TICKS = 100;
    private static final int RECORDED_SITE_NO_PATH_TICKS = 160;
    private static final int RECORDED_SITE_STALL_TICKS = 280;
    private static final Gson JSON=new Gson();
    private static final ExecutorService IO=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"BotFury-mining");t.setDaemon(true);return t;});
    private static final HttpClient HTTP=HttpClient.newBuilder().executor(IO).connectTimeout(Duration.ofMillis(300)).build();
    private static Job job;
    private static ProjectGather.Area excluded;
    private static Set<Block> blocks = Set.of();
    private static String owner,world,leaseToken;
    private static int claimSerial;
    private static int generation,age,nextSearch,nextPath,nextRenew,exploration;
    private static long leaseUntil;
    private static boolean requesting,dead,paused,pathActive,projectMining;
    private static boolean localOnly;
    private static boolean reachableOnly;
    private static final Deque<BlockPos> pendingCandidates = new ArrayDeque<>();
    private static boolean scanNeedsTool;
    private static boolean toolShortfall;
    private static boolean routeToolShortfall;
    static void pause(boolean value) {
        if (job == null || paused == value) return;
        paused = value;
        pendingCandidates.clear();
        if (value) {
            finishWideSearch(MinecraftClient.getInstance());
            stopPath();
            clearReservation();
            minedDrops.reset();
            pickup = null;
            status = "Mining paused";
        } else nextSearch = 0;
    }
    private static BlockPos target;
    private static Vec3d stand,origin,pickup;
    private static int alternateStandAttempts;
    private static int pickupUntil;
    private static int exposedOrePickupSteps;
    private static boolean protectedOrePickup;
    private static ProjectItem projectDrop;
    private static ItemEntity reservedDrop;
    private static int nextDropScan, dropDeadline;
    private static final int LOOSE_DROP_RADIUS = 12;
    private static int nextPickupRoomCheck;
    private static final int MAX_FAILED_DROP_ROUTES = 64;
    private static final long DROP_ROUTE_BACKOFF = java.time.Duration.ofMinutes(2).toNanos();
    private static final Map<UUID, FailedDropApproach> failedDropRoutes = new LinkedHashMap<>();

    private record FailedDropApproach(String world, BlockPos cell, BlockPos feet, long retryAt) {
        boolean stillApplies(String currentWorld, BlockPos itemCell, BlockPos playerCell, long now) {
            return retryAt > now && world.equals(currentWorld) && cell.equals(itemCell)
                    && Math.abs(feet.getY() - playerCell.getY()) <= 1 && feet.isWithinDistance(playerCell, 6);
        }
    }

    private static final DroppedItemSearch minedDrops = new DroppedItemSearch();
    private static BlockPos rememberedSite;
    private static final Set<BlockPos> rememberedRouteRegions = new HashSet<>();
    private static int rememberedProgressAt, nextRememberedPath;
    private static BlockPos deepExplorationGoal;
    private static BlockPos surfaceSearchAnchor, lastExplorationExit;
    private static BlockPos surfaceLedgeOrigin;
    private static int surfaceLedgeEnds;
    private static final int SURFACE_LEDGE_TICKS = 180;
    private static BlockPos surfaceRouteGoal, surfaceExplorationGoal, surfaceTrailWaypoint;
    private static final Map<BlockPos, Integer> rejectedSurfaceExits = new HashMap<>();
    private static int surfaceProgressAt, surfaceProgressY;
    private static double surfaceProgressDistance;
    private static final Deque<ProjectQueue.ResourceSite> surfaceTrail = new ArrayDeque<>();
    private static boolean checkedSurfaceTrail;
    private static final int NEARBY_SITE_RADIUS = 48;
    private static final int WIDE_CHUNKS_PER_TICK = 32;
    private static int nextLocalWideScan;
    private static Iterator<ChunkPos> wideChunks = Collections.emptyIterator();
    private static boolean surfaceResource;
    private static boolean checkedWiderBeforeRememberedSite;
    private static int nextSurfaceSurvey;
    private static int surfaceSearchFailures, explorationExits;
    private static BaritoneSettings caveSettings;
    private static int lastCaveProbeY, nextCaveProbe;
    private static int lastProspectingCheckpointY;
    private static int rememberedUntil;
    private static ProjectQueue.ResourceSite observedSite;

    static ProjectQueue.ResourceSite observedSite() { return observedSite; }
    private static BaritoneSettings settings;
    private static final StallRecovery routeRecovery = new StallRecovery();
    private static final SubsurfaceAccess subsurface = new SubsurfaceAccess();
    private static final OreRouteBackoff oreRoutes = new OreRouteBackoff();
    private static final Map<BlockPos,Integer> rejected=new HashMap<>();
    private static final Map<BlockPos,Integer> rejectedStands = new HashMap<>();
    private static int approachProgress;
    private static double approachDistance;
    private static boolean localApproachTried;
    private static BlockPos localApproachFrom;
    private static int localApproachSteps;
    private static final Set<BlockPos> openedCover = new HashSet<>();
    private static int coverAttemptEnds;
    private static volatile String status="";
    static boolean active(){return job!=null;}
    static boolean working(){return job!=null&&!paused&&!dead;}
    static boolean seekingDeepOre(MinecraftClient client) {
        return working() && projectMining && target == null && pickup == null
                && client.player.getBlockY() <= 8 && SubsurfaceAccess.deepResource(job.resource);
    }
    static boolean retracingSurfaceTrail() { return surfaceTrailWaypoint != null; }
    static boolean climbingToSurface() { return working() && surfaceRouteGoal != null; }
    static String status(){return job==null?"":status;}
    static String lastStatus(){return status;}
    static boolean toolShortfall() { return toolShortfall; }
    static boolean routeToolShortfall() { return routeToolShortfall; }
    static boolean blocksProspectingBreak(BlockPos pos) {
        return subsurface.blocksBreaking(MinecraftClient.getInstance(), pos);
    }
    static Job parse(String value){
        String[] parts=value.split("\\|",-1);
        if(parts.length!=3)throw new IllegalArgumentException("Expected resource, variant, coordinator port");
        ResourceCatalog.blocks(parts[0],parts[1]);
        int port;try{port=Integer.parseInt(parts[2]);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid coordinator port");}
        if(port<1024||port>65535)throw new IllegalArgumentException("Invalid coordinator port");
        return new Job(parts[0],parts[1],port);
    }
    static void start(MinecraftClient client,Job next){
        LocalFormation.pauseMovement();BotInventory.hold();
        startMining(client, next, false, null);
    }

    static void startProject(MinecraftClient client, Job next, ProjectGather.Area exclude, ProjectQueue.ResourceSite site,
                             ProjectItem drop, int cargoCheckpoint) {
        stop();
        startMining(client, next, true, exclude);
        if (job == null) return;
        projectDrop = drop;
        if (ResourceCatalog.isOre(next.resource)) subsurface.setMiningCheckpoint(cargoCheckpoint);
        if (site == null) return;
        var location = new BlockPos(site.x(), site.y(), site.z());
        Integer prospectingY = prospectingAltitude(client);
        int workerY = client.player.getBlockY();
        if (prospectingY != null && workerY <= location.getY() - 24
                && Math.abs(workerY - prospectingY) < Math.abs(location.getY() - prospectingY)) return;
        if (client.world.isInBuildLimit(location) && client.world.getWorldBorder().contains(location)
                && (surfaceResource || exclude == null || !exclude.contains(location))) {
            rememberedSite = location;
            lastProspectingCheckpointY = Math.min(lastProspectingCheckpointY, location.getY());
            double distance = client.player.getPos().distanceTo(Vec3d.ofBottomCenter(location));
            rememberedUntil = Math.min(2400, Math.max(600, (int) (distance * 12)));
        }
    }

    static void startLocalProject(MinecraftClient client, Job next, ProjectGather.Area exclude, ProjectItem drop) {
        startProject(client, next, exclude, null, drop, 0);
        localOnly = job != null;
    }

    static void startReachableProject(MinecraftClient client, Job next, ProjectGather.Area exclude, ProjectItem drop) {
        startLocalProject(client, next, exclude, drop);
        reachableOnly = job != null;
    }

    static boolean hasReachableLocalBlock(MinecraftClient client, String resource) {
        if (!SubsurfaceAccess.hasFooting(client) || client.interactionManager == null || client.player.isTouchingWater()) return false;
        var feet = client.player.getBlockPos();
        var world = client.world;
        var wanted = ResourceCatalog.blocks(resource, "both").stream()
                .map(id -> Registries.BLOCK.get(new Identifier(id))).collect(java.util.stream.Collectors.toSet());
        for (var pos : BlockPos.iterateOutwards(feet, REACHABLE_SCAN_RADIUS, REACHABLE_SCAN_HEIGHT, REACHABLE_SCAN_RADIUS)) {
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            if (wanted.contains(world.getBlockState(pos).getBlock()) && !BuildProtection.blocked(pos)
                    && canReachFromHere(client, feet, pos) && !supportsAnotherPlayer(client, pos)) return true;
        }
        return false;
    }

    static boolean hasExposedLocalBlock(MinecraftClient client, String resource) {
        var wanted = Set.copyOf(ResourceCatalog.blocks(resource, "both"));
        var world = client.world;
        for (var pos : BlockPos.iterateOutwards(client.player.getBlockPos(), 8, 4, 8)) {
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) || BuildProtection.blocked(pos)) continue;
            if (wanted.contains(Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString())
                    && !completelyCovered(world, pos)) return true;
        }
        return false;
    }

    static List<String> localStarterLogs(MinecraftClient client) {
        if (!SubsurfaceAccess.hasFooting(client)
                || !client.world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) return List.of();
        var feet = client.player.getBlockPos();
        var world = client.world;
        int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING, feet.getX(), feet.getZ());
        if (surface - feet.getY() > 12) return List.of();
        var logs = new LinkedHashSet<String>();
        for (var pos : BlockPos.iterateOutwards(feet, 12, 5, 12)) {
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) || BuildProtection.blocked(pos)) continue;
            var state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS) || completelyCovered(world, pos)) continue;
            logs.add(Registries.BLOCK.getId(state.getBlock()).toString());
            if (logs.size() >= 8) break;
        }
        return List.copyOf(logs);
    }

    static List<ProjectQueue.ReachableOre> reachableOre(MinecraftClient client) {
        if (!SubsurfaceAccess.hasFooting(client) || client.interactionManager == null) return List.of();
        var feet = client.player.getBlockPos();
        var observations = new LinkedHashMap<String, ProjectQueue.ReachableOre>();
        for (var pos : BlockPos.iterateOutwards(feet, 8, 5, 8)) {
            if (!client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            var state = client.world.getBlockState(pos);
            if (state.isAir() || !state.isToolRequired()) continue;
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            if (!id.endsWith("_ore") && !id.equals("minecraft:ancient_debris")) continue;
            if (observations.containsKey(id) || BuildProtection.blocked(pos) || supportsAnotherPlayer(client, pos)
                    || !hasTool(client, state) || feet.getSquaredDistance(pos) > 100) continue;
            var stand = nearOreDropHeight(client, pos, feet) && canReachFromHere(client, feet, pos)
                    ? feet : walkableOreStand(client, feet, pos, 0);
            if (stand == null) continue;
            observations.put(id, new ProjectQueue.ReachableOre(id,
                    new ProjectQueue.ResourceSite(pos.getX(), pos.getY(), pos.getZ()),
                    new ProjectQueue.ResourceSite(stand.getX(), stand.getY(), stand.getZ())));
            if (observations.size() >= 8) break;
        }
        return List.copyOf(observations.values());
    }

    private static BlockPos walkableOreStand(MinecraftClient client, BlockPos feet, BlockPos ore, int cover) {
        for (var stand : BlockPos.iterateOutwards(ore.withY(feet.getY()), 3, 0, 3)) {
            if (!stand.isWithinDistance(feet, COVERED_ORE_WALK_RADIUS) || stand.down().equals(ore)
                    || cover > 0 && (rejectedStands.containsKey(stand)
                        || excluded != null && (excluded.contains(stand) || excluded.contains(stand.up())))
                    || !ProjectWorker.knownTrailFooting(client, stand)
                    || !nearOreDropHeight(client, ore, stand)) continue;
            var start = client.player.getPos();
            var finish = Vec3d.ofBottomCenter(stand);
            double reach = Math.min(4.3, client.interactionManager.getReachDistance() - .1);
            var eye = finish.add(0, client.player.getEyeY() - start.y, 0);
            if (miningHit(client, ore, eye, reach) == null && coverHitFrom(client, stand, ore, cover, eye) == null) continue;
            int steps = Math.max(1, (int) Math.ceil(finish.distanceTo(start) * 2));
            boolean clear = true;
            for (int i = 1; i <= steps; i++) {
                var point = start.lerp(finish, i / (double) steps);
                if (!ProjectWorker.knownTrailFooting(client, BlockPos.ofFloored(point))
                        || !client.world.isSpaceEmpty(client.player, client.player.getBoundingBox().offset(point.subtract(start)))) {
                    clear = false;
                    break;
                }
            }
            if (clear) return stand.toImmutable();
        }
        return null;
    }

    static boolean isSurfaceResource(net.minecraft.block.Block block) {
        return block.getDefaultState().isIn(BlockTags.LOGS)
                || block instanceof FlowerBlock || block instanceof TallFlowerBlock;
    }

    static boolean isSurfaceGathering(MinecraftClient client, List<String> sources) {
        return client.world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)
                && sources.stream().map(id -> Registries.BLOCK.get(new Identifier(id)))
                .allMatch(MiningTask::isSurfaceResource);
    }

    private static void startMining(MinecraftClient client, Job next, boolean project, ProjectGather.Area exclude) {
        if(client.player==null||client.world==null||LocalFormation.here()==null){fail("Not in game");return;}
        if(!BaritoneClient.available()){fail("Install Baritone for mining");return;}
        var ids=ResourceCatalog.blocks(next.resource,next.variant);
        for(String id:ids)if(!Registries.BLOCK.containsId(new Identifier(id))){fail("Unknown mining block: "+id);return;}
        for(String id:ids){var state=Registries.BLOCK.get(new Identifier(id)).getDefaultState();if(state.isAir()||state.getHardness(client.world,client.player.getBlockPos())<0){fail("Choose a breakable mining block");return;}}
        boolean surface = project && isSurfaceGathering(client, ids);
        try {
            settings = surface ? BaritoneSettings.surfaceGathering() : new BaritoneSettings(exclude == null, project);
        } catch (ReflectiveOperationException | LinkageError error) {
            fail("Could not configure mining approach");
            return;
        }
        projectMining = project;
        localOnly = reachableOnly = false;
        surfaceResource = surface;
        checkedWiderBeforeRememberedSite = false;
        nextLocalWideScan = 0;
        surfaceRouteGoal = surfaceExplorationGoal = null;
        rejectedSurfaceExits.clear();
        resetSurfaceTrail();
        nextSurfaceSurvey = 0;
        toolShortfall = false;
        routeToolShortfall = false;
        subsurface.reset();
        lastCaveProbeY = Integer.MAX_VALUE;
        nextCaveProbe = 0;
        lastProspectingCheckpointY = client.player.getBlockY();
        rememberedSite = null;
        rememberedRouteRegions.clear();
        rememberedProgressAt = nextRememberedPath = 0;
        observedSite = null;
        surfaceSearchAnchor = lastExplorationExit = null;
        surfaceLedgeOrigin = null;
        surfaceLedgeEnds = 0;
        surfaceSearchFailures = explorationExits = 0;
        excluded=exclude;
        blocks = ids.stream().map(id -> Registries.BLOCK.get(new Identifier(id)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        job=next;generation++;owner=UUID.randomUUID()+"-"+client.player.getGameProfile().getName();
        var here=LocalFormation.here();world=here.server()+"|"+here.dimension();
        paused=false;
        nextDropScan = nextPickupRoomCheck = 0;
        age=nextSearch=nextPath=nextRenew=exploration=0;requesting=dead=false;target=null;stand=null;pickup=null;leaseToken=null;origin=client.player.getPos();rejected.clear();
        rejectedStands.clear();
        pendingCandidates.clear();
        status="Searching: "+next.resource+" ("+next.variant+")";BotEvents.add("info","mining",status);
    }
    private static void fail(String reason){status=reason;BotEvents.add("warn","mining",reason);}
    static void stop(){
        finishWideSearch(MinecraftClient.getInstance());
        resetSurfaceTrail();
        if(job!=null){stopPath();var client=MinecraftClient.getInstance();if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();release(job,leaseToken);}
        minedDrops.reset();
        reservedDrop = null;
        projectDrop = null;
        pendingCandidates.clear();
        job=null;generation++;target=null;stand=null;pickup=null;leaseToken=null;requesting=false;
        if(settings!=null){settings.close();settings=null;}
        if(!status.isEmpty())status="Mining stopped";
    }
    static void onDeath(){if(job!=null&&!dead){dead=true;finishWideSearch(MinecraftClient.getInstance());stopPath();clearReservation();minedDrops.reset();pendingCandidates.clear();pickup=null;status="Mining waits for respawn";}}
    static void tick(MinecraftClient client){
        if(job==null||paused)return;
        var here=LocalFormation.here();
        if(client.player==null||client.world==null||here==null||!world.equals(here.server()+"|"+here.dimension())){stop();return;}
        age++;dead=false;
        if (surfaceLedgeEnds != 0 && client.player.isOnGround()
                && (age >= surfaceLedgeEnds || client.player.getBlockY() < surfaceLedgeOrigin.getY()
                    || client.player.getHealth() < 16)) {
            stopPath();
            nextPath = age;
        }
        MiningViewDistance.update(client, age, target);
        if (!MiningViewDistance.active()) wideChunks = Collections.emptyIterator();
        if (reservedDrop != null) {
            collectReservedDrop(client);
            return;
        }
        if(target!=null){
            if (!client.world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)
                    || protectedTarget(client, target)) {
                stopPath();
                clearReservation();
                client.interactionManager.cancelBlockBreaking();
                nextSearch=age+1;
                return;
            }
            if(!matches(client,target)){
                pickup=Vec3d.ofBottomCenter(target);pickupUntil=age+(projectDrop==null?60:100);
                exposedOrePickupSteps = !openedCover.isEmpty() && !surfaceResource ? EXPOSED_ORE_PICKUP_STEPS : 0;
                protectedOrePickup = false;
                clearReservation();nextSearch=age+12;
                if (projectDrop == null) pickupPath();
                else stopPath();
                return;
            }
            if (supportsAnotherPlayer(client, target)) {
                rejected.put(target, age + 60);
                stopPath();
                clearReservation();
                client.interactionManager.cancelBlockBreaking();
                nextSearch = age + 1;
                return;
            }
            if(age>=nextRenew&&!requesting){request(client,target,stand,"renew");nextRenew=age+40;}
            if(System.currentTimeMillis()>=leaseUntil){stopPath();client.interactionManager.cancelBlockBreaking();status="Waiting for mining reservation";return;}
            double reach=Math.min(4.3,client.interactionManager.getReachDistance()-.1);
            var ray = miningHit(client, target, client.player.getEyePos(), reach);
            var feet = client.player.getBlockPos();
            boolean atPlannedStand = client.player.squaredDistanceTo(stand) <= 1.44;
            // The stand is a route, not a requirement when a project worker already has a safe hit.
            if (ray != null && (atPlannedStand || canMineFromCurrentFooting(client, feet))
                    && !feet.down().equals(target)
                    && (!projectMining || !ResourceCatalog.isOre(job.resource) || nearOreDropHeight(client, target, feet))) {
                approachProgress = age;
                stopPath();
                if (!equip(client, target)) {
                    toolShortfall = projectMining;
                    status = "Need a suitable tool";
                    return;
                }
                if (projectMining) {
                    if (excluded == null || !excluded.contains(feet)) {
                        observedSite = new ProjectQueue.ResourceSite(feet.getX(), feet.getY(), feet.getZ());
                    }
                }
                client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,ray.getPos());
                String blockName=Registries.BLOCK.getId(client.world.getBlockState(target).getBlock()).getPath();
                client.interactionManager.updateBlockBreakingProgress(ray.getBlockPos(),ray.getSide());client.player.swingHand(Hand.MAIN_HAND);
                status="Mining "+blockName;
            } else if (reachableOnly) {
                stopPath();
                rejected.put(target, age + 100);
                client.interactionManager.cancelBlockBreaking();
                clearReservation();
                nextSearch = age + 1;
                return;
            } else if (!clearNearbyCover(client, feet)) {
                var p = BlockPos.ofFloored(stand);
                double distance = client.player.getPos().distanceTo(stand);
                if (approachDistance - distance >= .6) {
                    approachDistance = distance;
                    approachProgress = age;
                    localApproachTried = false;
                }
                if (subsurface.continuePassage(client, age, excluded)
                        || subsurface.continueLocalApproach(client, age)) return;
                if (localApproachFrom != null) {
                    // A sideways step can reach the lip of a cave without reducing straight-line distance.
                    if (!feet.equals(localApproachFrom)) {
                        approachProgress = age;
                        localApproachTried = false;
                    }
                    localApproachFrom = null;
                }
                if (surfaceResource && age - approachProgress >= 80
                        && (age - approachProgress >= 250 || !BaritoneClient.pursuing(p))
                        && climbTowardElevatedStand(client, feet, p)) return;
                boolean prospecting = prospectingAltitude(client) != null;
                if (projectMining && ResourceCatalog.isOre(job.resource) && !p.isWithinDistance(feet, DISTANT_ORE_STAND)
                        && age - approachProgress >= 80 && !BaritoneClient.pursuing(p)) {
                    abandonApproach(client, feet, p, prospecting, true);
                    return;
                }
                if (projectMining && !localApproachTried && localApproachSteps < MAX_PROTECTED_APPROACH_STEPS
                        && age - approachProgress >= 80
                        && subsurface.hasDiggingTool(client)
                        && SubsurfaceAccess.localApproachInRange(feet, p, prospecting)
                        && shouldOpenLocalApproach(feet, p, prospecting)) {
                    stopPath();
                    localApproachTried = true;
                    if (subsurface.openLocalApproach(client, age, p, excluded, prospecting)) {
                        localApproachFrom = feet;
                        localApproachSteps++;
                        status = "Opening a protected approach to reserved " + job.resource;
                        BotEvents.add("info", "mining", status);
                        return;
                    }
                }
                if (age - approachProgress >= 80 && !BaritoneClient.pursuing(p) && trySurfaceLedge(client, p)) {
                    approachProgress = age;
                    return;
                }
                if (projectMining && age - approachProgress >= 110
                        && (age - approachProgress >= 250 || !BaritoneClient.pursuing(p))
                        && tryAlternateStand(client, p)) return;
                if (age - approachProgress >= 250) {
                    abandonApproach(client, feet, p, prospecting, false);
                    return;
                }
                if (recoverRoute(client, p)) return;
                if (age >= nextPath && !BaritoneClient.pursuing(p)) {
                    nextPath = age + 60;
                    if (!path(p)) {
                        status = "Baritone could not approach reserved " + job.resource;
                        rejected.put(target, age + 200);
                        clearReservation();
                    } else status = "Approaching reserved " + job.resource;
                }
            }
            return;
        }
        if(pickup!=null){
            if (projectDrop != null) {
                if (protectedOrePickup && (subsurface.continuePassage(client, age, excluded)
                        || subsurface.continueLocalApproach(client, age))) return;
                protectedOrePickup = false;
                if (client.player.getInventory().getEmptySlot() < 0 && age >= nextPickupRoomCheck) {
                    var drop = client.world.getEntitiesByClass(ItemEntity.class, new Box(BlockPos.ofFloored(pickup)).expand(1.5),
                            item -> item.isOnGround() && eligibleDrop(client, item)).stream()
                            .min(Comparator.comparingDouble(client.player::squaredDistanceTo)).orElse(null);
                    if (drop != null) makeRoomForDrop(client, drop);
                }
                var state = minedDrops.tick(client, projectDrop::matches, pickup);
                if (age >= pickupUntil || state == DroppedItemSearch.State.UNAVAILABLE
                        || state == DroppedItemSearch.State.SEARCHING && age >= nextSearch) {
                    minedDrops.reset();
                    if (openExposedOrePickup(client)) return;
                    pickup = null;
                    nextSearch = age + 1;
                } else status = "Gathering mined drops";
                return;
            }
            if(age>=pickupUntil||(age>=nextSearch&&client.player.squaredDistanceTo(pickup)<1)){
                pickup=null;stopPath();nextSearch=age+1;
            }else if(age%20==0)pickupPath();
            status="Gathering mined drops";return;
        }
        if (retracingSurfaceTrail() && followSurfaceTrail(client, client.player.getBlockPos())) return;
        if (requestLooseDrop(client)) return;
        if (subsurface.continuePassage(client, age, excluded)) {
            // The bounded cut can exceed the exit timer. Keep the route through its first walking step.
            if (surfaceRouteGoal != null) surfaceProgressAt = age;
            status = surfaceRouteGoal == null ? "Opening a short prospecting passage"
                    : "Opening stairs to the surface for " + job.resource;
            return;
        }
        if (subsurface.continueExplorationExit(client, age)) {
            status = "Opening a local exit to continue gathering " + job.resource;
            return;
        }
        if (surfaceRouteGoal != null && seekSurface(client)) return;
        var routeGoal = surfaceRouteGoal != null ? surfaceRouteGoal
                : rememberedSite != null ? rememberedSite : surfaceExplorationGoal;
        if (pathActive && recoverRoute(client, routeGoal)) return;
        if (requesting) return;
        if (pendingCandidates.isEmpty()) {
            if (age < nextSearch) return;
            if (MiningViewDistance.active()) {
                if (age >= nextLocalWideScan) {
                    nextLocalWideScan = age + 100;
                    scan(client);
                }
                if (pendingCandidates.isEmpty()) scanWider(client);
            } else scan(client);
        }
        for (int checked = 0; checked < STANDS_PER_TICK && !pendingCandidates.isEmpty(); checked++) {
            var candidate = pendingCandidates.removeFirst();
            if (!client.world.isChunkLoaded(candidate.getX() >> 4, candidate.getZ() >> 4)
                    || !matches(client, candidate) || protectedTarget(client, candidate)
                    || deferredOreRoute(client, candidate)) continue;
            if (!hasTool(client, client.world.getBlockState(candidate))) {
                scanNeedsTool = true;
                continue;
            }
            var place = supportsAnotherPlayer(client, candidate) ? null : chooseStand(client, candidate);
            if (place != null) {
                subsurface.stop();
                pendingCandidates.clear();
                nextSearch = age + 20;
                request(client, candidate, place, "claim");
                return;
            }
            rejected.put(candidate, age + 100);
        }
        if (!pendingCandidates.isEmpty()) {
            status = "Checking approaches to " + job.resource;
            return;
        }
        nextSearch = age + 20;
        if (scanNeedsTool) {
            toolShortfall = projectMining;
            status = "Need a suitable tool for " + job.resource;
            stopPath();
            return;
        }
        if (MiningViewDistance.active()) {
            nextSearch = wideChunks.hasNext() ? age + 1 : age + 10;
            status = "Checking more loaded chunks for " + job.resource;
        } else explore(client);
    }

    private static void abandonApproach(MinecraftClient client, BlockPos feet, BlockPos failedStand,
                                        boolean prospecting, boolean distant) {
        rejectedStands.put(failedStand, age + 600);
        if (projectMining && ResourceCatalog.isOre(job.resource)) {
            oreRoutes.defer(world, job.resource, target, feet, System.nanoTime());
            BotEvents.add("info", "mining", (distant ? "Could not reach a distant vein near "
                    : "Trying a different vein after blocked approaches near ") + target.toShortString());
        } else rejected.put(target, age + 1200);
        stopPath();
        clearReservation();
        client.interactionManager.cancelBlockBreaking();
        status = "Trying another approach to " + job.resource;
        nextSearch = age + 1;
        if (prospecting) {
            rememberedSite = null;
            pendingCandidates.clear();
            nextSearch = age + 20;
            explore(client);
        }
    }

    private static boolean openExposedOrePickup(MinecraftClient client) {
        if (exposedOrePickupSteps <= 0) return false;
        var drop = client.world.getEntitiesByClass(ItemEntity.class, new Box(BlockPos.ofFloored(pickup)).expand(1.5),
                item -> item.isOnGround() && availableDrop(client, item)).stream()
                .min(Comparator.comparingDouble(client.player::squaredDistanceTo)).orElse(null);
        if (drop == null || !subsurface.openMiningPickupStep(client, age, drop.getBlockPos(), excluded)) return false;
        exposedOrePickupSteps--;
        protectedOrePickup = true;
        pickupUntil = age + 100;
        status = "Opening a protected pickup route for " + job.resource;
        return true;
    }

    private static boolean requestLooseDrop(MinecraftClient client) {
        if (requesting || projectDrop == null || age < nextDropScan) return false;
        nextDropScan = age + 10;
        var player = client.player;
        var feet = player.getBlockPos();
        long now = System.nanoTime();
        failedDropRoutes.values().removeIf(route -> route.retryAt() <= now);
        var loose = client.world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(LOOSE_DROP_RADIUS),
                item -> item.isOnGround() && player.squaredDistanceTo(item) <= LOOSE_DROP_RADIUS * LOOSE_DROP_RADIUS
                        && rejected.getOrDefault(item.getBlockPos(), 0) <= age
                        && !deferDropRoute(item, feet, now) && eligibleDrop(client, item))
                .stream().min(Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
        if (loose == null || !makeRoomForDrop(client, loose)) return false;
        var cell = loose.getBlockPos();
        request(client, cell, Vec3d.ofBottomCenter(cell), "claim", loose);
        return true;
    }

    private static boolean deferDropRoute(ItemEntity item, BlockPos feet, long now) {
        var prior = failedDropRoutes.get(item.getUuid());
        return prior != null && prior.stillApplies(world, item.getBlockPos(), feet, now);
    }

    private static boolean availableDrop(MinecraftClient client, ItemEntity item) {
        return eligibleDrop(client, item) && ProjectInventory.canFit(client, item.getStack(), 1);
    }

    private static boolean makeRoomForDrop(MinecraftClient client, ItemEntity drop) {
        if (ProjectInventory.canFit(client, drop.getStack(), 1)) return true;
        if (age < nextPickupRoomCheck || !SubsurfaceAccess.hasFooting(client)) return false;
        nextPickupRoomCheck = age + 20;
        return ProjectInventory.releaseSurplus(client, client.player.playerScreenHandler, projectDrop::matches, "mined cargo");
    }

    private static boolean eligibleDrop(MinecraftClient client, ItemEntity item) {
        if (!item.isAlive() || !projectDrop.matches(item.getStack())) return false;
        var cell = item.getBlockPos();
        if (BuildProtection.blocked(cell) || excluded != null && excluded.contains(cell)) return false;
        // Leave nearby players room to collect their own harvest or receive a delivery.
        for (var peer : client.world.getPlayers()) {
            if (peer != client.player && peer.isAlive() && !peer.isSpectator() && peer.squaredDistanceTo(item) <= 9) return false;
        }
        return true;
    }

    private static void collectReservedDrop(MinecraftClient client) {
        if (reservedDrop.getBlockPos().equals(target) && availableDrop(client, reservedDrop)
                && System.currentTimeMillis() < leaseUntil) {
            if (age < dropDeadline && minedDrops.approachReserved(client, reservedDrop) == DroppedItemSearch.State.APPROACHING) {
                status = "Collecting dropped " + projectDrop;
                return;
            }
            // Keep the failed approach across assignments and retry if the drop or footing changes.
            var id = reservedDrop.getUuid();
            failedDropRoutes.remove(id);
            failedDropRoutes.put(id, new FailedDropApproach(world, target, client.player.getBlockPos(),
                    System.nanoTime() + DROP_ROUTE_BACKOFF));
            if (failedDropRoutes.size() > MAX_FAILED_DROP_ROUTES) failedDropRoutes.remove(failedDropRoutes.keySet().iterator().next());
            BotEvents.add("debug", "mining", "Skipped stalled drop route at " + target.toShortString());
        }
        minedDrops.reset();
        clearReservation();
        nextSearch = age + 1;
    }

    private static void scan(MinecraftClient client) {
        rejectedStands.values().removeIf(until -> until <= age);
        rejected.values().removeIf(until -> until <= age);
        var position = client.player.getBlockPos();
        var world = client.world;
        var candidates = new ArrayList<BlockPos>();
        var nearbyHits = new ArrayList<BlockPos>();
        boolean safeFooting = canMineFromCurrentFooting(client, position);
        int coverLimit = safeFooting ? coverLimit(client) : 0;
        boolean lookForCoveredOre = coverLimit > 0 && !surfaceResource;
        var suitability = new HashMap<BlockState, Boolean>();
        scanNeedsTool = false;
        int radius = reachableOnly ? REACHABLE_SCAN_RADIUS : surfaceResource ? 20 : 12;
        int height = reachableOnly ? REACHABLE_SCAN_HEIGHT : 10;
        for (var pos : BlockPos.iterateOutwards(position, radius, height, radius)) {
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            var state = world.getBlockState(pos);
            boolean covered = !reachableOnly && coverLimit > 0 && matches(state)
                    && coverHit(client, position, pos, coverLimit) != null;
            boolean localCover = covered || lookForCoveredOre && pos.isWithinDistance(position, COVERED_ORE_WALK_RADIUS);
            if (!eligibleCandidate(client, pos, state, suitability, localCover)) continue;
            if (safeFooting && (covered || canReachFromHere(client, position, pos))) nearbyHits.add(pos.toImmutable());
            else if (!reachableOnly) candidates.add(pos.toImmutable());
        }
        Comparator<BlockPos> byDistance = Comparator.comparingDouble(p -> p.getSquaredDistance(position));
        nearbyHits.sort(byDistance);
        candidates.sort(byDistance);
        nearbyHits.addAll(candidates);
        queueDryTargetsFirst(client, nearbyHits);
    }

    private static void queueDryTargetsFirst(MinecraftClient client, List<BlockPos> sorted) {
        if (!projectMining) {
            pendingCandidates.addAll(sorted);
            return;
        }
        var wet = new ArrayList<BlockPos>();
        for (var target : sorted) {
            if (fluidExposed(client.world, target)) wet.add(target);
            else pendingCandidates.addLast(target);
        }
        pendingCandidates.addAll(wet);
    }

    private static boolean fluidExposed(BlockView world, BlockPos target) {
        if (!world.getFluidState(target).isEmpty()) return true;
        for (var side : Direction.values()) {
            if (!world.getFluidState(target.offset(side)).isEmpty()) return true;
        }
        return false;
    }

    private static boolean deferredOreRoute(MinecraftClient client, BlockPos ore) {
        if (!projectMining || !ResourceCatalog.isOre(job.resource)) return false;
        var feet = client.player.getBlockPos();
        if (!oreRoutes.deferred(world, job.resource, ore, feet, System.nanoTime())) return false;
        // The previous approach no longer matters if the worker has a usable hit from its current footing.
        return !ore.isWithinDistance(feet, 5) || !canMineFromCurrentFooting(client, feet)
                || !nearOreDropHeight(client, ore, feet)
                || !canReachFromHere(client, feet, ore) && coverHit(client, feet, ore, coverLimit(client)) == null;
    }

    private static boolean eligibleCandidate(MinecraftClient client, BlockPos pos, BlockState state,
                                             Map<BlockState, Boolean> suitability, boolean canExpose) {
        if (!matches(state) || excluded != null && excluded.contains(pos)
                || rejected.containsKey(pos) || protectedTarget(client, pos) || deferredOreRoute(client, pos)) return false;
        if (!canExpose && projectMining && excluded != null && state.isFullCube(client.world, pos)
                && completelyCovered(client.world, pos)) return false;
        if (suitability.computeIfAbsent(state, block -> hasTool(client, block))) return true;
        scanNeedsTool = true;
        return false;
    }

    private static void scanWider(MinecraftClient client) {
        rejectedStands.values().removeIf(until -> until <= age);
        rejected.values().removeIf(until -> until <= age);
        scanNeedsTool = false;
        var feet = client.player.getBlockPos();
        var view = client.world;
        var suitability = new HashMap<BlockState, Boolean>();
        if (!wideChunks.hasNext()) {
            // Visit nearby rings first and revisit them as the server delivers more chunks.
            var center = new ChunkPos(feet);
            int radius = MiningViewDistance.loadedRadius(client);
            wideChunks = ChunkPos.stream(center, radius)
                    .sorted(Comparator.comparingInt(center::getChebyshevDistance)).iterator();
        }
        for (int i = 0; i < WIDE_CHUNKS_PER_TICK && wideChunks.hasNext(); i++) {
            var coordinates = wideChunks.next();
            var chunk = view.getChunkManager().getChunk(coordinates.x, coordinates.z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            var candidates = new ArrayList<BlockPos>();
            var pos = new BlockPos.Mutable();
            var sections = chunk.getSectionArray();
            for (int section = 0; section < sections.length; section++) {
                int sectionY = view.sectionIndexToCoord(section) << 4;
                if (!surfaceResource && (sectionY > feet.getY() + 10 || sectionY + 15 < feet.getY() - 10)) continue;
                var data = sections[section];
                if (data.isEmpty() || !data.hasAny(state -> blocks.contains(state.getBlock()))) continue;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int top = surfaceResource ? chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z) : feet.getY() + 10;
                        int bottom = surfaceResource ? top - 24 : feet.getY() - 10;
                        for (int y = Math.max(sectionY, bottom); y <= Math.min(sectionY + 15, top); y++) {
                            pos.set(coordinates.getStartX() + x, y, coordinates.getStartZ() + z);
                            if (eligibleCandidate(client, pos, data.getBlockState(x, y & 15, z), suitability, false)) {
                                candidates.add(pos.toImmutable());
                            }
                        }
                    }
                }
            }
            if (!candidates.isEmpty()) {
                candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(feet)));
                queueDryTargetsFirst(client, candidates);
                return;
            }
        }
    }

    private static boolean canMineFromCurrentFooting(MinecraftClient client, BlockPos feet) {
        return projectMining && (excluded == null || !excluded.contains(feet) && !excluded.contains(feet.up()))
                && SubsurfaceAccess.hasFooting(client);
    }

    private static boolean canReachFromHere(MinecraftClient client, BlockPos feet, BlockPos target) {
        var eye = client.player.getEyePos();
        double reach = Math.min(4.3, client.interactionManager.getReachDistance() - .1);
        return !feet.down().equals(target) && eye.squaredDistanceTo(Vec3d.ofCenter(target)) <= (reach + 1) * (reach + 1)
                && miningHit(client, target, eye, reach) != null;
    }

    private static Vec3d chooseStand(MinecraftClient client, BlockPos target) {
        var feet = client.player.getBlockPos();
        if (reachableOnly) {
            return canMineFromCurrentFooting(client, feet) && canReachFromHere(client, feet, target)
                    ? Vec3d.ofBottomCenter(feet) : null;
        }
        if (!rejectedStands.containsKey(feet) && canMineFromCurrentFooting(client, feet)
                && (!ResourceCatalog.isOre(job.resource) || nearOreDropHeight(client, target, feet))
                && (canReachFromHere(client, feet, target)
                    || coverHit(client, feet, target, coverLimit(client)) != null)) return Vec3d.ofBottomCenter(feet);
        if (projectMining && !surfaceResource && ResourceCatalog.isOre(job.resource)
                && completelyCovered(client.world, target)) {
            if (canMineFromCurrentFooting(client, feet) && target.isWithinDistance(feet, COVERED_ORE_WALK_RADIUS)) {
                int cover = coverLimit(client);
                var nearby = cover == 0 ? null : walkableOreStand(client, feet, target, cover);
                if (nearby != null) return Vec3d.ofBottomCenter(nearby);
            }
            if (excluded != null) return null;
        }
        return standing(client, target);
    }

    private static int coverLimit(MinecraftClient client) {
        if (surfaceResource) return MAX_CANOPY_LEAVES;
        return projectMining && ResourceCatalog.isOre(job.resource) && subsurface.hasDiggingTool(client) ? MAX_ORE_COVER : 0;
    }

    private static BlockHitResult coverHit(MinecraftClient client, BlockPos feet, BlockPos resource, int limit) {
        return coverHitFrom(client, feet, resource, limit, client.player.getEyePos());
    }

    private static BlockHitResult coverHitFrom(MinecraftClient client, BlockPos feet, BlockPos resource,
                                               int limit, Vec3d eye) {
        if (limit == 0) return null;
        var point = Vec3d.ofCenter(resource);
        double reach = Math.min(4.3, client.interactionManager.getReachDistance() - .1);
        if (eye.squaredDistanceTo(point) > reach * reach || feet.down().equals(resource)
                || !surfaceResource && !nearOreDropHeight(client, resource, feet)) return null;

        var obstructions = new ArrayList<BlockHitResult>();
        var exposed = BlockView.raycast(eye, point, client.world, (world, pos) -> {
            var shape = pos.equals(resource) ? world.getBlockState(pos).getOutlineShape(world, pos)
                    : world.getBlockState(pos).getCollisionShape(world, pos);
            var hit = shape.raycast(eye, point, pos);
            if (hit == null || pos.equals(resource)) return hit;
            if (obstructions.size() >= limit || feet.down().equals(pos)
                    || excluded != null && excluded.contains(pos) || BuildProtection.blocked(pos)) return hit;
            boolean removable = surfaceResource
                    ? ProjectWorker.canClearNaturalLeaves(client, pos) && !supportsAnotherPlayer(client, pos)
                    : SubsurfaceAccess.canClearOreCover(client, pos, excluded);
            if (!removable) return hit;
            obstructions.add(hit.withBlockPos(pos.toImmutable()));
            return null;
        }, ignored -> null);
        return exposed != null && exposed.getBlockPos().equals(resource) && !obstructions.isEmpty()
                ? obstructions.get(0) : null;
    }

    private static boolean clearNearbyCover(MinecraftClient client, BlockPos feet) {
        if (!canMineFromCurrentFooting(client, feet)) return false;
        int limit = coverLimit(client);
        var cover = coverHit(client, feet, target, limit);
        if (cover == null || openedCover.size() >= limit && !openedCover.contains(cover.getBlockPos())) return false;
        String material = surfaceResource ? "foliage" : "cover";
        if (openedCover.add(cover.getBlockPos())) {
            coverAttemptEnds = age + 60;
            BotEvents.add("debug", "mining", "Clearing natural " + material + " in reach of reserved " + target.toShortString());
        }
        if (age >= coverAttemptEnds || !surfaceResource && !subsurface.equipDiggingTool(client, true)) return false;
        stopPath();
        client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, cover.getPos());
        client.interactionManager.updateBlockBreakingProgress(cover.getBlockPos(), cover.getSide());
        client.player.swingHand(Hand.MAIN_HAND);
        status = "Clearing natural " + material + " for " + job.resource;
        return true;
    }

    private static boolean completelyCovered(net.minecraft.world.World world, BlockPos pos) {
        for (Direction side : Direction.values()) {
            var adjacent = pos.offset(side);
            if (!world.getBlockState(adjacent).isFullCube(world, adjacent)) return false;
        }
        return true;
    }

    private static void explore(MinecraftClient client) {
        if (localOnly) {
            status = "Looking for reachable local " + job.resource;
            return;
        }
        // A nearby cave exit is more useful than a distant surface waypoint while buried.
        if (surfaceResource && seekSurface(client)) return;
        if (surfaceResource && rememberedSite != null
                && !rememberedSite.isWithinDistance(client.player.getBlockPos(), NEARBY_SITE_RADIUS)) {
            if (MiningViewDistance.active()) {
                status = "Checking more loaded chunks for " + job.resource;
                return;
            }
            if (!checkedWiderBeforeRememberedSite) {
                checkedWiderBeforeRememberedSite = true;
                if (widenSearch(client)) return;
            }
        }
        if (returnToResourceSite(client)) return;
        Integer depth = prospectingAltitude(client);
        boolean probeAtThisHeight = depth == null || Math.abs(client.player.getBlockY() - depth) <= 12;
        if ((projectMining || Boolean.getBoolean("botfury.managedHttp")) && probeAtThisHeight
                && widenSearch(client)) return;
        if (surfaceResource) {
            exploreSurface(client);
            return;
        }
        if (depth != null) {
            var feet = client.player.getBlockPos();
            if (lastProspectingCheckpointY - feet.getY() >= 8 && SubsurfaceAccess.hasFooting(client)
                    && !BuildProtection.blocked(feet) && (excluded == null || !excluded.contains(feet))) {
                // Reuse the descent after restocking, even if the worker has yet to find any ore.
                observedSite = new ProjectQueue.ResourceSite(feet.getX(), feet.getY(), feet.getZ());
                lastProspectingCheckpointY = feet.getY();
            }
            if (pathActive) {
                if (deepExplorationGoal != null && age < nextPath && BaritoneClient.pursuing(deepExplorationGoal)) {
                    return;
                }
                stopPath();
            }
            if (!subsurface.continuingLocalDescent() && tryNaturalDescent(client, depth)) return;
            if (subsurface.advance(client, age, depth, excluded)) {
                status = "Prospecting for " + job.resource + " toward Y " + depth;
                return;
            }
            if (!subsurface.hasDiggingTool(client)) {
                toolShortfall = true;
                routeToolShortfall = true;
                status = "Route pickaxes are worn out";
                return;
            }
        }
        status = "Exploring for " + job.resource;
        if(age>=nextPath){
            if (depth == null && projectMining && recoverExploration(client)) return;
            nextPath = age + (projectMining ? 400 : 200);
            double angle = (client.player.getUuid().hashCode() & 1023) * Math.PI / 512
                    + exploration * (projectMining ? .32 : .8);
            exploration++;
            double radius = projectMining ? Math.min(1024, 32 + 24.0 * exploration) : 24 + 16 * (exploration % 3);
            int x = (int)(origin.x + Math.cos(angle) * radius);
            int z = (int)(origin.z + Math.sin(angle) * radius);
            if (depth != null) {
                status = "Searching for " + job.resource + " around " + x + ", " + depth + ", " + z;
                deepExplorationGoal = new BlockPos(x, depth, z);
                path(deepExplorationGoal);
            } else path("explore " + x + " " + z);
        }
    }

    private static void exploreSurface(MinecraftClient client) {
        if (age < nextPath) return;
        var world = client.world;
        var feet = client.player.getBlockPos();
        if (surfaceExplorationGoal != null) stopPath();
        double angle = (client.player.getUuid().hashCode() & 1023) * Math.PI / 512 + exploration++ * .8;
        int x = feet.getX() + (int)(Math.cos(angle) * 24);
        int z = feet.getZ() + (int)(Math.sin(angle) * 24);
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -12; dx <= 12; dx += 3) {
            for (int dz = -12; dz <= 12; dz += 3) {
                int sx = x + dx;
                int sz = z + dz;
                if (!world.isChunkLoaded(sx >> 4, sz >> 4)) continue;
                int height = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sx, sz);
                var stand = new BlockPos(sx, height, sz);
                if (height < feet.getY() - 4 || !world.isInBuildLimit(stand.up()) || !world.getWorldBorder().contains(stand)
                        || BuildProtection.blocked(stand) || BuildProtection.blocked(stand.up())
                        || excluded != null && (excluded.contains(stand) || excluded.contains(stand.up()))
                        || !world.getFluidState(stand.down()).isEmpty()) continue;
                var floor = world.getBlockState(stand.down());
                if (floor.isIn(BlockTags.LOGS) || !ProjectPlacement.flushFloor(floor, world, stand.down())
                        || !world.getBlockState(stand).getCollisionShape(world, stand).isEmpty()
                        || !world.getBlockState(stand.up()).getCollisionShape(world, stand.up()).isEmpty()) continue;
                double score = dx * dx + dz * dz + Math.abs(height - feet.getY()) * 3;
                if (score < bestScore) {
                    best = stand;
                    bestScore = score;
                }
            }
        }
        nextPath = age + (best == null ? 60 : 300);
        status = "Searching the surface for " + job.resource;
        if (best != null && path(best)) surfaceExplorationGoal = best;
    }

    private static boolean seekSurface(MinecraftClient client) {
        if (!client.world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) return false;
        var feet = client.player.getBlockPos();
        if (surfaceRouteGoal != null && feet.getY() >= surfaceRouteGoal.getY() - 2) {
            surfaceRouteGoal = null;
            resetSurfaceTrail();
            subsurface.stop();
            if (pathActive) stopPath();
            return false;
        }
        if (surfaceRouteGoal != null) {
            double distance = Math.sqrt(surfaceRouteGoal.getSquaredDistance(feet));
            if (feet.getY() >= surfaceProgressY && (observedSite == null || feet.getY() > observedSite.y())
                    && SubsurfaceAccess.hasFooting(client) && !BuildProtection.blocked(feet.down())
                    && !BuildProtection.blocked(feet)
                    && (excluded == null || !excluded.contains(feet) && !excluded.contains(feet.down()))) {
                observedSite = new ProjectQueue.ResourceSite(feet.getX(), feet.getY(), feet.getZ());
            }
            if (feet.getY() > surfaceProgressY || distance <= surfaceProgressDistance - 3) {
                surfaceProgressAt = age;
                surfaceProgressY = Math.max(surfaceProgressY, feet.getY());
                surfaceProgressDistance = Math.min(surfaceProgressDistance, distance);
            } else if (age - surfaceProgressAt >= SURFACE_EXIT_STALL_TICKS) {
                BotEvents.add("info", "movement", "Trying another surface exit after a stalled climb toward "
                        + surfaceRouteGoal.toShortString());
                rejectedSurfaceExits.put(surfaceRouteGoal, age + SURFACE_EXIT_BACKOFF_TICKS);
                surfaceRouteGoal = null;
                resetSurfaceTrail();
                subsurface.stop();
                if (pathActive) stopPath();
                nextPath = nextSurfaceSurvey = age;
            }
        }
        if (surfaceRouteGoal == null && age >= nextSurfaceSurvey) {
            nextSurfaceSurvey = age + 80;
            rejectedSurfaceExits.values().removeIf(retry -> retry <= age);
            surfaceRouteGoal = localSurfaceExit(client, feet, excluded, exit -> !rejectedSurfaceExits.containsKey(exit));
            if (surfaceRouteGoal != null) observeSurfaceProgress(feet);
        }
        if (surfaceRouteGoal == null) return false;
        if (followSurfaceTrail(client, feet)) return true;
        if (!pathActive || !BaritoneClient.pursuing(surfaceRouteGoal)) {
            if (pathActive) stopPath();
            if (!subsurface.advanceToward(client, age, surfaceRouteGoal, excluded, true, true) && age >= nextPath) {
                nextPath = age + 100;
                path(surfaceRouteGoal);
            }
        }
        status = "Climbing toward the surface for " + job.resource;
        return true;
    }

    private static boolean climbTowardElevatedStand(MinecraftClient client, BlockPos feet, BlockPos goal) {
        if (localOnly || !client.world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)
                || goal.getY() - feet.getY() < ELEVATED_STAND_MIN_RISE
                || !goal.isWithinDistance(feet, ELEVATED_STAND_RANGE)
                || rejectedSurfaceExits.getOrDefault(goal, 0) > age
                || BuildProtection.blocked(goal) || BuildProtection.blocked(goal.up())
                || excluded != null && (excluded.contains(goal) || excluded.contains(goal.up()))
                || !ProjectWorker.knownTrailFooting(client, goal)) return false;
        stopPath();
        clearReservation();
        pendingCandidates.clear();
        resetSurfaceTrail();
        surfaceRouteGoal = goal;
        observeSurfaceProgress(feet);
        nextPath = age;
        BotEvents.add("info", "movement", "Climbing toward a stalled surface stand at " + goal.toShortString());
        return seekSurface(client);
    }

    private static void observeSurfaceProgress(BlockPos feet) {
        surfaceProgressAt = age;
        surfaceProgressY = feet.getY();
        surfaceProgressDistance = Math.sqrt(surfaceRouteGoal.getSquaredDistance(feet));
    }

    private static boolean followSurfaceTrail(MinecraftClient client, BlockPos feet) {
        if (!checkedSurfaceTrail) {
            checkedSurfaceTrail = true;
            surfaceTrail.addAll(ProjectWorker.recordedSurfaceRoute(client, surfaceRouteGoal));
            if (!surfaceTrail.isEmpty()) {
                BotEvents.add("info", "movement", "Retracing " + surfaceTrail.size() + " recorded waypoints to the surface");
            }
        }
        if (surfaceTrailWaypoint != null) {
            if (feet.equals(surfaceTrailWaypoint)) {
                stopPath();
                surfaceTrailWaypoint = null;
            } else if (age < nextPath && ProjectWorker.knownTrailFooting(client, surfaceTrailWaypoint) && pathActive) {
                status = "Retracing the recorded route toward the surface for " + job.resource;
                return true;
            } else {
                stopPath();
                surfaceTrailWaypoint = null;
                surfaceTrail.clear();
                nextPath = age;
            }
        }
        while (!surfaceTrail.isEmpty()) {
            var point = surfaceTrail.removeFirst();
            var stand = new BlockPos(point.x(), point.y(), point.z());
            // Missing or changed waypoints may be skipped only while the next safe stand remains local.
            if (!stand.isWithinDistance(feet, 12) || Math.abs(stand.getY() - feet.getY()) > 6) break;
            if (stand.equals(feet) || !ProjectWorker.knownTrailFooting(client, stand)) continue;
            subsurface.stop();
            if (pathActive) stopPath();
            if (!path(stand)) break;
            surfaceTrailWaypoint = stand;
            nextPath = age + 180;
            status = "Retracing the recorded route toward the surface for " + job.resource;
            return true;
        }
        surfaceTrail.clear();
        return false;
    }

    private static void resetSurfaceTrail() {
        surfaceTrail.clear();
        surfaceTrailWaypoint = null;
        checkedSurfaceTrail = false;
    }

    static BlockPos localSurfaceExit(MinecraftClient client, BlockPos feet, ProjectGather.Area excluded) {
        return localSurfaceExit(client, feet, excluded, exit -> true);
    }

    private static BlockPos localSurfaceExit(MinecraftClient client, BlockPos feet, ProjectGather.Area excluded,
                                             java.util.function.Predicate<BlockPos> candidate) {
        var world = client.world;
        var exits = new ArrayList<BlockPos>();
        for (int dx = -8; dx <= 8; dx += 4) {
            for (int dz = -8; dz <= 8; dz += 4) {
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                var exit = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z), z);
                if (!world.isInBuildLimit(exit.up()) || !world.getWorldBorder().contains(exit)
                        || world.getBlockState(exit.down()).isAir() || !world.getFluidState(exit.down()).isEmpty()
                        || BuildProtection.blocked(exit) || excluded != null && excluded.contains(exit)) continue;
                exits.add(exit);
            }
        }
        if (exits.isEmpty()) return null;
        exits.sort(Comparator.comparingInt(BlockPos::getY));
        // The surrounding terrain can be far above the floor of a skylit ravine.
        int typicalHeight = exits.get(exits.size() / 2).getY();
        if (typicalHeight - feet.getY() < SURFACE_ROUTE_DEPTH) return null;
        return exits.stream().filter(exit -> exit.getY() >= typicalHeight - 4 && candidate.test(exit))
                .min(Comparator.comparingDouble(exit -> exit.getSquaredDistance(feet))).orElse(null);
    }

    private static boolean recoverExploration(MinecraftClient client) {
        var feet = client.player.getBlockPos();
        if (surfaceSearchAnchor == null || !surfaceSearchAnchor.isWithinDistance(feet, 3)) {
            surfaceSearchAnchor = feet;
            surfaceSearchFailures = 0;
            return false;
        }
        if (++surfaceSearchFailures < 2 || explorationExits >= MAX_EXPLORATION_EXITS
                || lastExplorationExit != null && lastExplorationExit.isWithinDistance(feet, 4)) return false;
        stopPath();
        lastExplorationExit = feet;
        if (!subsurface.escapeExploration(client, age, excluded)) return false;
        explorationExits++;
        surfaceSearchFailures = 0;
        status = "Opening a local exit to continue gathering " + job.resource;
        BotEvents.add("info", "mining", status + " at " + feet.toShortString());
        return true;
    }

    private static Integer prospectingAltitude(MinecraftClient client) {
        if (!projectMining || !client.world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) return null;
        Integer depth = SubsurfaceAccess.prospectingAltitude(job.resource);
        return depth != null && client.world.getBottomY() <= depth && client.world.getTopY() > depth ? depth : null;
    }

    private static boolean tryNaturalDescent(MinecraftClient client, int depth) {
        var position = client.player.getBlockPos();
        int y = position.getY();
        if (y - depth < 12 || age < nextCaveProbe || y > lastCaveProbeY - 12) return false;
        lastCaveProbeY = y;
        nextCaveProbe = age + 600;
        stopPath();
        try {
            caveSettings = new BaritoneSettings(false, true);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
        int lower = y - 12;
        if (!BaritoneClient.goToAltitude(lower)) {
            caveSettings.close();
            caveSettings = null;
            return false;
        }
        deepExplorationGoal = position.withY(lower);
        pathActive = true;
        nextPath = age + 400;
        status = "Following a cave toward Y " + lower;
        return true;
    }
    private static boolean widenSearch(MinecraftClient client) {
        if (!MiningViewDistance.begin(client, age)) return false;
        if (pathActive) stopPath();
        nextSearch = age + 1;
        status = "Checking more loaded chunks for " + job.resource;
        return true;
    }

    private static void finishWideSearch(MinecraftClient client) {
        MiningViewDistance.finish(client);
        wideChunks = Collections.emptyIterator();
    }

    private static boolean returnToResourceSite(MinecraftClient client) {
        if (rememberedSite == null) return false;
        var point = Vec3d.ofBottomCenter(rememberedSite);
        if (age < rememberedUntil && client.player.squaredDistanceTo(point) > 25) {
            var feet = client.player.getBlockPos();
            var region = new BlockPos(feet.getX() >> 2, feet.getY() >> 2, feet.getZ() >> 2);
            if (rememberedRouteRegions.add(region)) rememberedProgressAt = age;
            boolean pursuing = pathActive && BaritoneClient.pursuing(rememberedSite);
            int timeout = pursuing ? RECORDED_SITE_STALL_TICKS : RECORDED_SITE_NO_PATH_TICKS;
            if (age - rememberedProgressAt >= timeout) {
                BotEvents.add("info", "mining", "Leaving a stalled route to recorded gathering site "
                        + rememberedSite.toShortString());
                rememberedSite = null;
            } else if (age >= nextPath && age >= nextRememberedPath && !pursuing) {
                nextRememberedPath = age + RECORDED_SITE_RETRY_TICKS;
                if (pathActive && trySurfaceLedge(client, rememberedSite)) return true;
                if (path(rememberedSite)) {
                    nextPath = age + RECORDED_SITE_RETRY_TICKS;
                    BotEvents.add("debug", "mining", "Sent route to recorded gathering site " + rememberedSite.toShortString());
                } else {
                    rememberedSite = null;
                }
            }
            if (rememberedSite != null) {
                status = "Returning to gathering site " + rememberedSite.toShortString();
                return true;
            }
        }
        rememberedSite = null;
        origin = client.player.getPos();
        nextPath = age;
        stopPath();
        return false;
    }

    private static boolean recoverRoute(MinecraftClient client, BlockPos goal) {
        var recovery = routeRecovery.tick(client, goal, pos -> excluded == null
                || (!excluded.contains(pos) && !excluded.contains(pos.up())));
        if (recovery == StallRecovery.State.DETOUR) {
            status = "Clearing a stalled mining route";
            return true;
        }
        if (recovery == StallRecovery.State.REPLAN) {
            if (trySurfaceLedge(client, goal)) return true;
            nextPath = age;
            if (rememberedSite != null && rememberedSite.equals(goal)) {
                BaritoneClient.execute("stop");
                pathActive = false;
            }
        }
        return false;
    }

    private static boolean trySurfaceLedge(MinecraftClient client, BlockPos goal) {
        var feet = client.player.getBlockPos();
        if (!surfaceResource || localOnly || goal == null || goal.getY() >= feet.getY()
                || surfaceLedgeEnds != 0 || surfaceLedgeOrigin != null && surfaceLedgeOrigin.isWithinDistance(feet, 3)
                || !client.player.isOnGround() || client.player.getHealth() < 16 || !client.world.isSkyVisible(feet)) return false;
        stopPath();
        if (!settings.surfaceLedgeDrop(true)) return false;
        surfaceLedgeOrigin = feet;
        surfaceLedgeEnds = age + SURFACE_LEDGE_TICKS;
        if (!path(goal)) {
            stopPath();
            return false;
        }
        // Baritone still checks the landing and keeps breaking and placing disabled.
        nextPath = age + SURFACE_LEDGE_TICKS;
        status = "Trying a short descent toward " + job.resource;
        BotEvents.add("info", "movement", status + " from " + feet.toShortString());
        return true;
    }

    private static void stopPath() {
        routeRecovery.reset();
        subsurface.stop();
        deepExplorationGoal = null;
        surfaceExplorationGoal = null;
        if (pathActive) {
            BaritoneClient.execute("stop");
            pathActive = false;
        }
        if (caveSettings != null) {
            caveSettings.close();
            caveSettings = null;
        }
        if (surfaceLedgeEnds != 0) {
            if (settings != null) settings.surfaceLedgeDrop(false);
            surfaceLedgeEnds = 0;
        }
    }
    private static boolean path(BlockPos target) {
        boolean accepted = BaritoneClient.goTo(target);
        pathActive |= accepted;
        return accepted;
    }

    private static boolean path(String command){boolean accepted=BaritoneClient.execute(command);pathActive|=accepted;return accepted;}
    private static void pickupPath(){
        if(pickup==null)return;
        var p=BlockPos.ofFloored(pickup);
        path(p);
    }
    private static boolean matches(MinecraftClient c, BlockPos p) {
        return matches(c.world.getBlockState(p));
    }

    private static boolean matches(BlockState state) {
        if (state.getBlock() instanceof net.minecraft.block.TallPlantBlock
                && state.get(net.minecraft.block.TallPlantBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER) return false;
        return blocks.contains(state.getBlock());
    }

    private static boolean protectedTarget(MinecraftClient client, BlockPos pos) {
        if (BuildProtection.blocked(pos)) return true;
        var upper = pos.up();
        return client.world.getBlockState(pos).getBlock() instanceof net.minecraft.block.TallPlantBlock
                && (BuildProtection.blocked(upper) || excluded != null && excluded.contains(upper));
    }

    private static boolean supportsAnotherPlayer(MinecraftClient client, BlockPos pos) {
        for (var player : client.world.getPlayers()) {
            if (player != client.player && player.isAlive() && !player.isSpectator()
                    && player.getBlockPos().down().equals(pos)) return true;
        }
        return false;
    }

    private static BlockHitResult miningHit(MinecraftClient client, BlockPos target, Vec3d eye, double reach) {
        var state = client.world.getBlockState(target);
        if (state.getCollisionShape(client.world, target).isEmpty()) {
            var lower = outlineHit(client, target, eye, reach);
            var upper = target.up();
            return lower != null || !(state.getBlock() instanceof net.minecraft.block.TallPlantBlock)
                    || client.world.getBlockState(upper).getBlock() != state.getBlock()
                    ? lower : outlineHit(client, upper, eye, reach);
        }
        Vec3d center = Vec3d.ofCenter(target);
        var hit = visibleColliderHit(client, target, eye, center, reach);
        if (hit != null) return hit;
        // The center ray can cross an adjacent block even when a side of this block is exposed.
        for (var side : Direction.values()) {
            Vec3d normal = Vec3d.of(side.getVector());
            Vec3d point = center.add(normal.multiply(.49));
            if (eye.subtract(point).dotProduct(normal) <= 0) continue;
            hit = visibleColliderHit(client, target, eye, point, reach);
            if (hit != null) return hit;
        }
        return null;
    }

    private static BlockHitResult visibleColliderHit(MinecraftClient client, BlockPos target, Vec3d eye,
                                                     Vec3d point, double reach) {
        if (eye.squaredDistanceTo(point) > reach * reach) return null;
        var ray = client.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, client.player));
        return ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(target) ? ray : null;
    }

    private static BlockHitResult outlineHit(MinecraftClient client, BlockPos target, Vec3d eye, double reach) {
        for (var box : client.world.getBlockState(target).getOutlineShape(client.world, target).getBoundingBoxes()) {
            Vec3d center = box.getCenter().add(target.getX(), target.getY(), target.getZ());
            if (eye.squaredDistanceTo(center) > reach * reach) continue;
            var ray = client.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, client.player));
            if (ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(target)) return ray;
        }
        return null;
    }
    static boolean hasTool(MinecraftClient client, BlockState state) {
        if (!state.isToolRequired()) return true;
        for (int slot = 0; slot < 36; slot++) {
            if (client.player.getInventory().getStack(slot).isSuitableFor(state)) return true;
        }
        return false;
    }
    static boolean equip(MinecraftClient client, BlockPos pos) {
        var inventory = client.player.getInventory();
        var state = client.world.getBlockState(pos);
        int best = -1;
        float speed = -1;
        for (int offset = 0; offset < 36; offset++) {
            int slot = (inventory.selectedSlot + offset) % 36;
            var stack = inventory.getStack(slot);
            if (state.isToolRequired() && !stack.isSuitableFor(state)) continue;
            float candidate = stack.getMiningSpeedMultiplier(state);
            if (candidate > speed || candidate == speed && !stack.isDamageable() && inventory.getStack(best).isDamageable()) {
                speed = candidate;
                best = slot;
            }
        }
        if (best < 0) return false;
        if (best < 9) inventory.selectedSlot = best;
        else {
            var handler = client.player.playerScreenHandler;
            if (client.player.currentScreenHandler != handler || !handler.getCursorStack().isEmpty()) return false;
            client.interactionManager.clickSlot(handler.syncId, best, inventory.selectedSlot, SlotActionType.SWAP, client.player);
        }
        return true;
    }

    private static boolean nearOreDropHeight(MinecraftClient client, BlockPos ore, BlockPos stand) {
        var world = client.world;
        for (var below = ore.down(); below.getY() >= stand.getY(); below = below.down()) {
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) continue;
            // An unsupported block may drop one or more levels before the item settles.
            // A shelf one block up is usable only when the worker has room to jump.
            var headroom = stand.up(2);
            return below.getY() == stand.getY()
                    && world.getBlockState(headroom).getCollisionShape(world, headroom).isEmpty();
        }
        return true;
    }

    private static Vec3d standing(MinecraftClient client, BlockPos target) {
        var player = client.player;
        var world = client.world;
        var origin = player.getPos();
        var body = player.getBoundingBox();
        boolean preserveTerrain = projectMining && excluded != null
                || world.getBlockState(target).getCollisionShape(world, target).isEmpty();
        double reach = Math.min(4.3, client.interactionManager.getReachDistance() - .1);
        Vec3d best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        boolean ore = projectMining && ResourceCatalog.isOre(job.resource);
        // The eyes can reach upward farther than the player can collect drops from a ledge.
        for (var offset : BlockPos.iterateOutwards(target, 2, 5, 2)) {
            if (offset.getY() > target.getY() + 2 || offset.equals(target) || offset.up().equals(target)
                    || offset.down().equals(target)
                    || rejectedStands.containsKey(offset)
                    || ore && !nearOreDropHeight(client, target, offset)
                    || !world.isChunkLoaded(offset.getX() >> 4, offset.getZ() >> 4)) continue;
            var below = offset.down();
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()
                    || !world.getFluidState(offset).isEmpty() || !world.getFluidState(offset.up()).isEmpty()
                    || world.getBlockState(offset).getHardness(world, offset) < 0
                    || world.getBlockState(offset.up()).getHardness(world, offset.up()) < 0) continue;
            var point = Vec3d.ofBottomCenter(offset);
            double score = origin.squaredDistanceTo(point) + Math.max(0, point.y - origin.y) * 6;
            if (score >= bestScore) continue;
            var box = body.offset(point.subtract(origin));
            if (preserveTerrain && (!world.isSpaceEmpty(player, box)
                    || miningHit(client, target, point.add(0, 1.62, 0), reach) == null)) continue;
            var space = box.expand(.35, 0, .35);
            if (!world.getOtherEntities(player, space, entity -> entity instanceof PlayerEntity && entity.isAlive()).isEmpty()) continue;
            if (!world.isSpaceEmpty(player, space)) score += 16;
            if (score < bestScore) {
                bestScore = score;
                best = point;
            }
        }
        return best;
    }

    private static boolean shouldOpenLocalApproach(BlockPos feet, BlockPos stand, boolean prospecting) {
        if (!BaritoneClient.pursuing(stand)) return true;
        var route = BaritoneClient.route();
        return route.isEmpty() || prospecting && route.size() > feet.getManhattanDistance(stand) * 3 + 8;
    }

    private static boolean tryAlternateStand(MinecraftClient client, BlockPos previous) {
        if (requesting) return true;
        if (alternateStandAttempts >= MAX_ALTERNATE_STANDS) return false;
        rejectedStands.put(previous, age + 600);
        var alternative = standing(client, target);
        if (alternative == null) return false;
        stopPath();
        alternateStandAttempts++;
        status = "Reserving another stand for " + job.resource;
        request(client, target, alternative, "claim");
        return true;
    }

    private static void request(MinecraftClient client,BlockPos ore,Vec3d place,String action){
        request(client, ore, place, action, null);
    }

    private static void request(MinecraftClient client, BlockPos ore, Vec3d place, String action, ItemEntity drop) {
        if(job==null||place==null)return;requesting=true;
        boolean switchingStand = action.equals("claim") && ore.equals(target);
        Job current=job;String token=action.equals("renew")&&leaseToken!=null?leaseToken:owner+":"+(++claimSerial);int epoch=generation;
        var body=new MiningClaims.Request(action,token,world,ore.getX(),ore.getY(),ore.getZ(),place.x,place.y,place.z);
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+current.broker+"/mining-claim")).timeout(Duration.ofMillis(800))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(),HttpResponse.BodyHandlers.ofString()).whenComplete((response,error)->client.execute(()->{
            if(epoch!=generation||job==null){release(current,token);return;}
            requesting=false;
            if(error!=null||response.statusCode()!=200){status="Mining coordinator unavailable";nextSearch=age+40;leaseUntil=0;stopPath();if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();return;}
            try{var reply=JSON.fromJson(response.body(),MiningClaims.Reply.class);
                if (!reply.granted() && switchingStand && "Mining stand is reserved".equals(reply.reason())) {
                    rejectedStands.put(BlockPos.ofFloored(place), age + 600);
                    return;
                }
                if(!reply.granted()){status=reply.reason();rejected.put(ore,age+160);if(target!=null)stopPath();clearReservation();client.interactionManager.cancelBlockBreaking();nextSearch=age+1;return;}
                if (!action.equals("renew")) {
                    if (switchingStand) {
                        BotEvents.add("info", "mining", "Reserved another stand at " + BlockPos.ofFloored(place).toShortString()
                                + " for " + ore.toShortString());
                    } else {
                        if (drop == null && MiningViewDistance.active()
                                && !ore.isWithinDistance(client.player.getBlockPos(), NEARBY_SITE_RADIUS)) {
                            BotEvents.add("info", "mining", "Wider search reserved " + job.resource + " at "
                                    + ore.toShortString() + " from " + client.player.getBlockPos().toShortString());
                        }
                        alternateStandAttempts = 0;
                        localApproachSteps = 0;
                        openedCover.clear();
                    }
                    approachProgress = age;
                    localApproachTried = false;
                    localApproachFrom = null;
                    approachDistance = client.player.getPos().distanceTo(place);
                    nextPath = age;
                }
                if (drop != null) {
                    stopPath();
                    reservedDrop = drop;
                    dropDeadline = age + 140;
                    BotEvents.add("info", "mining", "Reserved dropped " + projectDrop + " at " + ore.toShortString());
                }
                target=ore;stand=place;leaseToken=token;leaseUntil=System.currentTimeMillis()+Math.min(10000,reply.leaseMillis());nextRenew=age+40;
            }catch(RuntimeException e){status="Invalid mining coordinator reply";}
        }));
    }
    private static void clearReservation() {
        generation++;
        requesting = false;
        release(job, leaseToken);
        leaseToken = null;
        leaseUntil = 0;
        reservedDrop = null;
        target = null;
        stand = null;
    }

    private static void release(Job current,String token){
        if(current==null||token==null)return;
        var request=new MiningClaims.Request("release",token,world==null?"":world,0,0,0,0,0,0);
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+current.broker+"/mining-claim")).timeout(Duration.ofMillis(800)).POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(request))).build(),HttpResponse.BodyHandlers.discarding());
    }
}
