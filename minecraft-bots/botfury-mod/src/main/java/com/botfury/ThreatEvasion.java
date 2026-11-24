package com.botfury;

import com.botfury.mixin.ProjectileGroundAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import java.util.*;

final class ThreatEvasion {
    record Action(Vec3d walkingDirection, boolean wardensNearby, BlockPos step) {}
    private record Blast(Vec3d center,double radius) {}
    private record Flight(Vec3d from,Vec3d to,int tick) {}
    private record RetreatStep(BlockPos cell, RetreatStep previous, int distance) {}
    private static final int WARDEN_DETOUR_STEPS = 10;
    private static final Deque<BlockPos> wardenDetour = new ArrayDeque<>();
    private static long nextWardenSearch;
    private record Threats(List<Blast> blasts,List<Flight> flights,List<Vec3d> wardens) {
        boolean wardensNearby() { return !wardens.isEmpty(); }

        double risk(Box body,Vec3d movement){
            double score=0;
            var projected = body.getCenter().add(movement.multiply(5));
            for(Blast blast:blasts){
                double gap=Math.max(0,blast.radius-projected.distanceTo(blast.center));
                score+=gap*gap*25;
            }
            for (var warden : wardens) {
                double horizontalGap = Math.max(0, 18 - Math.hypot(projected.x - warden.x, projected.z - warden.z));
                double verticalGap = Math.max(0, 21 - Math.abs(body.minY - warden.y));
                score += horizontalGap * horizontalGap * (verticalGap / 21) * 12;
            }
            for(Flight flight:flights){
                Box future=body.offset(movement.multiply(flight.tick-.5)).expand(.25);
                double hit=SegmentBox.hitFraction(point(flight.from),point(flight.to),
                        new SegmentBox.Point(future.minX,future.minY,future.minZ),new SegmentBox.Point(future.maxX,future.maxY,future.maxZ));
                if(!Double.isNaN(hit))score+=(9-flight.tick)*30;
            }
            return score;
        }
    }
    private static SegmentBox.Point point(Vec3d p){return new SegmentBox.Point(p.x,p.y,p.z);}
    private static Threats scan(MinecraftClient client){
        var blasts=new ArrayList<Blast>();
        for(var creeper:client.world.getEntitiesByClass(CreeperEntity.class,client.player.getBoundingBox().expand(14),e->e.isAlive())){
            boolean powered=creeper.shouldRenderOverlay();
            boolean fusing=creeper.isIgnited()||creeper.getFuseSpeed()>0||creeper.getClientFuseTime(0)>.05f;
            double radius=powered?10:fusing?6:4.5;
            blasts.add(new Blast(creeper.getPos().add(0,.9,0),radius));
        }
        var flights=new ArrayList<Flight>();
        var arrows=client.world.getEntitiesByClass(PersistentProjectileEntity.class,client.player.getBoundingBox().expand(24),
                e->!e.isRemoved()&&e.getOwner()!=client.player&&!((ProjectileGroundAccess)e).botfury$isInGround()&&e.getVelocity().lengthSquared()>.04)
                .stream().sorted(Comparator.comparingDouble(client.player::squaredDistanceTo)).limit(16).toList();
        for(var arrow:arrows){
            Vec3d from=arrow.getPos(),velocity=arrow.getVelocity();
            double drag=arrow.isTouchingWater()?.6:.99;
            for(int i=1;i<=8;i++){
                Vec3d to=from.add(velocity);
                if(!arrow.isNoClip()){
                    var wall=client.world.raycast(new RaycastContext(from,to,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,arrow));
                    if(wall.getType()==HitResult.Type.BLOCK){flights.add(new Flight(from,wall.getPos(),i));break;}
                }
                flights.add(new Flight(from,to,i));from=to;
                velocity=velocity.multiply(drag).add(0,arrow.hasNoGravity()?0:-.05,0);
            }
        }
        var origin = client.player.getPos();
        var wardens = client.world.getEntitiesByClass(WardenEntity.class, client.player.getBoundingBox().expand(22),
                warden -> warden.isAlive() && Math.abs(warden.getY() - origin.y) < 21
                        && Math.hypot(warden.getX() - origin.x, warden.getZ() - origin.z) < 18)
                .stream().map(WardenEntity::getPos).toList();
        return new Threats(blasts,flights,wardens);
    }
    static Action dodge(MinecraftClient client){
        Threats threats=scan(client);
        if (!threats.wardensNearby()) clearWardenDetour();
        Box body=client.player.getBoundingBox();
        var referenceMovement = threats.wardensNearby() ? Vec3d.ZERO : client.player.getVelocity();
        double current=threats.risk(body,referenceMovement);
        if(current<=0)return null;
        if(!threats.wardensNearby()&&LocalFormation.canEvade()&&teleport(client,threats,body,current))
            return new Action(null, false, null);
        boolean canDetour = threats.wardensNearby() && threats.blasts.isEmpty() && threats.flights.isEmpty();
        var detour = canDetour ? followWardenDetour(client, threats, body, current) : null;
        if (detour != null) return detour;
        var direction = walkDirection(client, threats, body, current);
        if (direction == null && canDetour && client.player.isOnGround() && !client.player.isTouchingWater()
                && client.currentScreen == null && System.nanoTime() >= nextWardenSearch) {
            nextWardenSearch = System.nanoTime() + 1_000_000_000L;
            searchWardenDetour(client, threats, body, current);
            detour = followWardenDetour(client, threats, body, current);
            if (detour != null) return detour;
        }
        if (direction == null && !threats.wardensNearby()) return null;
        return new Action(direction, threats.wardensNearby(), null);
    }

