package com.botfury;
import java.util.LinkedHashMap;
final class SequenceHistory {
    private final LinkedHashMap<String,Long> history = new LinkedHashMap<>();
    synchronized boolean accept(String sender,long sequence) {
        if (sender == null || sender.isBlank() || sender.length() > 80 || sequence < 1) return false;
        if (sequence <= history.getOrDefault(sender,0L)) return false;
        history.put(sender,sequence);
        while(history.size()>128) history.remove(history.keySet().iterator().next());
        return true;
    }
}
