package com.botfury;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BotEventsTest {
    @Test void paginationDoesNotSkipUnreadEventsAndReportsOverflow() {
        for (int i=0;i<270;i++) BotEvents.add("info","test","event "+i);
        var first=JsonParser.parseString(BotEvents.json(0)).getAsJsonObject();
        assertTrue(first.get("truncated").getAsBoolean());
        assertEquals(100,first.getAsJsonArray("events").size());
        long cursor=first.get("cursor").getAsLong();
        var second=JsonParser.parseString(BotEvents.json(cursor)).getAsJsonObject();
        assertEquals(100,second.getAsJsonArray("events").size());
        var third=JsonParser.parseString(BotEvents.json(second.get("cursor").getAsLong())).getAsJsonObject();
        assertEquals(56,third.getAsJsonArray("events").size());
        assertEquals(third.get("latestCursor"),third.get("cursor"));
    }
}
