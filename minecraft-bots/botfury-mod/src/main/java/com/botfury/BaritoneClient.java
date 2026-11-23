package com.botfury;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class BaritoneClient {
    static final int PATH_LIMIT = 160;
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

    /** Read the active and already-planned continuation from the client's tick thread. */
    static List<BlockPos> route() {
        if (!available() || PathApi.INSTANCE == null) return List.of();
        try {
            var api = PathApi.INSTANCE;
            var behavior = api.behavior.invoke(api.bot);
            var points = new ArrayList<BlockPos>();
            api.append(api.current.invoke(behavior), points);
            if (!points.isEmpty()) api.append(api.next.invoke(behavior), points);
            return points;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return List.of();
        }
    }

    private record PathApi(Object bot, Method behavior, Method current, Method next,
                           Method path, Method position, Method positions) {
        private static final PathApi INSTANCE = create();

        private static PathApi create() {
            try {
                Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
                Object bot = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
                var behavior = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior");
                var executor = Class.forName("baritone.api.pathing.path.IPathExecutor");
                var path = Class.forName("baritone.api.pathing.calc.IPath");
                return new PathApi(bot, behavior, behavior.getReturnType().getMethod("getCurrent"),
                        behavior.getReturnType().getMethod("getNext"), executor.getMethod("getPath"),
                        executor.getMethod("getPosition"), path.getMethod("positions"));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return null;
            }
        }

        private void append(Object executor, List<BlockPos> points) throws ReflectiveOperationException {
            if (executor == null || points.size() >= PATH_LIMIT) return;
            var route = (List<?>) positions.invoke(path.invoke(executor));
            int start = Math.max(0, (int) position.invoke(executor));
            for (int index = start; index < route.size() && points.size() < PATH_LIMIT; index++) {
                if (route.get(index) instanceof BlockPos point
                        && (points.isEmpty() || !point.equals(points.get(points.size() - 1)))) points.add(point);
            }
        }
    }
}
