package com.botfury;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Explicit opt-in for one local coordinator, player and Minecraft world. */
record WorkerEnrollment(String name, String world, int port) {
    private static final Gson JSON = new Gson();

    WorkerEnrollment {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}") || world == null || world.isBlank()
                || world.length() > 240 || port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Invalid project worker enrollment");
        }
    }

    boolean matches(String player, String location) {
        return name.equalsIgnoreCase(player) && world.equals(location);
    }

    static WorkerEnrollment read(Path path) throws IOException {
        try {
            if (Files.size(path) > 4096) throw new IOException("Project worker enrollment is too large");
            return JSON.fromJson(Files.readString(path), WorkerEnrollment.class);
        } catch (NoSuchFileException ignored) {
            return null;
        } catch (RuntimeException error) {
            throw new IOException("Invalid project worker enrollment", error);
        }
    }

    static void save(Path path, WorkerEnrollment enrollment) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "botfury-worker-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(enrollment));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
