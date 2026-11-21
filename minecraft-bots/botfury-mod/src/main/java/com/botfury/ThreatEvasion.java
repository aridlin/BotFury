package com.botfury;

import com.botfury.mixin.ProjectileGroundAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import java.util.*;

final class ThreatEvasion {
    private record Blast(Vec3d center,double radius) {}
    private record Flight(Vec3d from,Vec3d to,int tick) {}
    private record Threats(List<Blast> blasts,List<Flight> flights) {
        double risk(Box body,Vec3d movement){
            double score=0;
            for(Blast blast:blasts){
                double gap=Math.max(0,blast.radius-body.getCenter().distanceTo(blast.center));
                score+=gap*gap*25;
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
        return new Threats(blasts,flights);
    }
    static boolean dodge(MinecraftClient client){
        if(!LocalFormation.canEvade())return false;
        Threats threats=scan(client);
        Box body=client.player.getBoundingBox();
        double current=threats.risk(body,client.player.getVelocity());
        if(current<=0)return false;
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
}
