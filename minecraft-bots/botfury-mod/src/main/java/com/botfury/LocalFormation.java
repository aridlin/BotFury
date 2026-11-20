package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

final class LocalFormation {
    private static final Gson JSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(350)).build();
    private static final Map<Integer, Peer> PEERS = new ConcurrentHashMap<>();
    private static volatile Peer local;
    private static volatile Order incoming;
    private static volatile long receivedAt;
    private static String mode = "";
    private static int tick;
    private static int castTick;
    private static BlockPos lastGoal;
    private static int lastGoalTick;
    private static boolean pathOwned;
    private static BaritoneBlocks savedBlocks;
    private static int step;

    record Peer(String server, String dimension, String name, double x, double y, double z,
                boolean baritone, long time) {}
    record Order(String server, String dimension, String action, double x, double y, double z) {}

    private LocalFormation() {}

    static String status() {
        return JSON.toJson(local);
    }

    static synchronized boolean receive(String json) {
        try {
            Order order = JSON.fromJson(json, Order.class);
            Peer here = local;
            if (order == null || here == null || !here.server.equals(order.server)
                    || !here.dimension.equals(order.dimension)
                    || !List.of("goto", "stop").contains(order.action)
                    || !Double.isFinite(order.x) || !Double.isFinite(order.y) || !Double.isFinite(order.z)
                    || Math.abs(order.x) > 30_000_000 || Math.abs(order.z) > 30_000_000
                    || order.y < -128 || order.y > 512) return false;
            incoming = order;
            receivedAt = System.nanoTime();
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static String permission() {
        String name = System.getProperty("botfury.advancedModulesFile", "");
        if (name.isBlank()) return "";
        try {
            String value = Files.readString(Path.of(name)).strip();
            return value.equals("movement") || value.equals("teleport") ? value : "";
        } catch (IOException | RuntimeException error) {
            return "";
        }
    }

    static void activate(MinecraftClient client, String action) {
        if (client.player == null) return;
        if (!action.equals("stop") && permission().isEmpty()) {
            client.player.sendMessage(Text.literal("Confirm permission in BotFury Dashboard → Settings → Advanced modules."), true);
            return;
        }
        if (action.equals("stop") || (action.equals("orbit") && mode.equals("orbit"))) {
            stop();
            client.player.sendMessage(Text.literal("Local formation stopped."), true);
            return;
        }
        mode = action;
        castTick = tick;
        step = 0;
        client.player.sendMessage(Text.literal(action.equals("orbit") ? "Saturn orbit requested." : "Scatter requested."), true);
    }

    static void tick(MinecraftClient client) {
        ++tick;
        local = snapshot(client);
        if (local == null) {
            stop();
            incoming = null;
            cancelPath();
            return;
        }

        applyOrder(client);
        if (mode.isEmpty() || tick % 10 != 0) return;
        String consent = permission();
        if (consent.isEmpty() || (mode.equals("scatter") && tick - castTick > 240)) {
            stop();
            return;
        }
        if (tick % 20 == 0 || tick - castTick <= 20) discover();
        List<Map.Entry<Integer, Peer>> bots = PEERS.entrySet().stream()
                .filter(entry -> sameWorld(entry.getValue())
                        && System.nanoTime() - entry.getValue().time < Duration.ofSeconds(4).toNanos())
                .sorted(Map.Entry.comparingByKey()).toList();
        boolean teleport = consent.equals("teleport") && client.player.hasPermissionLevel(2);
        if (mode.equals("orbit") && (teleport || tick % 40 == 0)) ++step;
        for (int index = 0; index < bots.size(); ++index) {
            var entry = bots.get(index);
            Peer bot = entry.getValue();
            double distance = new Vec3d(bot.x, bot.y, bot.z)
                    .squaredDistanceTo(local.x, local.y, local.z);
            double angle = 2 * Math.PI * index / bots.size() + (distance <= 100 ? step * 0.30 : 0);
            double radius = mode.equals("scatter") ? 8 + 2 * (index % 3) : 4.5;
            Vec3d goal = new Vec3d(local.x + Math.cos(angle)*radius, local.y,
                    local.z + Math.sin(angle)*radius);
            BlockPos landing = standable(client, BlockPos.ofFloored(goal));
            if (teleport && landing != null && (mode.equals("scatter") || distance <= 100)) {
                if (mode.equals("scatter") && new Vec3d(bot.x, bot.y, bot.z)
                        .squaredDistanceTo(Vec3d.ofBottomCenter(landing)) < 1) continue;
                send(entry.getKey(), new Order(local.server, local.dimension, "stop", 0, 0, 0));
                client.player.networkHandler.sendChatCommand("tp " + bot.name + " "
                        + (landing.getX() + 0.5) + " " + landing.getY() + " " + (landing.getZ() + 0.5));
            } else if (bot.baritone) {
                // The nearest bot client's world is used for pathing, even beyond the controller's loaded chunks.
                send(entry.getKey(), new Order(local.server, local.dimension, "goto", goal.x, goal.y, goal.z));
            }
        }
    }

    private static BlockPos standable(MinecraftClient client, BlockPos center) {
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            BlockPos feet = center.up(dy);
            if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) continue;
            BlockPos below = feet.down();
            if (client.world.getBlockState(below).isSideSolidFullSquare(client.world, below, Direction.UP)
                    && client.world.getBlockState(feet).getCollisionShape(client.world, feet).isEmpty()
                    && client.world.getBlockState(feet.up()).getCollisionShape(client.world, feet.up()).isEmpty()
                    && client.world.getFluidState(feet).isEmpty()
                    && client.world.getFluidState(feet.up()).isEmpty()) return feet;
        }
        return null;
    }

    private static synchronized void applyOrder(MinecraftClient client) {
        Order order = incoming;
        if (order == null || !local.server.equals(order.server) || !local.dimension.equals(order.dimension)
                || order.action.equals("stop")
                || System.nanoTime() - receivedAt > Duration.ofSeconds(4).toNanos()) {
            incoming = null;
            cancelPath();
            return;
        }
        if (tick % 10 != 0 || !BaritoneClient.available()) return;
        BlockPos center = BlockPos.ofFloored(order.x, order.y, order.z);
        BlockPos supported = standable(client, center);
        BlockPos goal = supported == null ? center : supported;
        if (goal.equals(lastGoal) && tick - lastGoalTick < 100) return;
        if (!pathOwned) {
            try {
                savedBlocks = new BaritoneBlocks();
                savedBlocks.disable();
                pathOwned = true;
            } catch (ReflectiveOperationException | LinkageError error) {
                return;
            }
        }
        BaritoneClient.execute("goto " + goal.getX() + " " + goal.getY() + " " + goal.getZ());
        lastGoal = goal;
        lastGoalTick = tick;
    }

    private static final class BaritoneBlocks {
        private final Object breaking;
        private final Object placing;
        private final java.lang.reflect.Field value;
        private final Object originalBreak;
        private final Object originalPlace;

        BaritoneBlocks() throws ReflectiveOperationException {
            Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
            breaking = settings.getClass().getField("allowBreak").get(settings);
            placing = settings.getClass().getField("allowPlace").get(settings);
            value = breaking.getClass().getField("value");
            originalBreak = value.get(breaking);
            originalPlace = value.get(placing);
        }

        void disable() throws IllegalAccessException {
            value.set(breaking, false);
            value.set(placing, false);
        }

        void restore() {
            try {
                value.set(breaking, originalBreak);
                value.set(placing, originalPlace);
            } catch (IllegalAccessException error) {
                System.err.println("[BotFury] Could not restore Baritone settings");
            }
        }
    }

    private static void cancelPath() {
        if (!pathOwned) return;
        pathOwned = false;
        lastGoal = null;
        BaritoneClient.execute("stop");
        savedBlocks.restore();
        savedBlocks = null;
    }

    private static Peer snapshot(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        if (info == null || client.world == null || client.player == null) return null;
        ServerAddress address = ServerAddress.parse(info.address);
        String host = address.getAddress().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("host.containers.internal")) host = "local";
        Vec3d pos = client.player.getPos();
        return new Peer(host + ":" + address.getPort(), client.world.getRegistryKey().getValue().toString(),
                client.player.getGameProfile().getName(), pos.x, pos.y, pos.z,
                BaritoneClient.available(), System.nanoTime());
    }

    private static boolean sameWorld(Peer peer) {
        Peer here = local;
        return here != null && peer.server != null && peer.dimension != null && peer.name != null
                && peer.name.matches("[A-Za-z0-9_]{1,16}") && !here.name.equalsIgnoreCase(peer.name)
                && here.server.equals(peer.server) && here.dimension.equals(peer.dimension);
    }

    private static void discover() {
        int base = Integer.getInteger("botfury.formationBasePort", 8765);
        int count = Math.min(32, Integer.getInteger("botfury.formationPortCount", 10));
        IntStream.range(0, Math.max(0, count)).map(offset -> base + offset)
                .filter(port -> port >= 1024 && port <= 65535)
                .forEach(port -> HTTP.sendAsync(request(port).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> {
                            try {
                                Peer peer = response.statusCode() == 200 ? JSON.fromJson(response.body(), Peer.class) : null;
                                if (peer != null && sameWorld(peer)) {
                                    PEERS.put(port, new Peer(peer.server, peer.dimension, peer.name,
                                            peer.x, peer.y, peer.z, peer.baritone, System.nanoTime()));
                                } else PEERS.remove(port);
                            } catch (RuntimeException error) {
                                PEERS.remove(port);
                            }
                        }).exceptionally(error -> null));
    }

    private static HttpRequest.Builder request(int port) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/local-formation"))
                .timeout(Duration.ofMillis(550));
    }

    private static void send(int port, Order order) {
        HTTP.sendAsync(request(port).POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(order))).build(),
                HttpResponse.BodyHandlers.discarding()).exceptionally(error -> null);
    }

    private static void stop() {
        if (mode.isEmpty()) return;
        mode = "";
        Peer here = local;
        if (here != null) PEERS.forEach((port, peer) -> {
            if (sameWorld(peer)) send(port, new Order(here.server, here.dimension, "stop", 0, 0, 0));
        });
        PEERS.clear();
    }
}
