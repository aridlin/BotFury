package com.botfury;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
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

    static boolean goTo(BlockPos destination) {
        if (!available()) return false;
        var api = GoalApi.INSTANCE;
        if (api != null) {
            try {
                var goal = api.block.newInstance(destination.getX(), destination.getY(), destination.getZ());
                api.start.invoke(api.process, goal);
                return true;
            } catch (ReflectiveOperationException | LinkageError error) {
                BotEvents.add("warn", "movement", "Baritone rejected the coordinate goal");
                return false;
            }
        }
        return execute("goto " + destination.getX() + " " + destination.getY() + " " + destination.getZ());
    }

    static boolean goToAny(List<BlockPos> destinations) {
        return goToAny(destinations, null);
    }

    static boolean cooperativeTravelAvailable() {
        return available() && GoalApi.INSTANCE != null && GoalApi.COOPERATIVE != null;
    }

    static boolean goToCooperativeStand(List<BlockPos> destinations, Collection<BlockPos> reservedCells) {
        return cooperativeTravelAvailable() && goToAny(destinations, reservedCells);
    }

    private static boolean goToAny(List<BlockPos> destinations, Collection<BlockPos> reservedCells) {
        if (destinations.isEmpty()) return false;
        var api = GoalApi.INSTANCE;
        if (available() && api != null && (destinations.size() > 1 || reservedCells != null)) {
            try {
                var goalType = api.start.getParameterTypes()[0];
                Object goal;
                if (destinations.size() == 1) {
                    var pos = destinations.get(0);
                    goal = api.block.newInstance(pos.getX(), pos.getY(), pos.getZ());
                } else {
                    var goals = Array.newInstance(goalType, destinations.size());
                    for (int i = 0; i < destinations.size(); i++) {
                        var pos = destinations.get(i);
                        Array.set(goals, i, api.block.newInstance(pos.getX(), pos.getY(), pos.getZ()));
                    }
                    goal = Class.forName("baritone.api.pathing.goals.GoalComposite")
                            .getConstructor(goals.getClass()).newInstance(goals);
                }
                if (reservedCells != null && GoalApi.COOPERATIVE != null) {
                    goal = GoalApi.COOPERATIVE.newInstance(goal, reservedCells);
                }
                api.start.invoke(api.process, goal);
                return true;
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                BotEvents.add("warn", "movement", "Baritone work-stand routing unavailable");
                if (reservedCells != null && GoalApi.COOPERATIVE != null) return false;
            }
        }
        return goTo(destinations.get(0));
    }

    static boolean goToAltitude(int y) {
        var api = GoalApi.INSTANCE;
        if (!available() || api == null) return false;
        var constructor = GoalApi.ALTITUDE;
        if (constructor == null) return false;
        try {
            api.start.invoke(api.process, constructor.newInstance(y));
            return true;
        } catch (ReflectiveOperationException | LinkageError error) {
            return false;
        }
    }

    private record GoalApi(Object process, Constructor<?> block, Method start) {
        private static final GoalApi INSTANCE = create();
        private static final Constructor<?> ALTITUDE = altitude();
        private static final Constructor<?> COOPERATIVE = cooperative();

        private static Constructor<?> cooperative() {
            try {
                var goal = Class.forName("baritone.api.pathing.goals.Goal");
                return Class.forName("baritone.api.pathing.goals.GoalCooperative")
                        .getConstructor(goal, Collection.class);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return null;
            }
        }

        private static Constructor<?> altitude() {
            try {
                return Class.forName("baritone.api.pathing.goals.GoalYLevel").getConstructor(int.class);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return null;
            }
        }

        private static GoalApi create() {
            var path = PathApi.INSTANCE;
            if (path == null) return null;
            try {
                var bot = Class.forName("baritone.api.IBaritone");
                var process = bot.getMethod("getCustomGoalProcess").invoke(path.bot);
                var goal = Class.forName("baritone.api.pathing.goals.Goal");
                var block = Class.forName("baritone.api.pathing.goals.GoalBlock");
                var start = Class.forName("baritone.api.process.ICustomGoalProcess").getMethod("setGoalAndPath", goal);
                return new GoalApi(process, block.getConstructor(int.class, int.class, int.class), start);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return null;
            }
        }
    }

    static boolean pursuing(BlockPos destination) {
        if (!available() || PathApi.INSTANCE == null) return false;
        var api = PathApi.INSTANCE;
        try {
            var behavior = api.behavior.invoke(api.bot);
            var goal = api.goal.invoke(behavior);
            return goal != null && (boolean) api.inGoal.invoke(goal, destination.getX(), destination.getY(), destination.getZ())
                    && ((boolean) api.pathing.invoke(behavior)
                        || ((java.util.Optional<?>) api.calculating.invoke(behavior)).isPresent());
        } catch (ReflectiveOperationException | LinkageError unavailable) {
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
                           Method path, Method position, Method positions, Method goal, Method inGoal,
                           Method pathing, Method calculating) {
        private static final PathApi INSTANCE = create();

        private static PathApi create() {
            try {
                Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
                Object bot = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
                var behavior = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior");
                var executor = Class.forName("baritone.api.pathing.path.IPathExecutor");
                var path = Class.forName("baritone.api.pathing.calc.IPath");
                var type = behavior.getReturnType();
                var goal = type.getMethod("getGoal");
                return new PathApi(bot, behavior, type.getMethod("getCurrent"),
                        type.getMethod("getNext"), executor.getMethod("getPath"),
                        executor.getMethod("getPosition"), path.getMethod("positions"), goal,
                        goal.getReturnType().getMethod("isInGoal", int.class, int.class, int.class),
                        type.getMethod("isPathing"), type.getMethod("getInProgress"));
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
