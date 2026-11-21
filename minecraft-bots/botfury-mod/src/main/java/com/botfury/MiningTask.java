package com.botfury;

import com.google.gson.Gson;
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
    static void pause(boolean value) {
        if (job == null || paused == value) return;
        paused = value;
        if (value) {
            stopPath();
            clearReservation();
            pickup = null;
            status = "Mining paused";
        } else nextSearch = 0;
    }
    private static BlockPos target;
    private static Vec3d stand,origin,pickup;
    private static int pickupUntil;
    private static BlockPos rememberedSite;
    private static int rememberedUntil;
    private static ProjectQueue.ResourceSite observedSite;

    static ProjectQueue.ResourceSite observedSite() { return observedSite; }
    private static BaritoneSettings settings;
    private static final StallRecovery routeRecovery = new StallRecovery();
    private static final Map<BlockPos,Integer> rejected=new HashMap<>();
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

    static void startProject(MinecraftClient client, Job next, ProjectGather.Area exclude, ProjectQueue.ResourceSite site) {
        stop();
        startMining(client, next, true, exclude);
        if (job == null || site == null) return;
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
        try{settings=new BaritoneSettings(exclude == null);}catch(ReflectiveOperationException|LinkageError e){fail("Could not configure mining approach");return;}
        projectMining=project;
        rememberedSite = null;
        observedSite = null;
        excluded=exclude;
        job=next;blocks=Set.copyOf(ids);generation++;owner=UUID.randomUUID()+"-"+client.player.getGameProfile().getName();
        var here=LocalFormation.here();world=here.server()+"|"+here.dimension();
        paused=false;
        age=nextSearch=nextPath=nextRenew=exploration=0;requesting=dead=false;target=null;stand=null;pickup=null;leaseToken=null;origin=client.player.getPos();rejected.clear();
        status="Searching: "+next.resource+" ("+next.variant+")";BotEvents.add("info","mining",status);
    }
    private static void fail(String reason){status=reason;BotEvents.add("warn","mining",reason);}
    static void stop(){
        if(job!=null){stopPath();var client=MinecraftClient.getInstance();if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();release(job,leaseToken);}
        job=null;generation++;target=null;stand=null;pickup=null;leaseToken=null;requesting=false;
        if(settings!=null){settings.close();settings=null;}
        if(!status.isEmpty())status="Mining stopped";
    }
    static void onDeath(){if(job!=null&&!dead){dead=true;stopPath();clearReservation();pickup=null;status="Mining waits for respawn";}}
    static void tick(MinecraftClient client){
        if(job==null||paused)return;
        var here=LocalFormation.here();
        if(client.player==null||client.world==null||here==null||!world.equals(here.server()+"|"+here.dimension())){stop();return;}
        age++;dead=false;
        if(target!=null){
            if(!matches(client,target)){
                pickup=Vec3d.ofBottomCenter(target);pickupUntil=age+60;
                clearReservation();nextSearch=age+12;
                pickupPath();return;
            }
            if(age>=nextRenew&&!requesting){request(client,target,stand,"renew");nextRenew=age+40;}
            if(System.currentTimeMillis()>=leaseUntil){stopPath();client.interactionManager.cancelBlockBreaking();status="Waiting for mining reservation";return;}
            double reach=Math.min(4.3,client.interactionManager.getReachDistance()-.1);
            Vec3d eye=client.player.getEyePos(),center=Vec3d.ofCenter(target);
            var ray=client.world.raycast(new RaycastContext(eye,center,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,client.player));
            if(client.player.squaredDistanceTo(stand)<=1.44&&eye.squaredDistanceTo(center)<=reach*reach&&ray.getType()==HitResult.Type.BLOCK&&((BlockHitResult)ray).getBlockPos().equals(target)){
                stopPath();
                if(!equip(client,target)){status="Need a suitable tool";return;}
                if (projectMining) {
                    var feet = client.player.getBlockPos();
                    if (excluded == null || !excluded.contains(feet)) {
                        observedSite = new ProjectQueue.ResourceSite(feet.getX(), feet.getY(), feet.getZ());
                    }
                }
                client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,center);
                String blockName=Registries.BLOCK.getId(client.world.getBlockState(target).getBlock()).getPath();
                client.interactionManager.updateBlockBreakingProgress(target,((BlockHitResult)ray).getSide());client.player.swingHand(Hand.MAIN_HAND);
                status="Mining "+blockName;
            } else {
                var p = BlockPos.ofFloored(stand);
                if (recoverRoute(client, p)) return;
                if (age >= nextPath) {
                    nextPath = age + 20;
                    if (!path("goto " + p.getX() + " " + p.getY() + " " + p.getZ())) {
                        status = "Baritone could not approach reserved ore";
                        rejected.put(target, age + 200);
                        clearReservation();
                    } else status = "Approaching reserved " + job.resource;
                }
            }
            return;
        }
        if(pickup!=null){
            if(age>=pickupUntil||(age>=nextSearch&&client.player.squaredDistanceTo(pickup)<1)){
                pickup=null;stopPath();nextSearch=age+1;
            }else if(age%20==0)pickupPath();
            status="Gathering mined drops";return;
        }
        if (pathActive && recoverRoute(client, rememberedSite)) return;
        if(requesting||age<nextSearch)return;
        nextSearch=age+20;
        var position=client.player.getBlockPos();
        var candidates=new ArrayList<BlockPos>();boolean needsTool=false;
        for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)for(int y=-10;y<=10;y++){
            BlockPos p=position.add(x,y,z);
            if (excluded != null && excluded.contains(p)) continue;
            if(rejected.getOrDefault(p,0)>age||!client.world.isChunkLoaded(p.getX()>>4,p.getZ()>>4)||!matches(client,p))continue;
            if(!hasTool(client,p)){needsTool=true;continue;}candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble(p->p.getSquaredDistance(position)));
        for(BlockPos ore:candidates){Vec3d place=standing(client,ore);if(place!=null){request(client,ore,place,"claim");return;}rejected.put(ore,age+100);}
        if(needsTool){status="Need a suitable tool for "+job.resource;stopPath();return;}
        if (returnToResourceSite(client)) return;
        status=candidates.isEmpty()?"Exploring for "+job.resource:"Nearby mining positions are occupied";
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
                path("goto " + x + " -54 " + z);
            } else path("explore " + x + " " + z);
        }
    }
    private static boolean returnToResourceSite(MinecraftClient client) {
        if (rememberedSite == null) return false;
        var point = Vec3d.ofBottomCenter(rememberedSite);
        if (age < rememberedUntil && client.player.squaredDistanceTo(point) > 25) {
            if (age < nextPath || path("goto " + rememberedSite.getX() + " " + rememberedSite.getY() + " " + rememberedSite.getZ())) {
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
    private static boolean path(String command){boolean accepted=BaritoneClient.execute(command);pathActive|=accepted;return accepted;}
    private static void pickupPath(){
        if(pickup==null)return;
        var p=BlockPos.ofFloored(pickup);
        path("goto "+p.getX()+" "+p.getY()+" "+p.getZ());
    }
    private static boolean matches(MinecraftClient c,BlockPos p){return blocks.contains(Registries.BLOCK.getId(c.world.getBlockState(p).getBlock()).toString());}
    private static boolean hasTool(MinecraftClient c,BlockPos p){var state=c.world.getBlockState(p);if(!state.isToolRequired())return true;for(int i=0;i<36;i++)if(c.player.getInventory().getStack(i).isSuitableFor(state))return true;return false;}
    private static boolean equip(MinecraftClient c,BlockPos p){
        var state=c.world.getBlockState(p);int best=-1;float speed=-1;
        for(int i=0;i<36;i++){var stack=c.player.getInventory().getStack(i);if(state.isToolRequired()&&!stack.isSuitableFor(state))continue;float s=stack.getMiningSpeedMultiplier(state);if(s>speed){speed=s;best=i;}}
        if(best<0)return false;
        if(best<9)c.player.getInventory().selectedSlot=best;
        else if(c.player.currentScreenHandler==c.player.playerScreenHandler)c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId,best,c.player.getInventory().selectedSlot,SlotActionType.SWAP,c.player);
        return true;
    }
    private static Vec3d standing(MinecraftClient c,BlockPos ore){
        Vec3d best=null;double score=Double.POSITIVE_INFINITY;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++){
            if(dx==0&&dz==0)continue;BlockPos feet=ore.add(dx,dy,dz),below=feet.down();
            if(!c.world.isChunkLoaded(feet.getX()>>4,feet.getZ()>>4)||c.world.getBlockState(below).getCollisionShape(c.world,below).isEmpty()
                    ||!c.world.getFluidState(feet).isEmpty()||!c.world.getFluidState(feet.up()).isEmpty()
                    ||c.world.getBlockState(feet).getHardness(c.world,feet)<0||c.world.getBlockState(feet.up()).getHardness(c.world,feet.up())<0)continue;
            Vec3d point=Vec3d.ofBottomCenter(feet);var box=c.player.getBoundingBox().offset(point.subtract(c.player.getPos())).expand(.35,0,.35);
            if(!c.world.getOtherEntities(c.player,box,e->e instanceof PlayerEntity&&e.isAlive()).isEmpty())continue;
            double distance=c.player.squaredDistanceTo(point)+(c.world.isSpaceEmpty(c.player,box)?0:16);
            if(distance<score){score=distance;best=point;}
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
                target=ore;stand=place;leaseToken=token;leaseUntil=System.currentTimeMillis()+Math.min(10000,reply.leaseMillis());nextRenew=age+40;nextPath=age;
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
