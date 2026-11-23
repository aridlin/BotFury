package com.botfury;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BotEvents {
    record Event(long id, String time, String level, String source, String message) {}
    private static final ArrayDeque<Event> events = new ArrayDeque<>();
    private static long next;
    static volatile String last = "Ready";
    static volatile String lastLevel = "info";
    static volatile long lastAt;
    static synchronized void add(String level, String source, String message) {
        if (message == null) return;
        message = message.substring(0, Math.min(message.length(), 512));
        if (!events.isEmpty() && events.getLast().source.equals(source) && events.getLast().message.equals(message)
                && Instant.parse(events.getLast().time).plusSeconds(1).isAfter(Instant.now())) return;
        last = message;
        lastLevel = level;
        lastAt = System.currentTimeMillis();
        events.addLast(new Event(++next, Instant.now().toString(), level, source,
                message.substring(0, Math.min(message.length(), 512))));
        while (events.size() > 256) events.removeFirst();
        System.out.println("[BotFury/" + level + "] " + source + ": " + message);
    }
    static synchronized String tailJson() {
        var selected = events.stream().skip(Math.max(0, events.size() - 40)).toList();
        return new Gson().toJson(Map.of("cursor", next, "latestCursor", next, "events", selected));
    }
    static synchronized List<Event> recent() {
        var recent = new ArrayList<Event>();
        var newest = events.descendingIterator();
        while (newest.hasNext() && recent.size() < 100) recent.add(newest.next());
        return List.copyOf(recent);
    }
    static synchronized String json(long after) {
        List<Event> selected = events.stream().filter(event -> event.id > after).limit(100).toList();
        long cursor = selected.isEmpty() ? next : selected.get(selected.size() - 1).id;
        boolean truncated = !events.isEmpty() && events.getFirst().id > after + 1;
        return new Gson().toJson(Map.of("cursor", cursor, "latestCursor", next, "truncated", truncated, "events", selected));
    }
}
