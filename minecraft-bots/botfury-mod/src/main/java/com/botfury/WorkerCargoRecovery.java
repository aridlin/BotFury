package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/** Returns a respawned worker to the drops from its interrupted project work. */
final class WorkerCargoRecovery {
    private static final Gson JSON = new Gson();
    private static final long WINDOW_MS = 120_000;
    private static final int DROP_RADIUS = 7;
    private record Cargo(String item, int count, ProjectQueue.ResourceSite location, long expiresAt) {}
    private record Saved(WorkerEnrollment worker, Cargo cargo) {}

    private final DroppedItemSearch pickup = new DroppedItemSearch(20);
    private Cargo cargo;
    private ProjectItem item;
    private BaritoneSettings travel;
    private long nextPath, emptySince;

    void remember(Path path, WorkerEnrollment worker, ProjectItem lostItem, int count, BlockPos position) {
        cargo = new Cargo(lostItem.key(), count, new ProjectQueue.ResourceSite(position.getX(), position.getY(),
                position.getZ()), System.currentTimeMillis() + WINDOW_MS);
        item = lostItem;
        try {
            var parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            var temporary = Files.createTempFile(parent, "botfury-cargo-", ".tmp");
            try {
                Files.writeString(temporary, JSON.toJson(new Saved(worker, cargo)));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            BotEvents.add("warn", "projects", "Could not save the dropped cargo location");
        }
        BotEvents.add("info", "projects", "Marked dropped " + item + " for recovery");
    }

    void load(Path path, WorkerEnrollment worker) {
        cargo = null;
        item = null;
        if (!Files.isRegularFile(path)) return;
        try {
            if (Files.size(path) > 4096) throw new IOException("Dropped cargo file is too large");
            var saved = JSON.fromJson(Files.readString(path), Saved.class);
            if (saved == null || !worker.equals(saved.worker())) return;
            var stored = saved.cargo();
            if (stored == null || stored.count() < 1 || stored.location() == null
                    || stored.expiresAt() <= System.currentTimeMillis()) {
                clear(path);
                return;
            }
            item = ProjectItem.parse(stored.item());
            cargo = stored;
        } catch (IOException | RuntimeException error) {
            BotEvents.add("warn", "projects", "Could not read the dropped cargo location");
        }
    }

    boolean pending() { return cargo != null; }

    boolean tick(MinecraftClient client, Path path) {
        if (cargo == null) return false;
        int held = item.count(client);
        if (held >= cargo.count() || System.currentTimeMillis() >= cargo.expiresAt()) {
            return finish(path, held);
        }
        var death = cargo.location();
        var position = new BlockPos(death.x(), death.y(), death.z());
        var center = Vec3d.ofCenter(position);
        if (!client.world.getWorldBorder().contains(position) || !client.world.isInBuildLimit(position)) {
            return finish(path, held);
        }
        var player = client.player;
        long now = System.nanoTime();
        if (player.squaredDistanceTo(center) > 8 * 8) {
            pickup.reset();
            emptySince = 0;
            if (travel == null) {
                try {
                    travel = new BaritoneSettings(false, true);
                } catch (ReflectiveOperationException | LinkageError unavailable) {
                    return finish(path, held);
                }
            }
            if (now >= nextPath && !BaritoneClient.pursuing(position)) {
                if (!BaritoneClient.goTo(position)) return finish(path, held);
                nextPath = now + 3_000_000_000L;
            }
            return true;
        }
        stopTravel();
        var drop = client.world.getEntitiesByClass(ItemEntity.class, new Box(position).expand(DROP_RADIUS),
                        entity -> entity.isAlive() && item.matches(entity.getStack())
                                && entity.squaredDistanceTo(center) <= DROP_RADIUS * DROP_RADIUS)
                .stream().min(Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
        if (drop != null && pickup.approachReserved(client, drop) == DroppedItemSearch.State.APPROACHING) {
            emptySince = 0;
            return true;
        }
        if (emptySince == 0) emptySince = now;
        if (now - emptySince < 3_000_000_000L) return true;
        return finish(path, held);
    }

    private boolean finish(Path path, int held) {
        BotEvents.add("info", "projects", held > 0 ? "Recovered " + held + " " + item + " after respawn"
                : "Dropped project cargo could not be recovered");
        clear(path);
        return false;
    }

    void clear(Path path) {
        stop();
        cargo = null;
        item = null;
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            BotEvents.add("warn", "projects", "Could not clear the dropped cargo record");
        }
    }

    void stop() {
        pickup.reset();
        stopTravel();
        emptySince = nextPath = 0;
    }

    private void stopTravel() {
        if (travel == null) return;
        BaritoneClient.execute("stop");
        travel.close();
        travel = null;
    }
}
