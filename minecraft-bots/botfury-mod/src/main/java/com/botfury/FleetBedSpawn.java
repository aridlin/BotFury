package com.botfury;

import net.minecraft.block.BedBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;

final class FleetBedSpawn {
    private static ClientWorld world;
    private static BlockPos bed;
    private static long deadline;
    private static long nextAction;
    private static long confirmBy;
    private static long wakeUntil;
    private static ClientPlayerEntity bedUser;
    private static int attempts;
    private static boolean approaching;
    private static boolean used;
    private static BaritoneSettings travel;
    private static String status = "";

    static void start(MinecraftClient client, BlockPos position) {
        if (client.world == null || client.player == null || client.player.isSpectator()) return;
        if (client.world.isChunkLoaded(position) && !(client.world.getBlockState(position).getBlock() instanceof BedBlock)) {
            status = "The requested block is not a bed";
            return;
        }
        if (!BedBlock.isBedWorking(client.world)) {
            status = "Beds cannot set spawn in this dimension";
            return;
        }
        cancel();
        LocalFormation.pauseMovement();
        world = client.world;
        bed = position;
        deadline = System.nanoTime() + 90_000_000_000L;
        nextAction = 0;
        approaching = false;
        used = false;
        attempts = 0;
        status = "Approaching bed " + position.toShortString();
    }

    static void tick(MinecraftClient client) {
        var player = client.player;
        long now = System.nanoTime();
        if (bedUser != null && (player != bedUser || now >= wakeUntil)) bedUser = null;
        if (bedUser != null && player.isSleeping()) {
            player.networkHandler.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.STOP_SLEEPING));
            bedUser = null;
            if (bed != null && used) finish("Spawn set at bed " + bed.toShortString());
        }
        if (bed == null) return;
        if (client.world != world || player == null || !player.isAlive() || client.interactionManager == null) {
            finish("Bed request interrupted");
            return;
        }
        if (now > deadline) {
            finish(used ? "Server did not confirm the spawn point" : "Could not approach the bed");
            return;
        }
        if (used) {
            if (now < confirmBy) return;
            if (attempts == 3) {
                finish("Server did not confirm the spawn point");
                return;
            }
            used = false;
        }
        boolean loaded = client.world.isChunkLoaded(bed);
        if (loaded && !(world.getBlockState(bed).getBlock() instanceof BedBlock)) {
            finish("Bed is no longer available");
            return;
        }
        var hit = loaded ? ProjectWorker.excavationHit(client, bed) : null;
        if (hit != null) {
            stopTravel();
            used = true;
            attempts++;
            confirmBy = now + 2_500_000_000L;
            wakeUntil = confirmBy;
            bedUser = player;
            status = "Waiting for spawn confirmation";
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            return;
        }
        if (now < nextAction) return;
        nextAction = now + 7_000_000_000L;
        var stands = new ArrayList<BlockPos>();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            for (int radius = 1; radius <= 2; radius++) {
                var candidate = bed.offset(direction, radius);
                if (!loaded || ProjectWorker.usableInteractionStand(client, candidate, bed)) stands.add(candidate);
            }
        }
        if (stands.isEmpty()) return;
        stands.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(player.getPos())));
        try {
            if (travel == null) travel = new BaritoneSettings(false, true);
        } catch (ReflectiveOperationException error) {
            finish("Safe bed routing is unavailable");
            return;
        }
        approaching = BaritoneClient.goToAny(stands);
    }

    static void gameMessage(Text message) {
        if (bed != null && used && message.getContent() instanceof TranslatableTextContent content
                && "block.minecraft.set_spawn".equals(content.getKey())) {
            finish("Spawn set at bed " + bed.toShortString());
        }
    }

    static void cancel() {
        if (bed != null) finish("Bed request cancelled");
    }

    private static void stopTravel() {
        if (approaching) BaritoneClient.execute("stop");
        approaching = false;
        if (travel != null) {
            travel.close();
            travel = null;
        }
    }

    private static void finish(String result) {
        stopTravel();
        bed = null;
        world = null;
        status = result;
    }

    static String status() {
        return status;
    }
}
