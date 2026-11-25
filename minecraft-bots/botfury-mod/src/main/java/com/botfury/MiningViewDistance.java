package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/** Requests more server chunks during a short search without writing the user's video settings. */
public final class MiningViewDistance {
    private static final int MAX_VIEW_DISTANCE = 32;
    private static final int SEARCH_TICKS = 240;
    private static final int APPROACH_TICKS = 900;
    private static final long COOLDOWN_MS = 45_000;
    private static ClientPlayNetworkHandler connection;
    private static int expiresAt;
    private static long nextAllowed;
    private static int approachExpiresAt;
    private static boolean enlarged;

    private MiningViewDistance() {}

    static boolean begin(MinecraftClient client, int age) {
        if (active()) return true;
        long now = Util.getMeasuringTimeMs();
        if (now < nextAllowed || client.getNetworkHandler() == null) return false;
        connection = client.getNetworkHandler();
        expiresAt = age + SEARCH_TICKS;
        approachExpiresAt = age + APPROACH_TICKS;
        nextAllowed = now + COOLDOWN_MS;
        enlarged = client.options.getViewDistance().getValue() < MAX_VIEW_DISTANCE;
        if (enlarged) {
            client.options.sendClientSettings();
            BotEvents.add("debug", "mining", "Requested wider chunk loading for the resource search");
        }
        return true;
    }

    static boolean active() { return connection != null; }

    static void update(MinecraftClient client, int age, BlockPos target) {
        if (!active()) return;
        var feet = client.player.getBlockPos();
        int defaultRadius = Math.max(1, client.options.getViewDistance().getValue() - 1);
        boolean targetNearby = target != null && Math.abs((target.getX() >> 4) - (feet.getX() >> 4)) <= defaultRadius
                && Math.abs((target.getZ() >> 4) - (feet.getZ() >> 4)) <= defaultRadius;
        if (connection != client.getNetworkHandler() || targetNearby
                || age >= (target == null ? expiresAt : approachExpiresAt)) finish(client);
    }

    static void finish(MinecraftClient client) {
        var previous = connection;
        connection = null;
        if (enlarged && previous != null && previous == client.getNetworkHandler()) {
            client.options.sendClientSettings();
            BotEvents.add("debug", "mining", "Restored the normal chunk loading distance");
        }
        enlarged = false;
    }

    static int loadedRadius(MinecraftClient client) {
        return MathHelper.clamp(client.getNetworkHandler().chunkLoadDistance, 2, MAX_VIEW_DISTANCE);
    }

    public static int requestedDistance(int configured) {
        return active() && connection == MinecraftClient.getInstance().getNetworkHandler()
                ? MAX_VIEW_DISTANCE : configured;
    }
}
