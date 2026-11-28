package com.botfury.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;

public final class BenchmarkInput implements ClientModInitializer {
    private static final Path COMMAND = Path.of("benchmark-order.txt");
    private static final String STOCK = "bf output manifest minecraft:dirt=320 minecraft:cobblestone=192 any_logs=128 minecraft:diamond=128";

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || ClientCommandInternals.getActiveDispatcher() == null
                    || !Files.isRegularFile(COMMAND)) return;
            try {
                String command = Files.readString(COMMAND).strip();
                Files.delete(COMMAND);
                if (command.equals(STOCK)
                        || command.matches("bf project build village-homestead\\.nbt -?\\d+ -?\\d+ -?\\d+ supplied")) {
                    ClientCommandInternals.executeCommand(command);
                }
            } catch (IOException error) {
                System.err.println("Cannot read the local benchmark order: " + error.getMessage());
            }
        });
    }
}
