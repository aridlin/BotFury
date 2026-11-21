package com.botfury;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BuildProtectionTest {
    @Test void protectsOwnBuildAcrossSharedStoreAndScopesPermitToItsProject(@TempDir Path directory) throws Exception {
        String property = "botfury.advancedModulesFile";
        String previous = System.getProperty(property);
        System.setProperty(property, directory.resolve("permission").toString());
        try {
            String world = "server|overworld";
            BlockPos target = new BlockPos(1, 64, 2);
            BuildProtection.reserve(world, "platform", new ProjectGather.Area(target, target.add(2, 1, 3)));
            assertTrue(Files.readString(directory.resolve("botfury-exclusion-zones.json")).contains("platform"));
            assertTrue(BuildProtection.blocked(world, target, null));
            assertTrue(BuildProtection.blocked(world, target, "other"));
            assertFalse(BuildProtection.blocked(world, target, "platform"));
            assertFalse(BuildProtection.blocked(world, target.west(), null));
            assertFalse(BuildProtection.blocked("server|nether", target, null));
            BuildProtection.remove(world, "platform");
            assertFalse(BuildProtection.blocked(world, target, null));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }
}
