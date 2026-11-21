package com.botfury;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;

public class BotFuryMod implements ClientModInitializer {
    private static final boolean MANAGED_HTTP = Boolean.getBoolean("botfury.managedHttp");
    private HttpServer server;
    private static volatile boolean ready;
    public static volatile boolean renderEnabled = true;
    private static boolean started = false;
    private static int httpPort = -1;
    private static final String runtimeId = UUID.randomUUID().toString();
    private static volatile String statusSnapshot = "{\"ready\":false,\"connected\":false,\"protocol\":3}";
    private static int snapshotTicks;
    private static final SequenceHistory COMMAND_SEQUENCES = new SequenceHistory();
    private static final long startedAtMillis = System.currentTimeMillis();
    private static boolean autoJoinRegistered = false;
    private static int autoJoinTicks = 0;
    private static int autoJoinAttempts = 0;
    private static int lastAutoJoinAttemptTick = 0;
    private static final int MAX_AUTO_JOIN_ATTEMPTS = 12;

    @Override
    public void onInitializeClient() {
        renderEnabled = !Boolean.parseBoolean(System.getProperty("botfury.disableRendering", "false"));
        FormationClient.register();
        if (Boolean.parseBoolean(System.getProperty("botfury.httpEnabled", "true"))) startHttp();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++snapshotTicks % 5 == 0) updateStatus(client);
            if (client.getOverlay() != null) return;
            if (MANAGED_HTTP && client.currentScreen instanceof AccessibilityOnboardingScreen) {
                client.options.onboardAccessibility = false;
                client.options.write();
                client.setScreen(new TitleScreen());
            }
            if (client.currentScreen instanceof TitleScreen || client.world != null) ready = true;
        });
        registerAutoJoin();
    }

    private void startHttp() {
      if (started) {
        System.out.println("[BotFury] HTTP server already started, skipping");
        return;
      }

      int requestedPort = parseIntProperty("botfury.port", 8765);
      int retries = Math.max(0, parseIntProperty("botfury.portRetries", 8));
      boolean strictPort = Boolean.parseBoolean(System.getProperty("botfury.strictPort", "false"));

      for (int attempt = 0; attempt <= retries; attempt++) {
        int port = requestedPort + attempt;
        try {
          server = HttpServer.create(new InetSocketAddress(System.getProperty("botfury.httpBindAddress", "0.0.0.0"), port), 0);
          server.createContext("/command", this::handleCommand);
          server.createContext("/status", this::handleStatus);
          server.createContext("/events", this::handleEvents);
          server.createContext("/mining-claim", MiningBroker::handle);
          server.createContext("/bots", this::handleBots);
          if (MANAGED_HTTP) server.createContext("/local-formation", this::handleLocalFormation);
          server.setExecutor(Executors.newCachedThreadPool());
          server.start();

          started = true;
          httpPort = port;

          System.out.println("[BotFury] HTTP server started on port " + port);
          return;
        } catch (IOException e) {
          if (strictPort || attempt == retries) {
            System.err.println("[BotFury] Failed to start HTTP server on port " + port);
            e.printStackTrace();
            return;
          }
          System.err.println("[BotFury] Port " + port + " unavailable, trying " + (port + 1));
        }
      }
    }

    private static int parseIntProperty(String key, int fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void registerAutoJoin() {
        if (autoJoinRegistered) return;
        autoJoinRegistered = true;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || !ready || client.getOverlay() != null) return;
            if (!Boolean.parseBoolean(System.getProperty("botfury.autoJoin", "false"))) return;
            if (client.getNetworkHandler() != null || client.world != null) return;
            if (client.currentScreen instanceof ConnectScreen) return;

            String target = configuredServerTarget();
            if (target.isBlank()) return;

            ++autoJoinTicks;
            if (autoJoinTicks < 40 || client.currentScreen == null) return;
            if (autoJoinAttempts >= MAX_AUTO_JOIN_ATTEMPTS) return;
            if (autoJoinAttempts > 0 && autoJoinTicks - lastAutoJoinAttemptTick < 200) return;

            ++autoJoinAttempts;
            lastAutoJoinAttemptTick = autoJoinTicks;
            connectToServer(client, target);
            System.out.println("[BotFury] Auto-joining " + target + " (attempt " + autoJoinAttempts + ")");
        });
    }

    private static String configuredServerTarget() {
        String server = firstNonBlank(
                System.getProperty("botfury.server"),
                System.getProperty("botfury.serverIp"),
                "");
        if (server.isBlank()) return "";

        int port = parseIntProperty("botfury.serverPort", 25565);
        if (server.indexOf(':') >= 0 || port == 25565) {
            return server.trim();
        }
        return server.trim() + ":" + port;
    }

    static int controlPort() { return httpPort; }
    public static boolean cancelFleetPush(net.minecraft.entity.Entity first, net.minecraft.entity.Entity second) {
        var local=MinecraftClient.getInstance().player;
        if(!(first instanceof net.minecraft.entity.player.PlayerEntity a)||!(second instanceof net.minecraft.entity.player.PlayerEntity b))return false;
        return BotCollisionPolicy.cancel(first==local,second==local,a.getGameProfile().getName(),b.getGameProfile().getName());
    }
    public static void onPositionCorrection() { LocalFormation.corrected(); }

    private static void updateStatus(MinecraftClient client) {
        var status = new java.util.LinkedHashMap<String, Object>();
        status.put("connected", client.getNetworkHandler() != null && client.world != null);
        status.put("ready", ready);
        status.put("renderEnabled", renderEnabled);
        status.put("player", client.player == null ? "None" : client.player.getGameProfile().getName());
        status.put("offlineUsername", firstNonBlank(System.getProperty("botfury.offlineUsername"), System.getProperty("botfury.username"), ""));
        status.put("httpPort", httpPort);
        status.put("runtimeId", runtimeId);
        status.put("build", "0.3.3");
        status.put("managed", MANAGED_HTTP);
        status.put("miningTask", MiningTask.status());
        if(LocalFormation.here()!=null){status.put("server",LocalFormation.here().server());status.put("dimension",LocalFormation.here().dimension());}
        status.put("uptimeMillis", System.currentTimeMillis() - startedAtMillis);
        status.put("inventoryTask", BotInventory.status());
        status.put("friends", BotFriends.names());
        status.put("lastEvent", BotEvents.last);
        status.put("lastEventLevel", BotEvents.lastLevel);
        status.put("coordinatedMining", true);
        status.put("taskDefenseCapable", true);
        status.put("taskDefenseEnabled", TaskDefense.enabled());
        if (client.player != null) {
            var items = new java.util.TreeMap<String, Integer>();
            var inventory = client.player.getInventory();
            for (int slot=0;slot<inventory.size();slot++) {
                var stack=inventory.getStack(slot);
                if(!stack.isEmpty())items.merge(net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString(),stack.getCount(),Integer::sum);
            }
            status.put("items", items);
            status.put("health", client.player.getHealth());
            status.put("maxHealth", client.player.getMaxHealth());
            status.put("x", client.player.getX()); status.put("y", client.player.getY()); status.put("z", client.player.getZ());
            status.put("alive", client.player.isAlive());
        }
        status.putAll(LocalFormation.telemetry());
        statusSnapshot = new com.google.gson.Gson().toJson(status);
    }
    private void handleStatus(HttpExchange exchange) throws IOException {
        if (rejectHttpRequest(exchange)) return;
        sendResponse(exchange, 200, "application/json", statusSnapshot);
    }
    private void handleBots(HttpExchange exchange) throws IOException {
        if(rejectHttpRequest(exchange))return;
        sendResponse(exchange,200,"application/json",BotTracker.json());
    }
    private void handleEvents(HttpExchange exchange) throws IOException {
        if (rejectHttpRequest(exchange)) return;
        long after = 0;
        String query = exchange.getRequestURI().getQuery();
        if ("tail".equals(query)) { sendResponse(exchange, 200, "application/json", BotEvents.tailJson()); return; }
        try { if (query != null && query.startsWith("after=")) after = Long.parseLong(query.substring(6)); }
        catch (NumberFormatException error) { sendResponse(exchange, 400, "text/plain", "Invalid event cursor"); return; }
        sendResponse(exchange, 200, "application/json", BotEvents.json(after));
    }

    private void handleLocalFormation(HttpExchange exchange) throws IOException {
        if (rejectHttpRequest(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 200, "application/json", LocalFormation.status());
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Use GET or POST.");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(16385);
        boolean accepted = body.length <= 16384 && LocalFormation.receive(new String(body, StandardCharsets.UTF_8));
        sendResponse(exchange, accepted ? 200 : 400, "text/plain", accepted ? "accepted" : LocalFormation.rejection());
    }

    private void handleCommand(HttpExchange exchange) throws IOException {
        if (rejectHttpRequest(exchange)) return;
        String response;
        int code;
        try {
            var fields = CommandQuery.parse(exchange.getRequestURI().getRawQuery());
            if (!fields.containsKey("cmd")) throw new IllegalArgumentException("Missing cmd");
            if (fields.containsKey("sequence") || fields.containsKey("session")) {
                long sequence = Long.parseLong(fields.getOrDefault("sequence", "0"));
                if (!COMMAND_SEQUENCES.accept(fields.get("session"), sequence)) {
                    sendResponse(exchange, 409, "text/plain", "error: stale command was discarded"); return;
                }
            }
            response = executeCommand(fields.get("cmd"));
            code = response.startsWith("error:") ? 400 : 200;
        } catch (IllegalArgumentException error) { response = "error: " + error.getMessage(); code = 400; }
        if (!response.startsWith("{")) BotEvents.add(code == 200 ? "info" : "error", "command", response);
        sendResponse(exchange, code, "text/plain; charset=utf-8", response);
    }

    private boolean rejectHttpRequest(HttpExchange exchange) throws IOException {
        if (MANAGED_HTTP) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host == null || !host.matches("(127\\.0\\.0\\.1|localhost)(:[0-9]+)?")
                    || exchange.getRequestHeaders().containsKey("Origin")
                    || exchange.getRequestHeaders().containsKey("Sec-Fetch-Mode")) {
                sendResponse(exchange, 403, "text/plain; charset=utf-8", "Local BotFury controller required.");
                return true;
            }
            return false;
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (!MANAGED_HTTP) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        }
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String executeCommand(String cmd) {
        if (cmd == null || cmd.isBlank()) {
            return "error: empty command";
        }

        if (cmd.length() > 2048) return "error: command is too long";
        cmd = cmd.trim();
        MinecraftClient client = MinecraftClient.getInstance();
        if (cmd.equals("status")) return statusSnapshot;
        if (cmd.equals("control:stop") || cmd.equals("control:resume")) {
            boolean stop = cmd.endsWith("stop");
            client.execute(() -> {
                if (stop) { LocalFormation.pauseMovement(); BotInventory.hold(); }
                else { MiningTask.stop(); BotInventory.resume(); }
                BotEvents.add("info", "control", stop ? "Paused bot tasks" : "Resumed formation orders");
            });
            return stop ? "Pause queued" : "Resume queued";
        }
        if(cmd.startsWith("tracking:")) {
            String[] parts=cmd.substring(9).split("\\|",-1);
            if(parts.length!=2||!java.util.Set.of("recall","hold","resume","render","select").contains(parts[0]))return "error: invalid tracking action";
            int port;try{port=Integer.parseInt(parts[1]);}catch(NumberFormatException e){return "error: invalid bot port";}
            if(BotTracker.get(port)==null)return "error: unknown tracked bot";
            client.execute(()->{if(parts[0].equals("select"))BotTracker.select(port);else if(parts[0].equals("render"))BotTracker.command(port,"toggle_render");else LocalFormation.manage(port,parts[0]);});
            return "Bot action queued";
        }
        if(cmd.startsWith("defense:")) {
            try {var config=TaskDefense.parse(cmd.substring(8));client.execute(()->TaskDefense.configure(config));return "{\"ok\":true}";}
            catch(IllegalArgumentException error){return "error: "+error.getMessage();}
        }
        if (cmd.startsWith("collision:")) {
            String[] parts=cmd.substring(10).split("\\|",-1);var here=LocalFormation.here();
            if(parts.length!=3||here==null||!parts[0].equals(here.server())||!parts[1].equals(here.dimension()))return "error: wrong collision roster world";
            try{BotCollisionPolicy.replace(BotFriends.parse(parts[2]));return "{\"ok\":true}";}catch(IllegalArgumentException e){return "error: invalid collision roster";}
        }
        if (cmd.startsWith("mining:")) {
            if(!BaritoneClient.available())return "error: install Baritone for coordinated mining";
            if(client.player==null)return "error: bot is not in game";
            try{var job=MiningTask.parse(cmd.substring(7));client.execute(()->MiningTask.start(client,job));return "Coordinated mining queued";}
            catch(IllegalArgumentException error){return "error: "+error.getMessage();}
        }
        if (cmd.startsWith("formation:")) {
            String action = cmd.substring(10);
            if (!java.util.Set.of("saturn", "orbit", "lightning", "scatter", "stop", "gather", "spread", "status").contains(action))
                return "error: unknown formation action";
            client.execute(() -> ClientActions.action(client, action));
            return "Formation action queued";
        }
        if (cmd.startsWith("friends:")) {
            String[] parts = cmd.substring(8).split(":", 2);
            if (parts.length != 2) return "error: expected friends:add:names or friends:remove:names";
            try {
                BotFriends.edit(parts[0], parts[1]);
                return "Friend list saved";
            } catch (IOException | IllegalArgumentException error) {
                return "error: " + error.getMessage();
            }
        }
        if (cmd.startsWith("inventory:")) {
            try {
                BotInventory.Request request = BotInventory.parse(cmd.substring(10));
                if (client.player == null) return "error: client is not in game";
                client.execute(() -> BotInventory.start(client, request));
                return "Inventory task queued";
            } catch (IllegalArgumentException error) {
                return "error: " + error.getMessage();
            }
        }
        if (cmd.startsWith("chat:")) {
            String msg = cmd.substring(5);
            if (client.player != null) {
                client.execute(() -> client.player.networkHandler.sendChatMessage(msg));
                return "chat sent: " + msg;
            } else {
                return "not in-game";
            }
        } else if (cmd.equals("toggle_render")) {
            renderEnabled = !renderEnabled;
            return "render toggled: " + renderEnabled;
        } else if (cmd.startsWith("baritone:")) {
            if (!BaritoneClient.available()) return "error: Baritone is not installed in this client";
            String command = cmd.substring(9);
            if (client.player == null) return "error: client is not in game";
            client.execute(() -> {
                if(command.equals("pause")){MiningTask.pause(true);BotInventory.pause(true);BaritoneClient.execute("pause");}
                else if(command.equals("resume")){MiningTask.pause(false);BotInventory.pause(false);BaritoneClient.execute("resume");}
                else {LocalFormation.pauseMovement();BotInventory.baritone(command);BaritoneClient.execute(command);}
            });
            return "Baritone command queued";
        } else if (cmd.startsWith("connect:")) {
            String ip = cmd.substring(8).trim();
            if (ip.isEmpty()) {
                return "error: empty server";
            }
            if (!ready) return "error: Minecraft is still loading";
            autoJoinAttempts = MAX_AUTO_JOIN_ATTEMPTS;
            client.execute(() -> connectToServer(client, ip));
            return "connecting to " + ip;
        }
        return "error: unknown command: " + cmd;
    }

    private static void connectToServer(MinecraftClient client, String target) {
        if (client.getOverlay() != null || client.getNetworkHandler() != null || client.world != null) return;

        ServerAddress address = ServerAddress.parse(target);
        ServerInfo serverInfo = new ServerInfo("BotFury Server", target, false);
        ConnectScreen.connect(new TitleScreen(), client, address, serverInfo, false);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    out.append(ch);
                    break;
            }
        }
        return out.toString();
    }
}
