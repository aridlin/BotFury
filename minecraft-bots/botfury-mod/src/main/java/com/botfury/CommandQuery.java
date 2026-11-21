package com.botfury;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
final class CommandQuery {
    static Map<String,String> parse(String raw) {
        if (raw == null || raw.length() > 8192) throw new IllegalArgumentException("Missing or oversized command query");
        var fields = new LinkedHashMap<String,String>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Malformed command query");
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (fields.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate command field");
        }
        return fields;
    }
}
