package com.botfury;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

final class BotFriends {
    private static List<String> cached = List.of();
    private static long checked;
    private static volatile boolean reliable = true;
    static boolean reliable() { return reliable; }
    private static Path path() {
        return LocalFormation.settingsPath().toAbsolutePath().resolveSibling("botfury-friends.txt");
    }
    static synchronized List<String> names() {
        if (System.nanoTime() - checked < 1_000_000_000L) return cached;
        checked = System.nanoTime();
        try {
            cached = parse(Files.readString(path()));
            if (!reliable) BotEvents.add("info", "friends", "Friend list recovered");
            reliable = true;
        } catch (java.nio.file.NoSuchFileException error) {
            // A new installation has no friends file; keep known names if it was removed later.
        } catch (IOException | IllegalArgumentException error) {
            if (reliable) BotEvents.add("warn", "friends", "Friend list unreadable; automatic attacks paused");
            reliable = false;
        }
        return cached;
    }
    static List<String> parse(String value) {
        var names = new TreeSet<String>();
        for (String name : value.split("[,\\s]+")) {
            if (name.isBlank()) continue;
            if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid friend name: " + name);
            names.add(name.toLowerCase(Locale.ROOT));
        }
        if (names.size() > 128) throw new IllegalArgumentException("Friend list limit is 128 names");
        return List.copyOf(names);
    }
    static boolean protectedName(String candidate, String owner, java.util.Collection<String> names) {
        return owner == null || names == null || candidate.equalsIgnoreCase(owner)
                || names.stream().anyMatch(candidate::equalsIgnoreCase);
    }
    static synchronized void edit(String operation, String value) throws IOException {
        var changes = parse(value);
        if (changes.isEmpty()) throw new IllegalArgumentException("Enter at least one friend name");
        if (!operation.equals("add") && !operation.equals("remove")) throw new IllegalArgumentException("Unknown friends action");
        Path target = path();
        Files.createDirectories(target.getParent());
        try (var channel = java.nio.channels.FileChannel.open(target.resolveSibling(target.getFileName() + ".lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var names = new TreeSet<String>();
            if (Files.exists(target)) names.addAll(parse(Files.readString(target)));
            if (operation.equals("add")) names.addAll(changes); else names.removeAll(changes);
            Path temporary = Files.createTempFile(target.getParent(), "botfury-friends-", ".tmp");
            try {
                Files.writeString(temporary, String.join("\n", names) + "\n");
                try { Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
            cached = List.copyOf(names);
            reliable = true;
            checked = System.nanoTime();
        }
    }
}