    static void clearWardenDetour() {
        wardenDetour.clear();
        nextWardenSearch = 0;
    }

    private static Action followWardenDetour(MinecraftClient client, Threats threats, Box body, double current) {
        if (!client.player.isOnGround() || client.player.isTouchingWater() || client.currentScreen != null) return null;
        var center = body.getCenter();
        var goal = wardenDetour.peekLast();
        if (goal != null && threats.risk(body.offset(goal.getX() + .5 - center.x, goal.getY() - body.minY,
                goal.getZ() + .5 - center.z), Vec3d.ZERO) >= current) wardenDetour.clear();
        while (!wardenDetour.isEmpty()) {
            var cell = wardenDetour.getFirst();
            var delta = new Vec3d(cell.getX() + .5 - center.x, 0, cell.getZ() + .5 - center.z);
            int height = cell.getY() - client.player.getBlockY();
            if (Math.abs(height) > 1 || delta.lengthSquared() > 3 || !walkableStep(client, body, delta, cell, height)) {
                wardenDetour.clear();
                return null;
            }
            if (height != 0 || delta.lengthSquared() > .04) {
                return new Action(delta.normalize(), true, height == 0 ? null : cell);
            }
            wardenDetour.removeFirst();
        }
        return null;
    }

    private static void searchWardenDetour(MinecraftClient client, Threats threats, Box body, double current) {
        var origin = body.getCenter();
        var queue = new ArrayDeque<RetreatStep>();
        var seen = new HashSet<BlockPos>();
        var initial = client.player.getBlockPos();
        queue.add(new RetreatStep(initial, null, 0));
        seen.add(initial);
        RetreatStep best = null;
        double bestRisk = current;
        while (!queue.isEmpty()) {
            var step = queue.removeFirst();
            var cell = step.cell;
            var from = step.distance == 0 ? origin
                    : new Vec3d(cell.getX() + .5, cell.getY() + origin.y - body.minY, cell.getZ() + .5);
            var at = body.offset(from.subtract(origin));
            double risk = threats.risk(at, Vec3d.ZERO);
            if (risk < bestRisk) {
                bestRisk = risk;
                best = step;
                if (risk == 0) break;
            }
            if (step.distance >= WARDEN_DETOUR_STEPS) continue;
            for (var side : Direction.Type.HORIZONTAL) {
                var adjacent = cell.offset(side);
                if (!client.world.isChunkLoaded(adjacent.getX() >> 4, adjacent.getZ() >> 4)) continue;
                var delta = new Vec3d(adjacent.getX() + .5 - from.x, 0, adjacent.getZ() + .5 - from.z);
                for (int height = -1; height <= 1; height++) {
                    var next = adjacent.up(height);
                    if (seen.contains(next) || !walkableStep(client, at, delta, next, height)) continue;
                    seen.add(next);
                    queue.addLast(new RetreatStep(next, step, step.distance + 1));
                }
            }
        }
        // Keep the first turn even if it temporarily reduces distance to the Warden.
        for (var step = best; step != null && step.previous != null; step = step.previous) {
            wardenDetour.addFirst(step.cell);
        }
    }

