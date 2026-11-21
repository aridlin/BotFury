package com.botfury;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkerEnrollmentTest {
    @TempDir Path folder;

    @Test void restoresOnlyTheEnrolledPlayerAndWorld() throws Exception {
        Path path = folder.resolve("config/botfury-worker.json");
        var enrollment = new WorkerEnrollment("Bot01", "paper:25565|minecraft:overworld", 8840);
        assertNull(WorkerEnrollment.read(path));

        WorkerEnrollment.save(path, enrollment);
        var restored = WorkerEnrollment.read(path);
        assertEquals(enrollment, restored);
        assertTrue(restored.matches("Bot01", "paper:25565|minecraft:overworld"));
        assertFalse(restored.matches("Bot02", "paper:25565|minecraft:overworld"));
        assertFalse(restored.matches("Bot01", "paper:25565|minecraft:the_nether"));
        assertFalse(restored.matches("Bot01", "paper:25566|minecraft:overworld"));
    }

    @Test void canReplaceAnEnrollmentWithAStopMarkerAndRejectMalformedFiles() throws Exception {
        Path path = folder.resolve("botfury-worker.json");
        WorkerEnrollment.save(path, new WorkerEnrollment("Bot01", "test|world", 8840));
        WorkerEnrollment.save(path, null);
        assertNull(WorkerEnrollment.read(path));

        Files.writeString(path, "{\"name\":\"Bot01\",\"world\":\"test|world\",\"port\":1}");
        assertThrows(IOException.class, () -> WorkerEnrollment.read(path));
    }
}
