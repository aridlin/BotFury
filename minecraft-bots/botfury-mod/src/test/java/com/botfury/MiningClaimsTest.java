package com.botfury;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MiningClaimsTest {
    private MiningClaims.Request req(String owner,int x,double stand){return new MiningClaims.Request("claim",owner,"server|world",x,64,0,stand,64,.5);}
    @Test void oreAndStandingPositionsAreExclusive(){
        var c=new MiningClaims();assertTrue(c.handle(req("first:1",0,1.5),0).granted());
        assertFalse(c.handle(req("second:1",0,-1.5),1).granted());
        assertFalse(c.handle(req("second:1",2,1.5),1).granted());
        assertTrue(c.handle(req("second:1",3,4.5),1).granted());
    }
    @Test void expiredClaimsFreeOreAndOtherDimensionsDoNotConflict(){
        var c=new MiningClaims();c.handle(req("one:1",0,1.5),0);
        assertTrue(c.handle(new MiningClaims.Request("claim","two:1","server|nether",0,64,0,1.5,64,.5),1).granted());
        assertTrue(c.handle(req("three:1",0,1.5),12001).granted());
    }
    @Test void lateReleaseCannotRevokeNewClaimForSameMiner(){
        var c=new MiningClaims();assertTrue(c.handle(req("miner:1",0,1.5),0).granted());
        assertTrue(c.handle(req("miner:2",3,4.5),1).granted());
        c.handle(new MiningClaims.Request("release","miner:1","server|world",0,0,0,0,0,0),2);
        assertFalse(c.handle(req("other:1",3,4.5),3).granted());assertEquals(1,c.size(4));
    }
    @Test void renewalCannotRestoreExpiredOrReplacedClaims() {
        var claims = new MiningClaims();
        var renew = new MiningClaims.Request("renew", "miner:1", "server|world", 0, 64, 0, 1.5, 64, .5);
        assertTrue(claims.handle(req("miner:1", 0, 1.5), 0).granted());
        assertTrue(claims.handle(renew, 10).granted());
        assertTrue(claims.handle(req("miner:2", 3, 4.5), 20).granted());
        assertFalse(claims.handle(renew, 30).granted());
        assertFalse(claims.handle(req("other:1", 3, 4.5), 40).granted());
        var expired = new MiningClaims.Request("renew", "miner:2", "server|world", 3, 64, 0, 4.5, 64, .5);
        assertFalse(claims.handle(expired, 12_021).granted());
        assertEquals(0, claims.size(12_022));
    }

    @Test void invalidStandingCoordinatesAreRejected(){
        var c=new MiningClaims();assertFalse(c.handle(new MiningClaims.Request("claim","bad","world",0,64,0,Double.NaN,64,0),0).granted());
    }
}
