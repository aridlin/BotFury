package com.botfury;

import com.google.gson.Gson;
import com.botfury.ProjectQueue.ResourceSite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Grounded waypoints walked by one worker, in travel order. */
final class WorkerReturnTrail {
    private static final int CAPACITY = 256;
    private static final Gson JSON = new Gson();
    private record Saved(WorkerEnrollment worker, List<ResourceSite> points) {}
    private final List<ResourceSite> points = new ArrayList<>();
    private boolean dirty;

    void record(ResourceSite position) {
        if (!points.isEmpty()) {
            var previous = points.get(points.size() - 1);
            if (distanceSquared(previous, position) < 16) return;
            // A teleport or a large drop does not establish a return path.
            if (distanceSquared(previous, position) > 18 * 18 || previous.y() - position.y() > 5) {
                points.clear();
            } else {
                for (int i = points.size() - 2; i >= 0; i--) {
                    var earlier = points.get(i);
                    if (earlier.y() == position.y() && horizontalSquared(earlier, position) <= 4) {
                        points.subList(i + 1, points.size()).clear();
                        dirty = true;
                        return;
                    }
                }
            }
        }
        if (points.size() == CAPACITY) points.remove(0);
        points.add(position);
        dirty = true;
    }

    List<ResourceSite> backtrack(ResourceSite current, ResourceSite station) {
        var route = new ArrayList<ResourceSite>();
        boolean joined = false;
        for (int i = points.size() - 1; i >= 0; i--) {
            var step = points.get(i);
            if (!joined) {
                if (distanceSquared(current, step) > 8 * 8 || Math.abs(current.y() - step.y()) > 4) continue;
                joined = true;
            }
            if (!step.equals(current)) route.add(step);
            if (Math.abs(station.y() - step.y()) <= 5 && horizontalSquared(station, step) <= 16 * 16) {
                return List.copyOf(route);
            }
        }
        return List.of();
    }

    void load(Path path, WorkerEnrollment worker) throws IOException {
        points.clear();
        dirty = false;
        try {
            if (Files.size(path) > 32_768) throw new IOException("Worker return trail is too large");
            var saved = JSON.fromJson(Files.readString(path), Saved.class);
            if (saved == null || !worker.equals(saved.worker())) return;
            if (saved.points() == null || saved.points().size() > CAPACITY
                    || saved.points().stream().anyMatch(java.util.Objects::isNull)) {
                throw new IOException("Invalid worker return trail");
            }
            points.addAll(saved.points());
        } catch (NoSuchFileException ignored) {
            // A worker starts with no known route.
        } catch (RuntimeException error) {
            throw new IOException("Invalid worker return trail", error);
        }
    }

    void save(Path path, WorkerEnrollment worker) throws IOException {
        if (!dirty) return;
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "botfury-return-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(new Saved(worker, points)));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static double distanceSquared(ResourceSite a, ResourceSite b) {
        return horizontalSquared(a, b) + Math.pow(a.y() - b.y(), 2);
    }

    private static double horizontalSquared(ResourceSite a, ResourceSite b) {
        return Math.pow(a.x() - b.x(), 2) + Math.pow(a.z() - b.z(), 2);
    }
}
