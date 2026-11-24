package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** Returns a respawned worker to the drops from its interrupted project work. */
final class WorkerCargoRecovery {
    private static final Gson JSON = new Gson();
    private static final long WINDOW_MS = 120_000;
    private static final int DROP_RADIUS = 7;
    private record Cargo(String item, int count, ProjectQueue.ResourceSite location, long expiresAt,
                         Map<String, Integer> contents) {}
    private record Saved(WorkerEnrollment worker, Cargo cargo) {}

    private final DroppedItemSearch pickup = new DroppedItemSearch(20);
    private final Map<String, Integer> held = new HashMap<>();
    private Cargo cargo;
    private ProjectItem item;
    private BaritoneSettings travel;
    private long nextPath, emptySince;

    void remember(Path path, WorkerEnrollment worker, ProjectItem lostItem, Map<String, Integer> contents, BlockPos position) {
        cargo = new Cargo(lostItem == null ? null : lostItem.key(), lostItem == null ? 0 : lostItem.count(contents),
                new ProjectQueue.ResourceSite(position.getX(), position.getY(), position.getZ()),
                System.currentTimeMillis() + WINDOW_MS, Map.copyOf(contents));
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
        BotEvents.add("info", "projects", "Marked dropped project inventory for recovery");
    }

    void load(Path path, WorkerEnrollment worker) {
        cargo = null;
        item = null;
        if (!Files.isRegularFile(path)) return;
        try {
            if (Files.size(path) > 32768) throw new IOException("Dropped cargo file is too large");
            var saved = JSON.fromJson(Files.readString(path), Saved.class);
            if (saved == null || !worker.equals(saved.worker())) return;
            var stored = saved.cargo();
            if (stored == null || stored.location() == null
                    || stored.contents() == null && (stored.item() == null || stored.count() < 1)
                    || stored.contents() != null && (stored.contents().isEmpty() || stored.contents().size() > 41
                        || stored.contents().values().stream().anyMatch(count -> count == null || count < 1))
                    || stored.expiresAt() <= System.currentTimeMillis()) {
                clear(path);
                return;
            }
            item = stored.item() == null ? null : ProjectItem.parse(stored.item());
            cargo = stored;
        } catch (IOException | RuntimeException error) {
            BotEvents.add("warn", "projects", "Could not read the dropped cargo location");
        }
    }

    boolean pending() { return cargo != null; }

    boolean tick(MinecraftClient client, Path path) {
        if (cargo == null) return false;
        snapshotInventory(client, held);
        boolean complete = cargo.contents() == null ? item.count(held) >= cargo.count()
                : cargo.contents().entrySet().stream().allMatch(entry -> held.getOrDefault(entry.getKey(), 0) >= entry.getValue());
        if (complete || System.currentTimeMillis() >= cargo.expiresAt()) {
            return finish(path);
        }
        var death = cargo.location();
        var position = new BlockPos(death.x(), death.y(), death.z());
        var center = Vec3d.ofCenter(position);
        if (!client.world.getWorldBorder().contains(position) || !client.world.isInBuildLimit(position)) {
            return finish(path);
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
                    return finish(path);
                }
            }
            if (now >= nextPath && !BaritoneClient.pursuing(position)) {
                if (!BaritoneClient.goTo(position)) return finish(path);
                nextPath = now + 3_000_000_000L;
            }
            return true;
        }
        stopTravel();
        if (pickup.tickNear(client, this::neededDrop, center, DROP_RADIUS) == DroppedItemSearch.State.APPROACHING) {
            emptySince = 0;
            return true;
        }
        if (emptySince == 0) emptySince = now;
        if (now - emptySince < 3_000_000_000L) return true;
        return finish(path);
    }

    static void snapshotInventory(MinecraftClient client, Map<String, Integer> counts) {
        counts.clear();
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (!stack.isEmpty()) counts.merge(itemKey(stack), stack.getCount(), Integer::sum);
        }
    }

    private static String itemKey(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private boolean neededDrop(ItemStack stack) {
        if (cargo.contents() == null) return item.matches(stack) && item.count(held) < cargo.count();
        String key = itemKey(stack);
        return held.getOrDefault(key, 0) < cargo.contents().getOrDefault(key, 0);
    }

    private boolean finish(Path path) {
        int primary = item == null ? 0 : Math.min(cargo.count(), item.count(held));
        int recovered = cargo.contents() == null ? primary : cargo.contents().entrySet().stream()
                .mapToInt(entry -> Math.min(entry.getValue(), held.getOrDefault(entry.getKey(), 0))).sum();
        if (primary > 0) BotEvents.add("info", "projects", "Recovered " + primary + " " + item + " after respawn");
        else BotEvents.add("info", "projects", recovered > 0 ? "Recovered " + recovered + " project items after respawn"
                : "Dropped project inventory could not be recovered");
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
