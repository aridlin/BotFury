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

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (rejectHttpRequest(exchange)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        boolean connected = client.getNetworkHandler() != null && client.world != null;
        String playerName = client.player != null ? client.player.getName().getString() : "None";
        String offlineUsername = firstNonBlank(
                System.getProperty("botfury.offlineUsername"),
                System.getProperty("botfury.username"),
                "");
        long uptimeMillis = System.currentTimeMillis() - startedAtMillis;
        String status = String.format(
                "{\"connected\":%b,\"ready\":%b,\"renderEnabled\":%b,\"player\":\"%s\",\"offlineUsername\":\"%s\",\"httpPort\":%d,\"uptimeMillis\":%d,\"runtimeId\":\"%s\"}",
                connected,
                ready,
                renderEnabled,
                jsonEscape(playerName),
                jsonEscape(offlineUsername),
                httpPort,
                uptimeMillis,
                runtimeId);

        sendResponse(exchange, 200, "application/json", status);
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
        byte[] body = exchange.getRequestBody().readNBytes(1025);
        boolean accepted = body.length <= 1024 && LocalFormation.receive(new String(body, StandardCharsets.UTF_8));
        sendResponse(exchange, accepted ? 200 : 400, "text/plain", accepted ? "accepted" : "unknown local session");
    }

    private void handleCommand(HttpExchange exchange) throws IOException {
        if (rejectHttpRequest(exchange)) return;

        URI requestURI = exchange.getRequestURI();
        String query = requestURI.getQuery();
        String response = "no command";
        int code = 400;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && kv[0].equals("cmd")) {
                    String decodedCmd = URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name());
                    response = executeCommand(decodedCmd);
                    code = response.startsWith("error:") ? 400 : 200;
                    break;
                }
            }
        }

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

        cmd = cmd.trim();
        MinecraftClient client = MinecraftClient.getInstance();
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
            client.execute(() -> BaritoneClient.execute(command));
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
