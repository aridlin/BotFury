package com.botfury;

import java.util.LinkedHashMap;
import java.util.Map;

/** Rejects reordered packets and prevents two controllers fighting over the same bot. */
final class OrderGate {
    private static final long LEASE_NS = 4_000_000_000L;
    private final Map<String, Long> sequences = new LinkedHashMap<>();
    private String owner;
    private long touched;
    synchronized boolean accept(String session, long sequence, long now) {
        if (session == null || session.length() > 64 || sequence < 1) return false;
        if (owner != null && !owner.equals(session) && now - touched < LEASE_NS) return false;
        if (sequence <= sequences.getOrDefault(session, 0L)) return false;
        sequences.put(session, sequence);
        while (sequences.size() > 64) sequences.remove(sequences.keySet().iterator().next());
        owner = session;
        touched = now;
        return true;
    }
    synchronized void reset() { sequences.clear(); owner = null; touched = 0; }
}
