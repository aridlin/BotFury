package com.botfury;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class FleetPortsTest {
    @Test void scansTheConfiguredHundredPortsWithoutOneLargePoll() {
        var fleet = FleetPorts.of(8765, 120);
        assertEquals(100, fleet.count());
        assertEquals(10, fleet.batchSize());
        var seen = new HashSet<Integer>();
        for (int i = 0; i < fleet.count(); i += fleet.batchSize()) {
            fleet.batch(i).forEach(port -> assertTrue(seen.add(port)));
        }
        assertEquals(fleet.all().boxed().collect(java.util.stream.Collectors.toSet()), seen);
        assertTrue(seen.contains(8864));
        assertEquals(2, FleetPorts.of(65534, 100).count());
        assertEquals(0, FleetPorts.of(65536, 100).all().count());
    }

    @Test void supportsAFullRosterAndLetsAnExistingMinerReplaceItsClaim() {
        var claims = new MiningClaims();
        for (int worker = 0; worker < FleetPorts.CAPACITY; worker++) {
            assertTrue(claims.handle(request("bot" + worker + ":first", worker * 5), 100).granted());
        }
        assertFalse(claims.handle(request("extra", 600), 101).granted());
        assertTrue(claims.handle(request("bot99:replacement", 510), 102).granted());
        assertEquals(FleetPorts.CAPACITY, claims.size(103));
    }

    private static MiningClaims.Request request(String owner, int x) {
        return new MiningClaims.Request("claim", owner, "world", x, 60, 0, x + .5, 60, 1.5);
    }
}
