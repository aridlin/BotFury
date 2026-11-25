package com.botfury;

import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkerCargoRecoveryTest {
    @TempDir Path directory;

    @Test void interruptedRecoveryKeepsOriginalCargoForOneDurableRetry() throws Exception {
        var path = directory.resolve("cargo.json");
        var worker = new WorkerEnrollment("FuryTest", "local:25565|minecraft:overworld", 19300, "supplies");
        var equipment = Map.of("minecraft:iron_pickaxe", 1);
        var first = new WorkerCargoRecovery();
        first.onDeath(path, worker, null, equipment, new BlockPos(34, 17, -8));
        var original = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("cargo");

        var interrupted = new WorkerCargoRecovery();
        interrupted.load(path, worker);
        interrupted.onDeath(path, worker, null, Map.of(), new BlockPos(2, 64, 0));
        var retry = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("cargo");
        assertEquals(original.get("location"), retry.get("location"));
        assertEquals(original.get("contents"), retry.get("contents"));
        assertEquals(original.get("expiresAt"), retry.get("expiresAt"));
        assertTrue(retry.get("retryAt").getAsLong() > 0);

        var last = new WorkerCargoRecovery();
        last.load(path, worker);
        assertTrue(last.pending());
        last.onDeath(path, worker, null, Map.of(), new BlockPos(3, 64, 0));
        assertFalse(last.pending());
        assertFalse(Files.exists(path));
    }
}
