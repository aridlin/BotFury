package com.botfury;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OrderGateTest {
    @Test void delayedStopCannotOverrideNewerAttack() {
        var gate = new OrderGate();
        assertTrue(gate.accept("master", 4, 100));
        assertTrue(gate.accept("master", 6, 200));
        assertFalse(gate.accept("master", 5, 300));
        assertFalse(gate.accept("master", 6, 400));
    }
    @Test void controllerLeaseExpiresAndRestartCanTakeOver() {
        var gate = new OrderGate();
        assertTrue(gate.accept("old", 20, 0));
        assertFalse(gate.accept("new", 1, 3_000_000_000L));
        assertTrue(gate.accept("new", 1, 4_000_000_001L));
        assertFalse(gate.accept("old", 21, 4_000_000_002L));
    }
    @Test void worldChangeClearsSequenceHistory() {
        var gate = new OrderGate();
        assertTrue(gate.accept("master", 5, 0));
        gate.reset();
        assertTrue(gate.accept("master", 1, 1));
        assertFalse(gate.accept(null, 1, 2));
        assertFalse(gate.accept("master", 0, 2));
    }
}
