package com.botfury;
import java.util.*;

/** One ore and a separated standing cell per miner; leases survive short polling gaps. */
final class MiningClaims {
    record Request(String action,String owner,String world,int x,int y,int z,double sx,double sy,double sz) {}
    record Reply(boolean granted,String reason,long leaseMillis) {}
    private record Lease(Request request,long expires) {}
    private final Map<String,Lease> leases=new HashMap<>();
    private final java.util.function.Predicate<Request> protectedTarget;

    MiningClaims() { this(request -> false); }

    MiningClaims(java.util.function.Predicate<Request> protectedTarget) {
        this.protectedTarget = protectedTarget;
    }

    synchronized Reply handle(Request r,long now){
        leases.values().removeIf(lease->lease.expires<=now);
        if(r==null||r.action==null||r.owner==null||r.owner.length()>96||r.world==null||r.world.length()>200)return new Reply(false,"Invalid claim",0);
        if(r.action.equals("release")){leases.remove(r.owner);return new Reply(true,"Released",0);}
        if(!Set.of("claim","renew").contains(r.action)||!Double.isFinite(r.sx)||!Double.isFinite(r.sy)||!Double.isFinite(r.sz)
                ||Math.abs((long)r.x)>30000000||Math.abs((long)r.z)>30000000||r.y< -128||r.y>512
                ||Math.pow(r.x+.5-r.sx,2)+Math.pow(r.y+.5-(r.sy+1.62),2)+Math.pow(r.z+.5-r.sz,2)>25)
            return new Reply(false,"Invalid mining position",0);
        if (protectedTarget.test(r)) {
            leases.remove(r.owner);
            return new Reply(false, "Mining target is in an exclusion zone", 0);
        }
        if (r.action.equals("renew")) {
            Lease existing = leases.get(r.owner);
            if (existing == null || !samePosition(existing.request, r)) {
                return new Reply(false, "Mining reservation expired or changed", 0);
            }
            leases.put(r.owner, new Lease(existing.request, now + 12_000));
            return new Reply(true, "Reserved", 12_000);
        }
        for(var entry:leases.entrySet()){
            var q=entry.getValue().request;
            if(family(entry.getKey()).equals(family(r.owner))||!q.world.equals(r.world))continue;
            if(q.x==r.x&&q.y==r.y&&q.z==r.z)return new Reply(false,"Ore is reserved",0);
            if(Math.pow(q.sx-r.sx,2)+Math.pow(q.sy-r.sy,2)+Math.pow(q.sz-r.sz,2)<2.25)return new Reply(false,"Mining stand is reserved",0);
        }
        if(leases.size()>=64&&!leases.containsKey(r.owner))return new Reply(false,"Mining roster full",0);
        leases.keySet().removeIf(key->family(key).equals(family(r.owner)));
        leases.put(r.owner,new Lease(r,now+12_000));return new Reply(true,"Reserved",12_000);
    }
    private static boolean samePosition(Request a, Request b) {
        return a.world.equals(b.world) && a.x == b.x && a.y == b.y && a.z == b.z
                && a.sx == b.sx && a.sy == b.sy && a.sz == b.sz;
    }

    private static String family(String owner){int split=owner.lastIndexOf(':');return split<0?owner:owner.substring(0,split);}
    synchronized int size(long now){leases.values().removeIf(l->l.expires<=now);return leases.size();}
}
