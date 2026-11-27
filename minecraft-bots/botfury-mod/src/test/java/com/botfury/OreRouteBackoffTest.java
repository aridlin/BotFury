package com.botfury;

import java.time.Duration;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreRouteBackoffTest {
    @Test void failedApproachDefersNeighboringOreAcrossJobsUntilConditionsChange() {
        var routes = new OreRouteBackoff();
        var ore = new BlockPos(-159, -47, 114);
        var approach = new BlockPos(-137, -46, 71);
        routes.defer("server|overworld", "diamond", ore, approach, approach, 100);

        assertTrue(routes.deferred("server|overworld", "diamond", ore.west().south(), approach.north(8), 101));
        assertFalse(routes.deferred("server|overworld", "diamond", ore.east(6), approach, 101));
        assertTrue(routes.deferred("server|overworld", "diamond", ore, approach.south(40), 101));
        assertFalse(routes.deferred("server|overworld", "diamond", ore, approach.south(65), 101));
        assertFalse(routes.deferred("server|the_nether", "diamond", ore, approach, 101));
        assertFalse(routes.deferred("server|overworld", "iron", ore, approach, 101));
        assertFalse(routes.deferred("server|overworld", "diamond", ore, approach, 100 + Duration.ofMinutes(3).toNanos()));
    }

    @Test void aShortFailedApproachDoesNotDeferTheVeinFromTheNextCave() {
        var routes = new OreRouteBackoff();
        var ore = new BlockPos(3, -48, 12);
        var approach = ore.north(6);
        routes.defer("server|overworld", "diamond", ore, approach, approach, 100);

        assertTrue(routes.deferred("server|overworld", "diamond", ore, approach.west(15), 101));
        assertFalse(routes.deferred("server|overworld", "diamond", ore, approach.west(30), 101));
    }

    @Test void aDistantFailedRouteDefersBothEndsAndLeavesOtherApproachesAvailable() {
        var routes = new OreRouteBackoff();
        var ore = new BlockPos(42, -54, -56);
        var start = new BlockPos(3, -53, 5);
        var stop = new BlockPos(92, -54, 24);
        routes.defer("server|overworld", "diamond", ore, start, stop, 100);

        assertTrue(routes.deferred("server|overworld", "diamond", ore, start, 101));
        assertTrue(routes.deferred("server|overworld", "diamond", ore, stop, 101));
        assertFalse(routes.deferred("server|overworld", "diamond", ore, ore.north(16), 101));
    }
}
