package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BuildProtection {
    private static final Gson JSON = new Gson();
    private static final java.lang.reflect.Type TYPE = new TypeToken<List<Zone>>() {}.getType();
    private static final ThreadLocal<String> PROJECT = new ThreadLocal<>();
    private static List<Zone> cached = List.of();
    private static long checked, lastDenied;
    private static boolean unreliable, loadedFile;

    private record Zone(String world, String name, ProjectGather.Bounds bounds, boolean automatic) {
        Zone {
            if (world == null || world.isBlank() || world.length() > 240
                    || name == null || !name.matches("[A-Za-z0-9_-]{1,64}") || bounds == null) {
                throw new IllegalArgumentException("Invalid exclusion zone");
            }
            bounds.checked();
        }

        boolean contains(BlockPos pos) {
            var min = bounds.min();
            var max = bounds.max();
            return pos.getX() >= min.x() && pos.getX() <= max.x() && pos.getY() >= min.y() && pos.getY() <= max.y()
                    && pos.getZ() >= min.z() && pos.getZ() <= max.z();
        }
    }

    private BuildProtection() {}

    private static Path file() {
        return LocalFormation.settingsPath().toAbsolutePath().resolveSibling("botfury-exclusion-zones.json");
    }

    private static List<Zone> read(Path path) throws IOException {
        try {
            List<Zone> zones = JSON.fromJson(Files.readString(path), TYPE);
            if (zones == null || zones.stream().anyMatch(Objects::isNull)) throw new IOException("Invalid exclusion zone file");
            return List.copyOf(zones);
        } catch (JsonParseException | IllegalArgumentException invalid) {
            throw new IOException("Invalid exclusion zone file", invalid);
        }
    }

    private static synchronized List<Zone> zones() {
        long now = System.nanoTime();
        if (now - checked < 250_000_000L) return cached;
        checked = now;
        try {
            cached = read(file());
            unreliable = false;
            loadedFile = true;
        } catch (IOException error) {
            if (!(error instanceof NoSuchFileException) || loadedFile) {
                if (!unreliable) BotEvents.add("warn", "zones", "Cannot read exclusion zones; automated block changes paused");
                unreliable = true;
            }
        }
        return cached;
    }

    private static synchronized void edit(Consumer<List<Zone>> change) throws IOException {
        Path path = file();
        Files.createDirectories(path.getParent());
        try (var channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var zones = new ArrayList<Zone>();
            try { zones.addAll(read(path)); }
            catch (NoSuchFileException error) {
                if (loadedFile) throw error;
            }
            change.accept(zones);
            Path temp = Files.createTempFile(path.getParent(), "botfury-zones-", ".tmp");
            try {
                Files.writeString(temp, JSON.toJson(zones, TYPE));
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                cached = List.copyOf(zones);
                unreliable = false;
                loadedFile = true;
                checked = System.nanoTime();
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static String world() {
        var here = LocalFormation.here();
        return here == null ? null : here.server() + "|" + here.dimension();
    }

    static void reserve(String world, String name, ProjectGather.Area area) throws IOException {
        add(world, name, area.min(), area.max(), true);
    }

    private static void add(String world, String name, BlockPos a, BlockPos b, boolean automatic) throws IOException {
        var min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        var max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        var zone = new Zone(world, name, new ProjectGather.Bounds(StructureSupply.position(min), StructureSupply.position(max)), automatic);
        edit(zones -> {
            if (zones.stream().anyMatch(z -> z.world.equals(world) && z.name.equals(name))) {
                throw new IllegalArgumentException("Zone already exists");
            }
            zones.add(zone);
        });
    }

    static void remove(String world, String name) throws IOException {
        edit(zones -> {
            if (!zones.removeIf(zone -> zone.world.equals(world) && zone.name.equals(name))) {
                throw new IllegalArgumentException("Unknown zone");
            }
        });
    }

    static boolean blocked(BlockPos pos) { return blocked(world(), pos, null); }

    static synchronized boolean blocked(String world, BlockPos pos, String project) {
        var current = zones();
        return unreliable || world != null && current.stream().anyMatch(zone ->
                zone.world.equals(world) && zone.contains(pos) && !(zone.automatic && zone.name.equals(project)));
    }

    public static boolean blocksWorkerInteraction(BlockPos pos) {
        if (!Boolean.getBoolean("botfury.managedHttp") || !blocked(world(), pos, PROJECT.get())) return false;
        long now = System.nanoTime();
        if (now - lastDenied > 2_000_000_000L) {
            lastDenied = now;
            BotEvents.add("warn", "zones", "Blocked automated change at " + pos.toShortString());
        }
        return true;
    }

    public static boolean blocksWorkerBreaking(BlockPos pos) {
        return blocksWorkerInteraction(pos) || ProjectWorker.blocksRecoveryBreak(pos);
    }

    static <T> T projectAction(String project, Supplier<T> action) {
        PROJECT.set(project);
        try {
            return action.get();
        } finally {
            PROJECT.remove();
        }
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        var root = ClientCommandManager.literal("zone");
        var names = new String[]{"x1", "y1", "z1", "x2", "y2", "z2"};
        RequiredArgumentBuilder<FabricClientCommandSource, Integer> coordinates = ClientCommandManager
                .argument("z2", IntegerArgumentType.integer())
                .executes(ctx -> change(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"),
                        new BlockPos(IntegerArgumentType.getInteger(ctx, "x1"), IntegerArgumentType.getInteger(ctx, "y1"),
                                IntegerArgumentType.getInteger(ctx, "z1")),
                        new BlockPos(IntegerArgumentType.getInteger(ctx, "x2"), IntegerArgumentType.getInteger(ctx, "y2"),
                                IntegerArgumentType.getInteger(ctx, "z2"))));
        for (int i = names.length - 2; i >= 0; i--) {
            coordinates = ClientCommandManager.argument(names[i], IntegerArgumentType.integer()).then(coordinates);
        }
        return root
                .then(ClientCommandManager.literal("add").then(ClientCommandManager.argument("name", StringArgumentType.word()).then(coordinates)))
                .then(ClientCommandManager.literal("remove").then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> change(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"), null, null))))
                .then(ClientCommandManager.literal("list").executes(ctx -> {
                    String world = world();
                    var list = zones().stream().filter(zone -> zone.world.equals(world)).toList();
                    var client = ctx.getSource().getClient();
                    if (list.isEmpty()) ClientActions.message(client, "No exclusion zones in this dimension");
                    for (Zone zone : list) ClientActions.message(client, zone.name + (zone.automatic ? " (build)" : "")
                            + " " + zone.bounds.min().checked().toShortString() + " to " + zone.bounds.max().checked().toShortString());
                    return 1;
                }));
    }

    private static int change(MinecraftClient client, String name, BlockPos a, BlockPos b) {
        try {
            String world = world();
            if (world == null || Boolean.getBoolean("botfury.managedHttp")) {
                throw new IllegalArgumentException("Join the world on the controller to edit exclusion zones");
            }
            if (a == null) remove(world, name);
            else add(world, name, a, b, false);
            ClientActions.message(client, (a == null ? "Removed exclusion zone " : "Created exclusion zone ") + name);
            return 1;
        } catch (IOException | IllegalArgumentException error) {
            ClientActions.message(client, error.getMessage());
            return 0;
        }
    }
}
