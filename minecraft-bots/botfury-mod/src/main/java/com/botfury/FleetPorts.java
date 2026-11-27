package com.botfury;

import java.util.stream.IntStream;

/** One control port per bot. Ports on other hosts can be forwarded to this range. */
record FleetPorts(int first, int count) {
    static final int CAPACITY = 100;

    static FleetPorts configured() {
        return of(Integer.getInteger("botfury.formationBasePort", 8765),
                Integer.getInteger("botfury.formationPortCount", 10));
    }

    static FleetPorts of(int first, int requested) {
        if (first < 1024 || first > 65535) return new FleetPorts(first, 0);
        int count = Math.max(0, Math.min(requested, Math.min(CAPACITY, 65536 - first)));
        return new FleetPorts(first, count);
    }

    IntStream all() {
        return IntStream.range(first, first + count);
    }

    /** Small fleets retain one poll per pass. Larger fleets spread a pass across ten ticks. */
    int batchSize() {
        return count <= 32 ? count : (count + 9) / 10;
    }

    IntStream batch(int offset) {
        if (count == 0) return IntStream.empty();
        return IntStream.range(0, batchSize()).map(i -> first + (offset + i) % count);
    }
}
