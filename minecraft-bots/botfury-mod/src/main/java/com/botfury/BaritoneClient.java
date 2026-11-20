package com.botfury;

import net.fabricmc.loader.api.FabricLoader;

final class BaritoneClient {
    private BaritoneClient() {}

    static boolean available() {
        return FabricLoader.getInstance().isModLoaded("baritone");
    }

    static boolean execute(String command) {
        if (!available()) return false;
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);
            Class<?> providerType = Class.forName("baritone.api.IBaritoneProvider");
            Object bot = providerType.getMethod("getPrimaryBaritone").invoke(provider);
            Class<?> botType = Class.forName("baritone.api.IBaritone");
            Object manager = botType.getMethod("getCommandManager").invoke(bot);
            Class<?> managerType = Class.forName("baritone.api.command.manager.ICommandManager");
            return (boolean) managerType.getMethod("execute", String.class).invoke(manager, command);
        } catch (ReflectiveOperationException | LinkageError error) {
            System.err.println("[BotFury] Baritone command failed: " + error.getMessage());
            return false;
        }
    }
}
