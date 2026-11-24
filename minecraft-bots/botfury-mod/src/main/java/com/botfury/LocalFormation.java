package com.botfury;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import java.util.UUID;
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
    static final int PROTOCOL = 3;
    private static final String SESSION = UUID.randomUUID().toString();
    private static final OrderGate GATE = new OrderGate();
    private static long sequence;
    private static volatile String stage = "Idle";
    private record FleetTask(String command, int requestedAt, BotTracker.Target target) {}
    private static final java.util.ArrayDeque<FleetTask> fleetTasks = new java.util.ArrayDeque<>();
    private static final Map<Integer, java.util.concurrent.CompletableFuture<Void>> commandChains = new ConcurrentHashMap<>();
    private static volatile String rejection = "Unknown local session";
    private static int packetCount, corrections;
    private static final java.util.Set<Integer> incompatiblePorts = ConcurrentHashMap.newKeySet();
    private static int lastCorrection = -1000, correctionStreak, lastPacketTick = -1000;
    private static Object packetPlayer;
    private static volatile Object connection;
    private static String connectionWorld;
    private static final java.util.Set<Integer> discovering = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Order> pending = new ConcurrentHashMap<>();
    private static final java.util.Set<Integer> sending = ConcurrentHashMap.newKeySet();
    private static final Gson JSON = new Gson();
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
        Thread worker = new Thread(task, "BotFury-local-io"); worker.setDaemon(true); return worker;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().executor(IO)
            .connectTimeout(Duration.ofMillis(350)).build();
    private static final java.util.Set<String> knownFleetNames = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Peer> PEERS = new ConcurrentHashMap<>();
    private static volatile Peer local;
    private static volatile Order incoming;
    private static volatile long receivedAt;
    private static String mode = "";
    private static BotTracker.Target formationTarget = new BotTracker.Target(-1, null);
    private static BotTracker.Target saturnTarget;
    private static int tick;
    private static int castTick;
    private static BlockPos lastGoal;
    private static int lastGoalTick;
    private static boolean pathOwned;
    private static BaritoneBlocks savedBlocks;
    private static int step;
    private static UUID strikeTarget;
    private static UUID localStrikeTarget;
    private static Vec3d strikeGoal;
    private static int nextDodge;
    private static int pathUntil;
    private static int nextTeleport;
    private static boolean resumeSaturn;
    private static int targetMissingSince = -1;
    private static Vec3d lastTargetPosition;
    private static Vec3d scatterOrigin;
    private record BotOverride(String action, int started) {}
    private static final Map<Integer,BotOverride> overrides = new java.util.HashMap<>();
    static Peer here() { return local; }
    static void clearManagement() { overrides.clear(); }
    static String overrideLabel(int port) { var value=overrides.get(port);return value==null?"":value.action; }
    static void manage(int port, String action) {
        var bot=BotTracker.get(port);
        if(bot==null||!bot.fresh()){BotEvents.add("warn","bots","Bot is offline");return;}
        if(action.equals("resume")) {overrides.remove(port);BotTracker.command(port,"control:resume");return;}
        if(action.equals("recall")&&(!bot.connected()||!bot.alive()||!bot.sameWorld())) {
            BotEvents.add("warn","bots","Recall needs a live bot in the same server and dimension");return;
        }
        if(action.equals("recall")&&!bot.baritone()&&bot.distance()>4) {
            BotEvents.add("warn","bots","Install Baritone on this bot for long-distance recall");return;
        }
        overrides.put(port,new BotOverride(action,tick));
        if(action.equals("hold"))BotTracker.command(port,"control:stop");
        if(action.equals("recall"))BotTracker.command(port,"control:resume");
    }
    private static void processOverrides() {
        if(tick%10!=0||local==null)return;
        for(var entry:new java.util.ArrayList<>(overrides.entrySet())) {
            if(!entry.getValue().action.equals("recall"))continue;
            var bot=BotTracker.get(entry.getKey());
            if(bot==null||!bot.fresh()||!bot.sameWorld())continue;
            if(bot.distance()<=3) {overrides.remove(entry.getKey());BotEvents.add("info","bots",bot.name()+" recalled");continue;}
            if(tick-entry.getValue().started>2400){overrides.put(entry.getKey(),new BotOverride("hold",tick));BotTracker.command(entry.getKey(),"control:stop");continue;}
            send(entry.getKey(),new Order(local.server,local.dimension,"move",local.x,local.y,local.z,null));
        }
    }

    record Peer(String server, String dimension, String name, double x, double y, double z,
                boolean baritone, long time, int protocol, boolean managed, String state) {
        Peer(String server, String dimension, String name, double x, double y, double z, boolean baritone, long time) {
            this(server, dimension, name, x, y, z, baritone, time, PROTOCOL,
                    Boolean.getBoolean("botfury.managedHttp"), stage);
        }
    }
    record Order(String server, String dimension, String action, double x, double y, double z,
                 String target, String owner, List<String> protectedNames, int protocol, String session, long sequence) {
        Order(String server, String dimension, String action, double x, double y, double z, String target) {
            this(server, dimension, action, x, y, z, target, null, List.of());
        }
        Order(String server, String dimension, String action, double x, double y, double z,
              String target, String owner, List<String> protectedNames) {
            this(server, dimension, action, x, y, z, target, owner, protectedNames, PROTOCOL, SESSION, ++LocalFormation.sequence);
        }
    }

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
                    || !List.of("goto", "move", "orbit", "strike", "stop").contains(order.action)
                    || !Double.isFinite(order.x) || !Double.isFinite(order.y) || !Double.isFinite(order.z)
                    || Math.abs(order.x) > 30_000_000 || Math.abs(order.z) > 30_000_000
                    || order.y < -128 || order.y > 512) return false;
            if (order.protocol != PROTOCOL) { rejection = "Client protocol mismatch; update all clients"; return false; }
            if (permission().isEmpty() && !order.action.equals("stop")) { rejection = "Advanced modules are disabled"; return false; }
            if (order.protectedNames == null || order.protectedNames.size() > 256
                    || order.protectedNames.stream().anyMatch(name -> name == null || !name.matches("[A-Za-z0-9_]{1,16}"))) return false;
            if ((order.action.equals("orbit") || order.action.equals("strike")) && (order.owner == null || !order.owner.matches("[A-Za-z0-9_]{1,16}"))) return false;
            if (order.action.equals("strike")) UUID.fromString(order.target);
            if (!GATE.accept(order.session, order.sequence, System.nanoTime())) {
                rejection = "Stale order or another controller owns the current lease";
                return false;
            }
            incoming = order;
            receivedAt = System.nanoTime();
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static String rejection() { return rejection; }
    static String mode() { return mode.isEmpty() ? incoming == null ? "idle" : incoming.action : mode; }
    static String stage() { return stage; }
    static boolean willResumeSaturn() { return resumeSaturn; }
    static int peers() { return (int) PEERS.values().stream().filter(p -> System.nanoTime() - p.time < 4_000_000_000L).count(); }
    static int incompatible() { return incompatiblePorts.size(); }
    static String targetName() {
        var client = MinecraftClient.getInstance();
        if (client.world == null || strikeTarget == null) return "";
        Entity target = findEntity(client, strikeTarget);
        return target == null ? "Target temporarily unloaded" : target.getName().getString();
    }
    static java.util.Map<String, Object> telemetry() {
        return java.util.Map.of("protocol", PROTOCOL, "formation", mode(), "movementState", stage,
                "peers", peers(), "incompatiblePeers", incompatible(), "packetsSent", packetCount, "corrections", corrections);
    }
    static void corrected() {
        if (tick - lastPacketTick > 20 || packetPlayer != MinecraftClient.getInstance().player) return;
        corrections++;
        correctionStreak = tick - lastCorrection < 100 ? correctionStreak + 1 : 1;
        lastCorrection = tick;
        nextTeleport = tick + Math.min(40, 4 + correctionStreak * 4);
        if (correctionStreak >= 3) pathUntil = tick + 40;
        stage = "Server correction; cooling down";
        BotEvents.add("warn", "movement", stage);
    }
    static Path settingsPath() {
        String name = System.getProperty("botfury.advancedModulesFile", "");
        return name.isBlank() ? net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("botfury-advanced.txt") : Path.of(name);
    }
    static void setPermission(String value) throws IOException {
        Files.createDirectories(settingsPath().toAbsolutePath().getParent());
        Files.writeString(settingsPath(), value);
    }
    static String permission() {
        try {
            String value = Files.readString(settingsPath()).strip();
            return value.equals("movement") || value.equals("teleport") ? value : "";
        } catch (IOException | RuntimeException error) {
            return "";
        }
    }

    static void activate(MinecraftClient client, String action) {
        if (client.player == null) return;
        if (!action.equals("stop") && permission().isEmpty()) {
            client.player.sendMessage(Text.literal("Enable movement with /bf allow packets or /bf allow movement."), true);
            return;
        }
        var target = BotTracker.target();
        if (action.equals("stop") || action.equals("orbit") && mode.equals("orbit") && target.equals(formationTarget)) {
            stop();
            client.player.sendMessage(Text.literal("Local formation stopped."), true);
            return;
        }
        if (BotInventory.active()) BotInventory.resume();
        if (!mode.isEmpty()) stop();
        applyFormationTarget(target);
        resumeSaturn = false;
        mode = action;
        if(action.equals("scatter"))scatterOrigin=client.player.getPos();
        stage = "Finding fleet";
        BotEvents.add("info", "formation", action + " requested");
        discover();
        castTick = tick;
        step = 0;
        client.player.sendMessage(Text.literal(action.equals("orbit") ? "Saturn orbit requested." : "Scatter requested."), true);
    }

    static boolean protectedTarget(LivingEntity target) {
        String name = target.getName().getString();
        var friends = BotFriends.names();
        return !BotFriends.reliable() || friends.stream().anyMatch(name::equalsIgnoreCase)
                || knownFleetNames.stream().anyMatch(name::equalsIgnoreCase)
                || (target instanceof net.minecraft.entity.player.PlayerEntity player && player.isCreative());
    }
    static void strike(MinecraftClient client, LivingEntity target) {
        if (client.player == null || !permission().equals("teleport")) {
            ClientActions.message(client, "Lightning needs packet movement: /bf allow packets."); return;
        }
        if (protectedTarget(target)) { ClientActions.message(client, "That target is protected by your fleet or friend list."); return; }
        if (BotInventory.active()) BotInventory.resume();
        var restore = mode.equals("orbit") ? formationTarget : saturnTarget;
        stop();
        saturnTarget = restore;
        resumeSaturn = restore != null;
        applyFormationTarget(BotTracker.target());
        strikeTarget = target.getUuid();
        lastTargetPosition = target.getPos();
        targetMissingSince = -1;
        mode = "strike";
        stage = "Lightning tracking " + target.getName().getString();
        BotEvents.add("info", "formation", stage);
        discover();
        castTick = tick;
        client.player.sendMessage(Text.literal("Lightning strike requested."), true);
    }

    static void tick(MinecraftClient client) {
        ++tick;
        String currentWorld = client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
        if (connection != client.getNetworkHandler() || !currentWorld.equals(connectionWorld)) {
            connection = client.getNetworkHandler();
            connectionWorld = currentWorld;
            boolean wasActive = !mode.isEmpty() || incoming != null;
            ProjectWorker.suspend(client);
            MiningTask.stop();
            BotCollisionPolicy.replace(java.util.List.of());
            pending.clear();
            stop();
            incoming = null;
            GATE.reset();
            if (wasActive) BotEvents.add("info", "formation", "World or connection changed; formation stopped");
            fleetTasks.clear();
            PEERS.clear();
            knownFleetNames.clear();
            incompatiblePorts.clear();
            overrides.clear();
            cancelPath();
            pathUntil = nextTeleport = 0;
            lastPacketTick = -1000;
            packetPlayer = null;
            correctionStreak = 0;
        }
        if (client.player != null && client.player.isDead()) {
            stage = "Waiting to respawn";
            TaskDefense.evade(client, false);
            if (ProjectWorker.enabled()) ProjectWorker.interrupt(client);
            MiningTask.onDeath();
            cancelPath();
            if (Boolean.getBoolean("botfury.managedHttp") && client.player.deathTime >= 20 && tick % 20 == 0) {
                BotEvents.add("info", "respawn", "Requesting bot respawn");
                client.player.requestRespawn();
                client.setScreen(null);
            }
            return;
        }
        local = snapshot(client);
        if (local == null) {
            stage = "Disconnected";
            TaskDefense.evade(client, false);
            if (BotInventory.active()) BotInventory.disconnected();
            stop();
            incoming = null;
            cancelPath();
            return;
        }

        BotTracker.tick();
        processOverrides();
        processFleetTasks();
        ProjectWorker.restore(client, local);
        if (ProjectWorker.enabled()) {
            cancelPath();
            ProjectWorker.tick(client, local);
            stage = ProjectWorker.status();
            return;
        }
        boolean incomingWork=incoming!=null&&!incoming.action.equals("stop")&&System.nanoTime()-receivedAt<4_000_000_000L;
        if(TaskDefense.evade(client,MiningTask.working()||BotInventory.working()||incomingWork))return;
        if (MiningTask.active()) { cancelPath(); stage=MiningTask.status(); if(!TaskDefense.tick(client,MiningTask.working()))MiningTask.tick(client); return; }
        if (BotInventory.active()) {
            cancelPath();
            stage = BotInventory.status();
            TaskDefense.tick(client,BotInventory.working());
            BotInventory.tick(client);
            return;
        }
        if (!Boolean.getBoolean("botfury.managedHttp") && tick % 20 == 0) discover();
        applyOrder(client);
        if (mode.isEmpty() || tick % 4 != 0) return;
        String consent = permission();
        if (mode.equals("strike") && !consent.equals("teleport")) {
            BotEvents.add("warn", "formation", "Lightning ended because packet movement was disabled");
            finishStrike(client);
            return;
        }
        if (consent.isEmpty() || (mode.equals("scatter") && tick - castTick > 1200)) {
            stop();
            return;
        }
        if (tick % 20 == 0 || tick - castTick <= 20) discover();
        stage = mode.equals("strike") ? "Lightning active" : "Directing " + peers() + " bots";
        List<Map.Entry<Integer, Peer>> bots = PEERS.entrySet().stream()
                .filter(entry -> sameWorld(entry.getValue())
                        && formationTarget.includes(entry.getKey(), entry.getValue().name)
                        && System.nanoTime() - entry.getValue().time < Duration.ofSeconds(4).toNanos())
                .sorted(Map.Entry.comparingByKey()).toList();
        if (mode.equals("strike")) {
            Entity target = findEntity(client, strikeTarget);
            if (target instanceof LivingEntity living) {
                if (!living.isAlive() || protectedTarget(living)) { finishStrike(client); return; }
                lastTargetPosition = target.getPos();
                targetMissingSince = -1;
            } else {
                if (targetMissingSince < 0) targetMissingSince = tick;
                if (tick - targetMissingSince > 200) { finishStrike(client); return; }
            }
            var safe = new java.util.ArrayList<>(BotFriends.names());
            safe.addAll(knownFleetNames);
            if (lastTargetPosition != null) for (var entry : bots) if(!overrides.containsKey(entry.getKey())) send(entry.getKey(),
                    new Order(local.server, local.dimension, "strike", lastTargetPosition.x,
                            lastTargetPosition.y, lastTargetPosition.z, strikeTarget.toString(), local.name, safe));
            return;
        }
        boolean teleport = consent.equals("teleport");
        if (mode.equals("orbit") && (teleport || tick % 40 == 0)) ++step;
        for (int index = 0; index < bots.size(); ++index) {
            var entry = bots.get(index);
            if(overrides.containsKey(entry.getKey()))continue;
            Peer bot = entry.getValue();
            double distance = new Vec3d(bot.x, bot.y, bot.z)
                    .squaredDistanceTo(local.x, local.y, local.z);
            int ring=index/8, ringSize=Math.min(8,bots.size()-ring*8);
            double angle=2*Math.PI*(index%8)/Math.max(1,ringSize)+(distance<=100?step*0.40*(ring%2==0?1:-1):0);
            double radius=mode.equals("scatter")?32+8*(index%4):2.0+ring*1.6;
            Vec3d center=mode.equals("scatter")&&scatterOrigin!=null?scatterOrigin:new Vec3d(local.x,local.y,local.z);
            if(mode.equals("scatter"))angle=2*Math.PI*index/Math.max(1,bots.size());
            Vec3d goal=center.add(Math.cos(angle)*radius,0,Math.sin(angle)*radius);
            BlockPos landing = standable(client, BlockPos.ofFloored(goal));
            if (teleport && landing != null && (mode.equals("scatter") || distance <= 144)) {
                if (mode.equals("scatter") && new Vec3d(bot.x, bot.y, bot.z)
                        .squaredDistanceTo(Vec3d.ofBottomCenter(landing)) < 1) continue;
                var protectedNames = new java.util.ArrayList<>(BotFriends.names());
                protectedNames.addAll(knownFleetNames);
                send(entry.getKey(), new Order(local.server, local.dimension,
                        mode.equals("orbit") ? "orbit" : "move",
                        goal.x, landing.getY(), goal.z, null, local.name, protectedNames));
            } else if (bot.baritone) {
                // The nearest bot client's world is used for pathing, even beyond the controller's loaded chunks.
                if (mode.equals("orbit")) {
                    var safe = new java.util.ArrayList<>(BotFriends.names());
                    safe.addAll(knownFleetNames);
                    send(entry.getKey(), new Order(local.server, local.dimension, "orbit", goal.x, goal.y, goal.z,
                            null, local.name, safe));
                } else send(entry.getKey(), new Order(local.server, local.dimension, "goto", goal.x, goal.y, goal.z, null));
            }
        }
    }

    private static BlockPos standable(MinecraftClient client, BlockPos center) {
        Vec3d found = landing(client, Vec3d.ofBottomCenter(center), Double.POSITIVE_INFINITY);
        return found == null ? null : BlockPos.ofFloored(found);
    }

    /** Finds an actual collision surface, including slabs and stairs, with full body clearance. */
    private static Vec3d landing(MinecraftClient client, Vec3d desired, double maxRange) {
        BlockPos center = BlockPos.ofFloored(desired);
        Vec3d best = null;
        double score = Double.POSITIVE_INFINITY;
        for (int dx : new int[]{0, -1, 1}) for (int dz : new int[]{0, -1, 1}) {
            for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
                BlockPos support = center.add(dx, dy - 1, dz);
                if (!client.world.isChunkLoaded(support.getX() >> 4, support.getZ() >> 4)) continue;
                var state = client.world.getBlockState(support);
                var shape = state.getCollisionShape(client.world, support);
                if (shape.isEmpty() || !client.world.getFluidState(support).isEmpty()
                        || state.isOf(net.minecraft.block.Blocks.MAGMA_BLOCK)
                        || state.isOf(net.minecraft.block.Blocks.CACTUS)
                        || state.isOf(net.minecraft.block.Blocks.CAMPFIRE)
                        || state.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE)) continue;
                Vec3d top = new Vec3d(support.getX() + 0.5,
                        support.getY() + shape.getMax(Direction.Axis.Y) + 0.0001, support.getZ() + 0.5);
                Vec3d delta = top.subtract(client.player.getPos());
                if (delta.lengthSquared() > maxRange * maxRange
                        || (Double.isFinite(maxRange) && Math.abs(delta.y) > 3.001)
                        || !client.world.getWorldBorder().contains(BlockPos.ofFloored(top))) continue;
                var box = client.player.getBoundingBox().offset(delta);
                if (!client.world.isSpaceEmpty(client.player, box)
                        || client.world.isSpaceEmpty(client.player, box.offset(0, -0.03, 0))
                        || !client.world.getFluidState(BlockPos.ofFloored(top)).isEmpty()
                        || !client.world.getOtherEntities(client.player, box,
                                entity -> entity instanceof LivingEntity && entity.isAlive() && !entity.isSpectator()).isEmpty()) continue;
                double cost = top.squaredDistanceTo(desired) + Math.abs(top.y - desired.y) * 0.25;
                if (cost < score) { score = cost; best = top; }
            }
        }
        return best;
    }

    private static synchronized void applyOrder(MinecraftClient client) {
        Order order = incoming;
        if (order == null || permission().isEmpty() || !local.server.equals(order.server) || !local.dimension.equals(order.dimension)
                || order.action.equals("stop")
                || System.nanoTime() - receivedAt > Duration.ofSeconds(4).toNanos()) {
            if (order != null && !order.action.equals("stop")) stage = "Order expired or modules disabled";
            else if (mode.isEmpty()) stage = "Idle";
            incoming = null;
            cancelPath();
            pathUntil = 0;
            localStrikeTarget = null;
            strikeGoal = null;
            return;
        }
        if (order.action.equals("move") || order.action.equals("orbit")) {
            travel(client, new Vec3d(order.x, order.y, order.z));
            if (order.action.equals("orbit")) attackNearby(client, order);
            return;
        }
        if (order.action.equals("strike")) {
            Entity entity;
            try { entity = findEntity(client, UUID.fromString(order.target)); }
            catch (IllegalArgumentException | NullPointerException error) { return; }
            cancelPath();
            if (!(entity instanceof LivingEntity target)) {
                teleportToward(client, new Vec3d(order.x, order.y, order.z));
                return;
            }
            if (!target.isAlive() || target == client.player
                    || BotFriends.protectedName(target.getName().getString(), order.owner, order.protectedNames)
                    || BotFriends.names().stream().anyMatch(target.getName().getString()::equalsIgnoreCase)
                    || !BotFriends.reliable()) { cancelPath(); return; }
            if (!target.getUuid().equals(localStrikeTarget)) {
                localStrikeTarget = target.getUuid();
                strikeGoal = null;
                nextDodge = 0;
            }
            if (client.player.squaredDistanceTo(target) > 25) {
                teleportToward(client, target.getPos());
            } else {
                if (tick >= nextDodge || strikeGoal == null
                        || strikeGoal.squaredDistanceTo(target.getPos()) > 16) {
                    strikeGoal = dodgeGoal(client, target);
                    nextDodge = tick + 4;
                }
                if (strikeGoal != null) teleportToward(client, strikeGoal);
            }
            if (client.player.squaredDistanceTo(target) <= 9 && client.player.canSee(target)
                    && client.player.getAttackCooldownProgress(0.5f) >= 0.95f) {
                punch(client, target);
            }
            return;
        }
        pathTo(client, new Vec3d(order.x, order.y, order.z));
    }

    private static Vec3d dodgeGoal(MinecraftClient client, LivingEntity target) {
        int phase = tick / 4;
        double offset = (client.player.getUuid().hashCode() & 255) * Math.PI / 128;
        for (int attempt = 0; attempt < 16; ++attempt) {
            // Alternating sides of an advancing ring produce a repeatable zigzag.
            double angle = offset + phase * 0.55 + (phase % 2 == 0 ? 0.8 : -0.8) + attempt * 0.2;
            double radius = phase % 2 == 0 ? 1.5 : 2.2;
            Vec3d candidate = target.getPos().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            candidate = landing(client, candidate, Double.POSITIVE_INFINITY);
            if (candidate == null || Math.abs(candidate.y - client.player.getY()) > 3.001) continue;
            var box = client.player.getBoundingBox().offset(candidate.subtract(client.player.getPos()));
            if (!client.world.isSpaceEmpty(client.player, box)
                    || !client.world.getOtherEntities(client.player, box,
                            other -> other instanceof LivingEntity && other.isAlive()).isEmpty()) continue;
            var sight = client.world.raycast(new net.minecraft.world.RaycastContext(
                    candidate.add(0, client.player.getStandingEyeHeight(), 0), target.getEyePos(),
                    net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, client.player));
            if (sight.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return candidate;
        }
        return null;
    }

    private static void teleportToward(MinecraftClient client, Vec3d destination) {
        cancelPath();
        if (!permission().equals("teleport")) { stage = "Lightning needs packet movement enabled"; return; }
        if (tick < nextTeleport) return;
        Vec3d current = client.player.getPos();
        Vec3d direct = landing(client, destination, 4);
        if (direct != null && direct.squaredDistanceTo(destination) < 2 && moveStep(client, direct)) {
            nextTeleport = tick + 4;
            return;
        }
        Vec3d delta = destination.subtract(current);
        Vec3d best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (double angle : new double[]{0, 0.6, -0.6, 1.2, -1.2}) {
            double cos = Math.cos(angle), sin = Math.sin(angle);
            Vec3d heading = new Vec3d(delta.x * cos - delta.z * sin, delta.y, delta.x * sin + delta.z * cos).normalize();
            Vec3d candidate = current.add(heading.multiply(Math.min(3, delta.length())));
            Vec3d top = landing(client, candidate, 4);
            if (top == null || top.squaredDistanceTo(current) < 0.05) continue;
            double cost = top.squaredDistanceTo(destination);
            if (cost < bestCost) { bestCost = cost; best = top; }
        }
        if (best != null && moveStep(client, best)) nextTeleport = tick + 4;
        else stage = "No clear packet landing; holding position";
    }

    private static void attackNearby(MinecraftClient client, Order order) {
        defend(client,order.owner,order.protectedNames);
    }
    static boolean defend(MinecraftClient client,String owner,java.util.Collection<String> protectedNames) {
        return defend(client, owner, protectedNames, other -> true);
    }

    static boolean defend(MinecraftClient client, String owner, java.util.Collection<String> protectedNames,
                          java.util.function.Predicate<LivingEntity> threat) {
        var target = defenseTarget(client, owner, protectedNames, threat);
        if (target != null && client.player.getAttackCooldownProgress(0.5f) >= 0.95f) {
            punch(client, target);
            return true;
        }
        return false;
    }

    static LivingEntity defenseTarget(MinecraftClient client, String owner, java.util.Collection<String> protectedNames,
                                      java.util.function.Predicate<LivingEntity> threat) {
        if (owner == null || protectedNames == null || !BotFriends.reliable()) return null;
        var safe = new java.util.HashSet<String>(BotFriends.names());
        protectedNames.forEach(name -> safe.add(name.toLowerCase(java.util.Locale.ROOT)));
        safe.add(owner.toLowerCase(java.util.Locale.ROOT));
        return client.world.getEntitiesByClass(LivingEntity.class,
                client.player.getBoundingBox().expand(3), other -> other != client.player && threat.test(other)
                        && other.isAlive() && !other.isSpectator() && !other.isTeammate(client.player)
                        && !BotFriends.protectedName(other.getName().getString(), owner, safe)
                        && !BotCollisionPolicy.known(other.getName().getString())
                        && !(other instanceof net.minecraft.entity.player.PlayerEntity player && player.isCreative())
                        && client.player.squaredDistanceTo(other) <= 9 && client.player.canSee(other)).stream()
                .min(java.util.Comparator.comparingDouble(client.player::squaredDistanceTo)).orElse(null);
    }

    static void punch(MinecraftClient client, LivingEntity target) {
        if (client.interactionManager == null) return;
        client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
        client.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                client.player.getYaw(), client.player.getPitch(), client.player.isOnGround()));
        client.interactionManager.attackEntity(client.player, target);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private static void travel(MinecraftClient client, Vec3d destination) {
        Vec3d top = landing(client, destination, 4);
        double distance = top == null ? Double.POSITIVE_INFINITY
                : client.player.getPos().squaredDistanceTo(top);
        // Only hand off once the actual supported destination is within packet range.
        if (!permission().equals("teleport") || tick < pathUntil || distance > 16) {
            pathTo(client, destination);
            return;
        }
        if (tick < nextTeleport) return;
        if (!moveStep(client, top)) {
            pathUntil = tick + 20;
            pathTo(client, destination);
            return;
        }
        cancelPath();
        nextTeleport = tick + 4;
    }

    private static void pathTo(MinecraftClient client, Vec3d destination) {
        if (!BaritoneClient.available()) { stage = "Need Baritone for approach"; return; }
        if (tick % 10 != 0) return;
        stage = "Baritone approach";
        BlockPos center = BlockPos.ofFloored(destination);
        BlockPos supported = standable(client, center);
        BlockPos goal = supported == null ? center : supported;
        if (goal.equals(lastGoal) && tick - lastGoalTick < 100) return;
        if (!pathOwned) {
            try {
                savedBlocks = new BaritoneBlocks();
                savedBlocks.approach();
                pathOwned = true;
            } catch (ReflectiveOperationException | LinkageError error) {
                if (savedBlocks != null) savedBlocks.restore();
                savedBlocks = null;
                stage = "Could not configure Baritone approach";
                BotEvents.add("warn", "movement", stage);
                return;
            }
        }
        if (!BaritoneClient.execute("goto " + goal.getX() + " " + goal.getY() + " " + goal.getZ())) {
            stage = "Baritone rejected approach";
            cancelPath();
            return;
        }
        lastGoal = goal;
        lastGoalTick = tick;
    }

    private static Entity findEntity(MinecraftClient client, UUID id) {
        if (id == null) return null;
        for (Entity entity : client.world.getEntities()) if (id.equals(entity.getUuid())) return entity;
        return null;
    }

    static boolean canEvade(){return permission().equals("teleport")&&tick>=nextTeleport;}
    static Vec3d evasionLanding(MinecraftClient client,Vec3d desired){return landing(client,desired,4);}
    static boolean evadeTo(MinecraftClient client,Vec3d top){
        if(!canEvade()||!moveStep(client,top))return false;
        nextTeleport=tick+4;stage="Evading creeper / projectile";return true;
    }
    private static boolean moveStep(MinecraftClient client, Vec3d top) {
        Vec3d current = client.player.getPos();
        Vec3d delta = top.subtract(current);
        if (delta.lengthSquared() > 16 || Math.abs(delta.y) > 3.001) return false;
        if (delta.lengthSquared() < 0.0025) return true;
        var box = client.player.getBoundingBox().offset(delta);
        if (!client.world.getWorldBorder().contains(BlockPos.ofFloored(top))
                || !client.world.isSpaceEmpty(client.player, box)
                || client.world.isSpaceEmpty(client.player, box.offset(0, -0.02, 0))) return false;
        client.player.setVelocity(Vec3d.ZERO);
        client.player.updatePosition(top.x, top.y, top.z);
        packetCount++;
        lastPacketTick = tick;
        packetPlayer = client.player;
        stage = "Packet movement";
        client.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                top.x, top.y, top.z, true));
        return true;
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

        void approach() throws IllegalAccessException {
            value.set(breaking, true);
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

    static Peer snapshot(MinecraftClient client) {
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
        Peer origin = local;
        Object originConnection = connection;
        if (origin == null) return;
        int base = Integer.getInteger("botfury.formationBasePort", 8765);
        int count = Math.min(32, Integer.getInteger("botfury.formationPortCount", 10));
        IntStream.range(0, Math.max(0, count)).map(offset -> base + offset)
                .filter(port -> port >= 1024 && port <= 65535 && discovering.add(port))
                .forEach(port -> HTTP.sendAsync(request(port).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> {
                            try {
                                if (connection != originConnection || local == null || !origin.server.equals(local.server) || !origin.dimension.equals(local.dimension)) return;
                                Peer peer = response.statusCode() == 200 ? JSON.fromJson(response.body(), Peer.class) : null;
                                if (peer != null && sameWorld(peer)) knownFleetNames.add(peer.name);
                                if (peer != null && sameWorld(peer) && peer.managed && peer.protocol == PROTOCOL) {
                                    incompatiblePorts.remove(port);
                                    knownFleetNames.add(peer.name);
                                    PEERS.put(port, new Peer(peer.server, peer.dimension, peer.name,
                                            peer.x, peer.y, peer.z, peer.baritone, System.nanoTime(), peer.protocol, peer.managed, peer.state));
                                } else {
                                    if (peer != null && sameWorld(peer) && peer.protocol != PROTOCOL) incompatiblePorts.add(port);
                                    else incompatiblePorts.remove(port);
                                    PEERS.remove(port);
                                }
                            } catch (RuntimeException error) { PEERS.remove(port); }
                        }).whenComplete((result, error) -> {
                            discovering.remove(port);
                            if (error != null) { PEERS.remove(port); incompatiblePorts.remove(port); }
                        }));
    }

    private static HttpRequest.Builder request(int port) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/local-formation"))
                .timeout(Duration.ofMillis(550));
    }

    private static void send(int port, Order order) {
        pending.put(port, order);
        drain(port);
    }
    private static void drain(int port) {
        if (!sending.add(port)) return;
        Order order = pending.remove(port);
        if (order == null) { sending.remove(port); return; }
        HTTP.sendAsync(request(port).POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(order))).build(),
                HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            if (error != null || response.statusCode() != 200) {
                String problem = error != null ? "Formation connection failed" : "Order rejected: " + response.body();
                if (tick % 20 < 4) BotEvents.add("warn", "fleet", problem);
            }
            sending.remove(port);
            if (pending.containsKey(port)) drain(port);
        });
    }

    static void pauseMovement() {
        ProjectWorker.stop(MinecraftClient.getInstance());
        clearMovement();
    }

    static void clearMovement() {
        TaskDefense.cancelEvasion();
        overrides.clear();
        stop();
        incoming = null;
        cancelPath();
        BaritoneClient.execute("stop");
        MiningTask.stop();
        fleetTasks.clear();
    }
    static void cancelStrike(MinecraftClient client) { if (mode.equals("strike")) finishStrike(client); }
    private static void applyFormationTarget(BotTracker.Target target) {
        formationTarget = target;
        overrides.entrySet().removeIf(entry -> {
            var bot = BotTracker.get(entry.getKey());
            return bot != null && target.includes(bot.port(), bot.name()) && !entry.getValue().action.equals("hold");
        });
        fleetCommand("control:resume", target);
    }

    static void fleetCommand(String command) {
        fleetCommand(command, new BotTracker.Target(-1, null));
    }

    static void fleetCommand(String command, BotTracker.Target target) {
        if (fleetTasks.size() >= 16) { BotEvents.add("warn", "fleet", "Too many queued tasks; wait for delivery"); return; }
        fleetTasks.addLast(new FleetTask(command, tick, target));
        discover();
        BotEvents.add("info", "fleet", "Fleet task queued");
    }
    private static void processFleetTasks() {
        if (fleetTasks.isEmpty()) return;
        FleetTask task = fleetTasks.peekFirst();
        if (tick - task.requestedAt < 4) return;
        var ports = PEERS.entrySet().stream().filter(entry -> System.nanoTime() - entry.getValue().time < 4_000_000_000L)
                .filter(entry -> task.target.includes(entry.getKey(), entry.getValue().name))
                .map(Map.Entry::getKey).toList();
        if (ports.isEmpty() && tick - task.requestedAt < 30) return;
        fleetTasks.removeFirst();
        if (ports.isEmpty()) { BotEvents.add("warn", "fleet", "No compatible local bots found"); return; }
        for (int port : ports) commandChains.compute(port, (key, previous) -> {
            var start = previous == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null) : previous;
            return start.handle((result, error) -> null).thenCompose(ignored -> HTTP.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/command?cmd="
                            + java.net.URLEncoder.encode(task.command, java.nio.charset.StandardCharsets.UTF_8)))
                            .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString()))
                    .handle((response, error) -> {
                        if (error != null) BotEvents.add("error", "fleet", "Bot at " + port + " did not accept the task");
                        else BotEvents.add(response.statusCode() == 200 ? "info" : "error", "fleet", "Bot " + port + ": " + response.body());
                        return null;
                    });
        });
    }

    private static void finishStrike(MinecraftClient client) {
        if (resumeSaturn && client.player != null && client.player.isAlive() && !permission().isEmpty()) {
            resumeSaturn = false;
            strikeTarget = null;
            var restore = saturnTarget;
            stop();
            applyFormationTarget(restore);
            mode = "orbit";
            castTick = tick;
            step = 0;
            discover();
        } else stop();
    }

    private static void stop() {
        BaritoneClient.execute("stop");
        resumeSaturn = false;
        saturnTarget = null;
        if (mode.isEmpty()) { incoming = null; cancelPath(); BaritoneClient.execute("stop"); stage = "Idle"; return; }
        mode = "";
        stage = "Idle";
        BotEvents.add("info", "formation", "Stopped");
        strikeTarget = null;
        Peer here = local;
        if (here != null) PEERS.forEach((port, peer) -> {
            if (sameWorld(peer) && formationTarget.includes(port, peer.name)) send(port, new Order(here.server, here.dimension, "stop", 0, 0, 0, null));
        });
        PEERS.clear();
    }
}
