package com.botfury;

import java.util.HashSet;
import java.util.Set;

final class GatherProgress {
    private record Region(int x, int y, int z) {}

    private final Set<Region> visited = new HashSet<>();
    private long lastProgress;
    private int highWater;
    private int highestAscent = Integer.MIN_VALUE;

    void reset() {
        visited.clear();
        lastProgress = 0;
        highWater = 0;
        highestAscent = Integer.MIN_VALUE;
    }

    void recordPreparation(long now) {
        lastProgress = now;
    }

    boolean active(int x, int y, int z, boolean surface, boolean climbing, int held, long now, long timeout) {
        // Adjacent blocks and repeat visits to the same search area do not renew the lease.
        var region = new Region(x >> 4, surface ? 0 : y >> 3, z >> 4);
        boolean gainedHeight = climbing && y > highestAscent;
        if (gainedHeight) highestAscent = y;
        if (visited.add(region) || held > highWater || gainedHeight) {
            lastProgress = now;
            highWater = Math.max(highWater, held);
        }
        return now - lastProgress < timeout;
    }
}
