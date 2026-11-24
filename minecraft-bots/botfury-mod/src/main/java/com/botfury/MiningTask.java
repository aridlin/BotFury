package com.botfury;

import com.google.gson.Gson;
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
import net.minecraft.world.RaycastContext;
import net.minecraft.world.Heightmap;
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
    private static final int MAX_PROTECTED_APPROACH_STEPS = 16;
    private static final int MAX_EXPLORATION_EXITS = 3;
    private static final int SURFACE_ROUTE_DEPTH = 12;
    private static final Gson JSON=new Gson();
    private static final ExecutorService IO=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"BotFury-mining");t.setDaemon(true);return t;});
    private static final HttpClient HTTP=HttpClient.newBuilder().executor(IO).connectTimeout(Duration.ofMillis(300)).build();
    private static Job job;
    private static ProjectGather.Area excluded;
    private static Set<String> blocks=Set.of();
    private static String owner,world,leaseToken;
    private static int claimSerial;
    private static int generation,age,nextSearch,nextPath,nextRenew,exploration;
    private static long leaseUntil;
    private static boolean requesting,dead,paused,pathActive,projectMining;
    private static final Deque<BlockPos> pendingCandidates = new ArrayDeque<>();
    private static boolean scanNeedsTool;
    private static boolean toolShortfall;
    private static boolean routeToolShortfall;
    static void pause(boolean value) {
        if (job == null || paused == value) return;
        paused = value;
        pendingCandidates.clear();
        if (value) {
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
    private static ProjectItem projectDrop;
    private static ItemEntity reservedDrop;
    private static int nextDropScan, dropDeadline;
    private static final int LOOSE_DROP_RADIUS = 12;
    private static final DroppedItemSearch minedDrops = new DroppedItemSearch();
    private static BlockPos rememberedSite;
    private static BlockPos deepExplorationGoal;
    private static BlockPos surfaceSearchAnchor, lastExplorationExit;
    private static BlockPos surfaceRouteGoal;
    private static boolean surfaceResource;
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
    private static final Map<BlockPos,Integer> rejected=new HashMap<>();
    private static final Map<BlockPos,Integer> rejectedStands = new HashMap<>();
    private static int approachProgress;
    private static double approachDistance;
    private static boolean localApproachTried;
    private static BlockPos localApproachFrom;
    private static int localApproachSteps;
    private static volatile String status="";
    static boolean active(){return job!=null;}
    static boolean working(){return job!=null&&!paused&&!dead;}
    static boolean seekingDeepOre(MinecraftClient client) {
        return working() && projectMining && target == null && pickup == null
                && client.player.getBlockY() <= 8 && SubsurfaceAccess.deepResource(job.resource);
    }
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
                             ProjectItem drop) {
        stop();
        startMining(client, next, true, exclude);
        if (job == null) return;
        projectDrop = drop;
        if (site == null) return;
        var location = new BlockPos(site.x(), site.y(), site.z());
        Integer prospectingY = prospectingAltitude(client);
        int workerY = client.player.getBlockY();
        if (prospectingY != null && workerY <= location.getY() - 24
                && Math.abs(workerY - prospectingY) < Math.abs(location.getY() - prospectingY)) return;
        if (client.world.isInBuildLimit(location) && client.world.getWorldBorder().contains(location)
                && (exclude == null || !exclude.contains(location))) {
            rememberedSite = location;
            lastProspectingCheckpointY = Math.min(lastProspectingCheckpointY, location.getY());
            double distance = client.player.getPos().distanceTo(Vec3d.ofBottomCenter(location));
            rememberedUntil = Math.min(2400, Math.max(600, (int) (distance * 12)));
        }
    }

    private static void startMining(MinecraftClient client, Job next, boolean project, ProjectGather.Area exclude) {
        if(client.player==null||client.world==null||LocalFormation.here()==null){fail("Not in game");return;}
        if(!BaritoneClient.available()){fail("Install Baritone for mining");return;}
        var ids=ResourceCatalog.blocks(next.resource,next.variant);
        for(String id:ids)if(!Registries.BLOCK.containsId(new Identifier(id))){fail("Unknown mining block: "+id);return;}
        for(String id:ids){var state=Registries.BLOCK.get(new Identifier(id)).getDefaultState();if(state.isAir()||state.getHardness(client.world,client.player.getBlockPos())<0){fail("Choose a breakable mining block");return;}}
        try{settings=new BaritoneSettings(exclude == null, project);}catch(ReflectiveOperationException|LinkageError e){fail("Could not configure mining approach");return;}
        projectMining=project;
        surfaceResource = project && ids.stream().map(id -> Registries.BLOCK.get(new Identifier(id)))
                .allMatch(block -> block.getDefaultState().isIn(BlockTags.LOGS)
                        || block instanceof FlowerBlock || block instanceof TallFlowerBlock);
        surfaceRouteGoal = null;
        toolShortfall = false;
        routeToolShortfall = false;
        subsurface.reset();
        lastCaveProbeY = Integer.MAX_VALUE;
        nextCaveProbe = 0;
        lastProspectingCheckpointY = client.player.getBlockY();
        rememberedSite = null;
        observedSite = null;
        surfaceSearchAnchor = lastExplorationExit = null;
        surfaceSearchFailures = explorationExits = 0;
        excluded=exclude;
        job=next;blocks=Set.copyOf(ids);generation++;owner=UUID.randomUUID()+"-"+client.player.getGameProfile().getName();
        var here=LocalFormation.here();world=here.server()+"|"+here.dimension();
        paused=false;
        nextDropScan = 0;
        age=nextSearch=nextPath=nextRenew=exploration=0;requesting=dead=false;target=null;stand=null;pickup=null;leaseToken=null;origin=client.player.getPos();rejected.clear();
        rejectedStands.clear();
        pendingCandidates.clear();
        status="Searching: "+next.resource+" ("+next.variant+")";BotEvents.add("info","mining",status);
    }
    private static void fail(String reason){status=reason;BotEvents.add("warn","mining",reason);}
    static void stop(){
        if(job!=null){stopPath();var client=MinecraftClient.getInstance();if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();release(job,leaseToken);}
        minedDrops.reset();
        reservedDrop = null;
        projectDrop = null;
        pendingCandidates.clear();
        job=null;generation++;target=null;stand=null;pickup=null;leaseToken=null;requesting=false;
        if(settings!=null){settings.close();settings=null;}
        if(!status.isEmpty())status="Mining stopped";
    }
    static void onDeath(){if(job!=null&&!dead){dead=true;stopPath();clearReservation();minedDrops.reset();pendingCandidates.clear();pickup=null;status="Mining waits for respawn";}}
    static void tick(MinecraftClient client){
        if(job==null||paused)return;
        var here=LocalFormation.here();
        if(client.player==null||client.world==null||here==null||!world.equals(here.server()+"|"+here.dimension())){stop();return;}
        age++;dead=false;
        if (reservedDrop != null) {
            collectReservedDrop(client);
            return;
        }
        if(target!=null){
            if (protectedTarget(client, target)) {
                stopPath();
                clearReservation();
                client.interactionManager.cancelBlockBreaking();
                nextSearch=age+1;
                return;
            }
            if(!matches(client,target)){
                pickup=Vec3d.ofBottomCenter(target);pickupUntil=age+(projectDrop==null?60:100);
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
                    && !feet.down().equals(target)) {
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
            } else {
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
                if (projectMining && !localApproachTried && localApproachSteps < MAX_PROTECTED_APPROACH_STEPS
                        && age - approachProgress >= 80
                        && SubsurfaceAccess.canDigRoute(client)
                        && SubsurfaceAccess.localApproachInRange(feet, p, prospectingAltitude(client) != null)
                        && (!BaritoneClient.pursuing(p) || BaritoneClient.route().isEmpty())) {
                    stopPath();
                    localApproachTried = true;
                    if (subsurface.openLocalApproach(client, age, p, excluded, prospectingAltitude(client) != null)) {
                        localApproachFrom = feet;
                        localApproachSteps++;
                        status = "Opening a protected approach to reserved " + job.resource;
                        BotEvents.add("info", "mining", status);
                        return;
                    }
                }
                if (projectMining && age - approachProgress >= 110
                        && (age - approachProgress >= 250 || !BaritoneClient.pursuing(p))
                        && tryAlternateStand(client, p)) return;
                if (age - approachProgress >= 250) {
                    rejectedStands.put(p, age + 600);
                    boolean prospectAgain = prospectingAltitude(client) != null;
                    rejected.put(target, age + 1200);
                    stopPath();
                    clearReservation();
                    client.interactionManager.cancelBlockBreaking();
                    status = "Trying another approach to " + job.resource;
                    nextSearch = age + 1;
                    if (prospectAgain) {
                        // A different stand for the same vein can still be on the other side of the wall.
                        rememberedSite = null;
                        pendingCandidates.clear();
                        nextSearch = age + 20;
                        explore(client);
                    }
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
                var state = minedDrops.tick(client, projectDrop::matches, pickup);
                if (age >= pickupUntil || state == DroppedItemSearch.State.UNAVAILABLE
                        || state == DroppedItemSearch.State.SEARCHING && age >= nextSearch) {
                    minedDrops.reset();
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
        if (requestLooseDrop(client)) return;
        if (subsurface.continuePassage(client, age, excluded)) {
            status = "Opening a short prospecting passage";
            return;
        }
        if (subsurface.continueExplorationExit(client, age)) {
            status = "Opening a local exit to continue gathering " + job.resource;
            return;
        }
        if (pathActive && recoverRoute(client, rememberedSite)) return;
        if (requesting) return;
        if (pendingCandidates.isEmpty()) {
            if (age < nextSearch) return;
            scan(client);
        }
        for (int checked = 0; checked < STANDS_PER_TICK && !pendingCandidates.isEmpty(); checked++) {
            var candidate = pendingCandidates.removeFirst();
            if (!client.world.isChunkLoaded(candidate.getX() >> 4, candidate.getZ() >> 4)
                    || !matches(client, candidate) || protectedTarget(client, candidate)) continue;
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
        explore(client);
    }

    private static boolean requestLooseDrop(MinecraftClient client) {
        if (requesting || projectDrop == null || age < nextDropScan) return false;
        nextDropScan = age + 10;
        var player = client.player;
        var loose = client.world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(LOOSE_DROP_RADIUS),
                item -> item.isOnGround() && player.squaredDistanceTo(item) <= LOOSE_DROP_RADIUS * LOOSE_DROP_RADIUS
                        && rejected.getOrDefault(item.getBlockPos(), 0) <= age && availableDrop(client, item))
                .stream().min(Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
        if (loose == null) return false;
        var cell = loose.getBlockPos();
        request(client, cell, Vec3d.ofBottomCenter(cell), "claim", loose);
        return true;
    }

    private static boolean availableDrop(MinecraftClient client, ItemEntity item) {
        if (!item.isAlive() || !projectDrop.matches(item.getStack())) return false;
        var cell = item.getBlockPos();
        if (BuildProtection.blocked(cell) || excluded != null && excluded.contains(cell)) return false;
        // Leave nearby players room to collect their own harvest or receive a delivery.
        for (var peer : client.world.getPlayers()) {
            if (peer != client.player && peer.isAlive() && !peer.isSpectator() && peer.squaredDistanceTo(item) <= 9) return false;
        }
        var inventory = client.player.getInventory();
        return inventory.getEmptySlot() >= 0 || inventory.getOccupiedSlotWithRoomForStack(item.getStack()) >= 0;
    }

    private static void collectReservedDrop(MinecraftClient client) {
        if (reservedDrop.getBlockPos().equals(target) && availableDrop(client, reservedDrop)
                && age < dropDeadline && System.currentTimeMillis() < leaseUntil
                && minedDrops.approachReserved(client, reservedDrop) == DroppedItemSearch.State.APPROACHING) {
            status = "Collecting dropped " + projectDrop;
            return;
        }
        if (reservedDrop.isAlive()) rejected.put(target, age + 160);
        minedDrops.reset();
        clearReservation();
        nextSearch = age + 1;
    }

    private static void scan(MinecraftClient client) {
        rejectedStands.values().removeIf(until -> until <= age);
        rejected.values().removeIf(until -> until <= age);
        var position = client.player.getBlockPos();
        var world = client.world;
        boolean preserveTerrain = projectMining && excluded != null;
        var suitability = new HashMap<BlockState, Boolean>();
        var candidates = new ArrayList<BlockPos>();
        var nearbyHits = new ArrayList<BlockPos>();
        boolean safeFooting = canMineFromCurrentFooting(client, position);
        scanNeedsTool = false;
        for (var pos : BlockPos.iterateOutwards(position, 12, 10, 12)) {
            if (excluded != null && excluded.contains(pos) || rejected.containsKey(pos)
                    || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || !matches(client, pos) || protectedTarget(client, pos)) continue;
            var state = world.getBlockState(pos);
            if (preserveTerrain && state.isFullCube(world, pos) && completelyCovered(world, pos)) continue;
            if (!suitability.computeIfAbsent(state, block -> hasTool(client, block))) {
                scanNeedsTool = true;
                continue;
            }
            if (safeFooting && canReachFromHere(client, position, pos)) nearbyHits.add(pos.toImmutable());
            else candidates.add(pos.toImmutable());
        }
        Comparator<BlockPos> byDistance = Comparator.comparingDouble(p -> p.getSquaredDistance(position));
        nearbyHits.sort(byDistance);
        candidates.sort(byDistance);
        pendingCandidates.addAll(nearbyHits);
        pendingCandidates.addAll(candidates);
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
        if (!rejectedStands.containsKey(feet) && canMineFromCurrentFooting(client, feet)
                && canReachFromHere(client, feet, target)) return Vec3d.ofBottomCenter(feet);
        return standing(client, target);
    }

    private static boolean completelyCovered(net.minecraft.world.World world, BlockPos pos) {
        for (Direction side : Direction.values()) {
            var adjacent = pos.offset(side);
            if (!world.getBlockState(adjacent).isFullCube(world, adjacent)) return false;
        }
        return true;
    }

    private static void explore(MinecraftClient client) {
        if (returnToResourceSite(client)) return;
        if (surfaceResource && seekSurface(client)) return;
        Integer depth = prospectingAltitude(client);
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
            if (tryNaturalDescent(client, depth)) return;
            if (subsurface.advance(client, age, depth, excluded)) {
                status = "Prospecting for " + job.resource + " toward Y " + depth;
                return;
            }
            if (!SubsurfaceAccess.canDigRoute(client)) {
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

    private static boolean seekSurface(MinecraftClient client) {
        if (!client.world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) return false;
        var feet = client.player.getBlockPos();
        if (surfaceRouteGoal == null && client.world.getTopY(Heightmap.Type.MOTION_BLOCKING,
                feet.getX(), feet.getZ()) - feet.getY() >= SURFACE_ROUTE_DEPTH) {
            for (var column : BlockPos.iterateOutwards(feet, 8, 0, 8)) {
                if (!client.world.isChunkLoaded(column.getX() >> 4, column.getZ() >> 4)) continue;
                int top = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, column.getX(), column.getZ());
                var exit = column.withY(top);
                if (top <= feet.getY() || !client.world.isInBuildLimit(exit.up())
                        || !client.world.getWorldBorder().contains(exit)
                        || !client.world.getFluidState(exit.down()).isEmpty()
                        || BuildProtection.blocked(exit) || excluded != null && excluded.contains(exit)) continue;
                if (surfaceRouteGoal == null || top < surfaceRouteGoal.getY()) surfaceRouteGoal = exit;
            }
        }
        if (surfaceRouteGoal == null) return false;
        if (feet.getY() >= surfaceRouteGoal.getY() - 2 || client.world.isSkyVisible(feet)) {
            surfaceRouteGoal = null;
            subsurface.stop();
            return false;
        }
        if (pathActive) stopPath();
        if (!subsurface.tryNaturalAscent(client, age, surfaceRouteGoal.getY(), 3)
                && !subsurface.advanceToward(client, age, surfaceRouteGoal, excluded, true)) return false;
        status = "Climbing toward the surface for " + job.resource;
        return true;
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
    private static boolean returnToResourceSite(MinecraftClient client) {
        if (rememberedSite == null) return false;
        var point = Vec3d.ofBottomCenter(rememberedSite);
        if (age < rememberedUntil && client.player.squaredDistanceTo(point) > 25) {
            if (age >= nextPath && (!pathActive || !BaritoneClient.pursuing(rememberedSite))) {
                if (path(rememberedSite)) {
                    nextPath = age + 100;
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
            nextPath = age;
            if (rememberedSite != null && rememberedSite.equals(goal)) {
                BaritoneClient.execute("stop");
                pathActive = false;
            }
        }
        return false;
    }

    private static void stopPath() {
        routeRecovery.reset();
        subsurface.stop();
        deepExplorationGoal = null;
        if (pathActive) {
            BaritoneClient.execute("stop");
            pathActive = false;
        }
        if (caveSettings != null) {
            caveSettings.close();
            caveSettings = null;
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
        var state = c.world.getBlockState(p);
        if (state.getBlock() instanceof net.minecraft.block.TallPlantBlock
                && state.get(net.minecraft.block.TallPlantBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER) return false;
        return blocks.contains(Registries.BLOCK.getId(state.getBlock()).toString());
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
        // Reach comes from the eyes, so useful poses can be several blocks below the target.
        for (var offset : BlockPos.iterateOutwards(target, 2, 5, 2)) {
            if (offset.getY() > target.getY() + 2 || offset.equals(target) || offset.up().equals(target)
                    || offset.down().equals(target)
                    || rejectedStands.containsKey(offset)
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
                        alternateStandAttempts = 0;
                        localApproachSteps = 0;
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
