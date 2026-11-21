package com.botfury;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CommandQueryTest {
    @Test void encodedSeparatorsRemainInsideCommand() {
        var query=CommandQuery.parse("cmd=chat%3Aone%26two%2Bthree&session=dash&sequence=5");
        assertEquals("chat:one&two+three",query.get("cmd"));
        assertEquals("5",query.get("sequence"));
    }
    @Test void malformedQueriesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->CommandQuery.parse("cmd=one&cmd=two"));
        assertThrows(IllegalArgumentException.class,()->CommandQuery.parse("cmd=%ZZ"));
    }
    @Test void delayedCommandsFromSameDashboardCannotUndoStop() {
        var gate=new SequenceHistory();
        assertTrue(gate.accept("dashboard",10));
        assertTrue(gate.accept("dashboard",12));
        assertFalse(gate.accept("dashboard",11));
        assertTrue(gate.accept("other-controller",1));
    }
}