    private static boolean teleport(MinecraftClient client, Threats threats, Box body, double current) {
        Vec3d origin=client.player.getPos(),best=null;
        double bestRisk=current,bestCost=Double.POSITIVE_INFINITY;
        var examined=new HashSet<BlockPos>();
        for(double distance:new double[]{3.5,2.0})for(int i=0;i<12;i++){
            double angle=i*Math.PI/6;
            Vec3d top=LocalFormation.evasionLanding(client,origin.add(Math.cos(angle)*distance,0,Math.sin(angle)*distance));
            if(top==null||top.squaredDistanceTo(origin)<.5||!examined.add(BlockPos.ofFloored(top)))continue;
            double risk=threats.risk(body.offset(top.subtract(origin)),Vec3d.ZERO);
            double cost=top.squaredDistanceTo(origin)+Math.abs(top.y-origin.y)*2;
            if(risk<bestRisk-1e-6||(best!=null&&Math.abs(risk-bestRisk)<1e-6&&cost<bestCost)){
                bestRisk=risk;bestCost=cost;best=top;
            }
        }
        return best!=null&&LocalFormation.evadeTo(client,best);
    }

    private static Vec3d walkDirection(MinecraftClient client, Threats threats, Box body, double current) {
        var player = client.player;
        if (!player.isOnGround() || player.isTouchingWater() || client.currentScreen != null) return null;
        Vec3d best = null;
        double bestRisk = current;
        for (int index = 0; index < 8; index++) {
            double angle = index * Math.PI / 4;
            var heading = new Vec3d(Math.cos(angle), 0, Math.sin(angle));
            if (!walkable(client, body, heading)) continue;
            double risk = threats.risk(body, heading.multiply(threats.wardensNearby() ? .07 : .16));
            if (risk < bestRisk) {
                bestRisk = risk;
                best = heading;
            }
        }
        return best;
    }

    static boolean walkableStep(MinecraftClient client, Box body, Vec3d direction, BlockPos next, int height) {
        if (height == 0) return walkable(client, body, direction);
        var world = client.world;
        if (!world.getWorldBorder().contains(next) || !world.getFluidState(next).isEmpty()
                || !world.getFluidState(next.up()).isEmpty()) return false;
        var floor = world.getBlockState(next.down());
        if (dangerousFloor(floor) || !ProjectPlacement.flushFloor(floor, world, next.down())) return false;

        // A jump needs overhead room before crossing, and a drop needs clear space above the landing.
        var highBody = height > 0 ? body.offset(0, 1.18, 0) : body;
        if (height > 0 && !world.isSpaceEmpty(client.player, highBody)) return false;
        for (int step = 1; step <= 5; step++) {
            if (!world.isSpaceEmpty(client.player, highBody.offset(direction.multiply(step * .2)))) return false;
        }
        return world.isSpaceEmpty(client.player, body.offset(direction).offset(0, height, 0));
    }

    private static boolean dangerousFloor(net.minecraft.block.BlockState floor) {
        return floor.isOf(net.minecraft.block.Blocks.MAGMA_BLOCK)
                || floor.isOf(net.minecraft.block.Blocks.CAMPFIRE)
                || floor.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE);
    }

    private static boolean walkable(MinecraftClient client, Box body, Vec3d heading) {
        for (int step = 1; step <= 5; step++) {
            var moved = body.offset(heading.multiply(step * .2));
            var feet = BlockPos.ofFloored(moved.getCenter().x, moved.minY, moved.getCenter().z);
            var floor = client.world.getBlockState(feet.down());
            if (!client.world.getWorldBorder().contains(feet) || !client.world.getFluidState(feet).isEmpty()
                    || dangerousFloor(floor)
                    || !client.world.isSpaceEmpty(client.player, moved)
                    || !client.world.getBlockCollisions(client.player, moved.offset(0, -.08, 0)).iterator().hasNext()) return false;
        }
        return true;
    }
}
