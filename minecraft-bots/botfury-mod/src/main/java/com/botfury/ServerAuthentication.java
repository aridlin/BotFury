package com.botfury;

import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Optional AuthMe-style commands, scoped to one configured server and a private worker file. */
final class ServerAuthentication {
    private final String server;
    private final String loginPrompt;
    private final String registerPrompt;
    private final String password;
    private boolean loginSent;
    private boolean registerSent;

    private ServerAuthentication(Properties config) {
        server = config.getProperty("server", "").strip();
        loginPrompt = config.getProperty("loginPrompt", "");
        registerPrompt = config.getProperty("registerPrompt", "");
        password = config.getProperty("password", "");
        if (server.isEmpty() || password.length() < 8 || password.length() > 80
                || password.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || (loginPrompt.isBlank() && registerPrompt.isBlank())
                || (!loginPrompt.isEmpty() && loginPrompt.equals(registerPrompt))) {
            throw new IllegalArgumentException("Invalid server authentication configuration");
        }
    }

    static ServerAuthentication load() {
        String path = System.getenv("BOTFURY_SERVER_AUTH_FILE");
        if (path == null || path.isBlank()) return null;
        try (var reader = Files.newBufferedReader(Path.of(path))) {
            Properties config = new Properties();
            config.load(reader);
            return new ServerAuthentication(config);
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("[BotFury] Could not load the worker's server authentication configuration.");
            return null;
        }
    }

    void reset() {
        loginSent = false;
        registerSent = false;
    }

    String redact(String value) {
        return value.replace(password, "[redacted]");
    }

    void handle(MinecraftClient client, String prompt) {
        var entry = client.getCurrentServerEntry();
        var network = client.getNetworkHandler();
        if (entry == null || network == null || !server.equalsIgnoreCase(entry.address)) return;
        if (!registerSent && !registerPrompt.isEmpty() && registerPrompt.equals(prompt)) {
            registerSent = true;
            network.sendChatCommand("register " + password + " " + password);
        } else if (!loginSent && !loginPrompt.isEmpty() && loginPrompt.equals(prompt)) {
            loginSent = true;
            network.sendChatCommand("login " + password);
        }
    }
}
