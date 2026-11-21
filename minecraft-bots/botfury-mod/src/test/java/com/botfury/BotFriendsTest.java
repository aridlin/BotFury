package com.botfury;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BotFriendsTest {
    @Test void protectsMasterFriendsAndFleetIgnoringCase() {
        var safe = List.of("Alice", "Bot01", "Bot02");
        assertTrue(BotFriends.protectedName("FURYMASTER", "FuryMaster", safe));
        assertTrue(BotFriends.protectedName("alice", "FuryMaster", safe));
        assertTrue(BotFriends.protectedName("bot02", "FuryMaster", safe));
        assertFalse(BotFriends.protectedName("Hostile", "FuryMaster", safe));
    }
    @Test void incompleteRosterPreventsAutomaticAttacks() {
        assertTrue(BotFriends.protectedName("Anyone", null, List.of()));
        assertTrue(BotFriends.protectedName("Anyone", "Master", null));
    }
    @Test void friendInputNormalizesAndRejectsMalformedNames() {
        assertEquals(List.of("alice", "bob"), BotFriends.parse("Alice, BOB\n alice"));
        assertThrows(IllegalArgumentException.class, () -> BotFriends.parse("bad/name"));
    }
}
