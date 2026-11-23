package com.botfury;

import com.google.gson.Gson;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
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
    private static int pickupUntil;
    private static ProjectItem projectDrop;
    private static final DroppedItemSearch minedDrops = new DroppedItemSearch();
    private static BlockPos rememberedSite;
    private static int rememberedUntil;
    private static ProjectQueue.ResourceSite observedSite;

    static ProjectQueue.ResourceSite observedSite() { return observedSite; }
    private static BaritoneSettings settings;
    private static final StallRecovery routeRecovery = new StallRecovery();
    private static final Map<BlockPos,Integer> rejected=new HashMap<>();
    private static final Map<BlockPos,Integer> rejectedStands = new HashMap<>();
    private static int approachProgress;
    private static double approachDistance;
    private static volatile String status="";
    static boolean active(){return job!=null;}
    static boolean working(){return job!=null&&!paused&&!dead;}
    static String status(){return job==null?"":status;}
    static String lastStatus(){return status;}
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
        if (client.world.isInBuildLimit(location) && client.world.getWorldBorder().contains(location)
                && (exclude == null || !exclude.contains(location))) {
            rememberedSite = location;
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
        rememberedSite = null;
        observedSite = null;
        excluded=exclude;
        job=next;blocks=Set.copyOf(ids);generation++;owner=UUID.randomUUID()+"-"+client.player.getGameProfile().getName();
        var here=LocalFormation.here();world=here.server()+"|"+here.dimension();
        paused=false;
        age=nextSearch=nextPath=nextRenew=exploration=0;requesting=dead=false;target=null;stand=null;pickup=null;leaseToken=null;origin=client.player.getPos();rejected.clear();
        rejectedStands.clear();
        pendingCandidates.clear();
        status="Searching: "+next.resource+" ("+next.variant+")";BotEvents.add("info","mining",status);
    }
    private static void fail(String reason){status=reason;BotEvents.add("warn","mining",reason);}
    static void stop(){
        if(job!=null){stopPath();var client=MinecraftClient.getInstance();if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();release(job,leaseToken);}
        minedDrops.reset();
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
            if(client.player.squaredDistanceTo(stand)<=1.44&&ray!=null
                    && !client.player.getBlockPos().down().equals(target)){
                approachProgress = age;
                stopPath();
                if(!equip(client,target)){status="Need a suitable tool";return;}
                if (projectMining) {
                    var feet = client.player.getBlockPos();
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
                }
                if (age - approachProgress >= 250) {
                    rejectedStands.put(p, age + 600);
                    stopPath();
                    clearReservation();
                    client.interactionManager.cancelBlockBreaking();
                    status = "Trying another approach to " + job.resource;
                    nextSearch = age + 1;
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
            var place = supportsAnotherPlayer(client, candidate) ? null : standing(client, candidate);
            if (place != null) {
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
            status = "Need a suitable tool for " + job.resource;
            stopPath();
            return;
        }
        explore(client);
    }

    private static void scan(MinecraftClient client) {
        rejectedStands.values().removeIf(until -> until <= age);
        rejected.values().removeIf(until -> until <= age);
        var position = client.player.getBlockPos();
        var world = client.world;
        boolean preserveTerrain = projectMining && excluded != null;
        var suitability = new HashMap<BlockState, Boolean>();
        var candidates = new ArrayList<BlockPos>();
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
            candidates.add(pos.toImmutable());
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(position)));
        pendingCandidates.addAll(candidates);
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
        status = "Exploring for " + job.resource;
        if(age>=nextPath){
            nextPath = age + (projectMining ? 400 : 200);
            double angle = (client.player.getUuid().hashCode() & 1023) * Math.PI / 512
                    + exploration * (projectMining ? .32 : .8);
            exploration++;
            double radius = projectMining ? Math.min(1024, 32 + 24.0 * exploration) : 24 + 16 * (exploration % 3);
            int x = (int)(origin.x + Math.cos(angle) * radius);
            int z = (int)(origin.z + Math.sin(angle) * radius);
            if (projectMining && job.resource.equals("diamond")
                    && client.world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)
                    && client.world.getBottomY() <= -54) {
                status = "Searching for diamonds around " + x + ", -54, " + z;
                path(new BlockPos(x, -54, z));
            } else path("explore " + x + " " + z);
        }
    }
    private static boolean returnToResourceSite(MinecraftClient client) {
        if (rememberedSite == null) return false;
        var point = Vec3d.ofBottomCenter(rememberedSite);
        if (age < rememberedUntil && client.player.squaredDistanceTo(point) > 25) {
            if (age < nextPath || path(rememberedSite)) {
                if (age >= nextPath) nextPath = age + 100;
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
        if (recovery == StallRecovery.State.REPLAN) nextPath = age;
        return false;
    }

    private static void stopPath() {
        routeRecovery.reset();
        if (pathActive) {
            BaritoneClient.execute("stop");
            pathActive = false;
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
    private static boolean hasTool(MinecraftClient client, BlockState state) {
        if (!state.isToolRequired()) return true;
        for (int slot = 0; slot < 36; slot++) {
            if (client.player.getInventory().getStack(slot).isSuitableFor(state)) return true;
        }
        return false;
    }
    private static boolean equip(MinecraftClient c,BlockPos p){
        var state=c.world.getBlockState(p);int best=-1;float speed=-1;
        for(int i=0;i<36;i++){var stack=c.player.getInventory().getStack(i);if(state.isToolRequired()&&!stack.isSuitableFor(state))continue;float s=stack.getMiningSpeedMultiplier(state);if(s>speed){speed=s;best=i;}}
        if(best<0)return false;
        if(best<9)c.player.getInventory().selectedSlot=best;
        else if(c.player.currentScreenHandler==c.player.playerScreenHandler)c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId,best,c.player.getInventory().selectedSlot,SlotActionType.SWAP,c.player);
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

    private static void request(MinecraftClient client,BlockPos ore,Vec3d place,String action){
        if(job==null||place==null)return;requesting=true;
        Job current=job;String token=action.equals("renew")&&leaseToken!=null?leaseToken:owner+":"+(++claimSerial);int epoch=generation;
        var body=new MiningClaims.Request(action,token,world,ore.getX(),ore.getY(),ore.getZ(),place.x,place.y,place.z);
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+current.broker+"/mining-claim")).timeout(Duration.ofMillis(800))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(),HttpResponse.BodyHandlers.ofString()).whenComplete((response,error)->client.execute(()->{
            if(epoch!=generation||job==null){release(current,token);return;}
            requesting=false;
            if(error!=null||response.statusCode()!=200){status="Mining coordinator unavailable";nextSearch=age+40;leaseUntil=0;stopPath();if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();return;}
            try{var reply=JSON.fromJson(response.body(),MiningClaims.Reply.class);
                if(!reply.granted()){status=reply.reason();rejected.put(ore,age+160);if(target!=null)stopPath();clearReservation();client.interactionManager.cancelBlockBreaking();nextSearch=age+1;return;}
                if (!action.equals("renew")) {
                    approachProgress = age;
                    approachDistance = client.player.getPos().distanceTo(place);
                    nextPath = age;
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
        target = null;
        stand = null;
    }

    private static void release(Job current,String token){
        if(current==null||token==null)return;
        var request=new MiningClaims.Request("release",token,world==null?"":world,0,0,0,0,0,0);
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+current.broker+"/mining-claim")).timeout(Duration.ofMillis(800)).POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(request))).build(),HttpResponse.BodyHandlers.discarding());
    }
}
