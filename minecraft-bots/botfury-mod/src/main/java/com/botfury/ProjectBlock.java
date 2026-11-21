package com.botfury;

import com.google.gson.Gson;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.HashSet;
import java.util.Set;

/** Explicit position, expected block, and an approved place to stand while working. */
record ProjectBlock(BlockPos target, BlockPos stand, Identifier block) {
    private record Position(Integer x, Integer y, Integer z) {
        BlockPos checked() {
            if (x == null || y == null || z == null || Math.abs((long)x) > 29_999_983
                    || Math.abs((long)z) > 29_999_983 || y < -64 || y > 319) {
                throw new IllegalArgumentException("Invalid block coordinates");
            }
            return new BlockPos(x, y, z);
        }
    }
    private record Payload(Position target, Position stand, String block) {}

    static ProjectBlock parse(String json) {
        Payload data = new Gson().fromJson(json, Payload.class);
        if (data == null || data.target == null || data.stand == null || data.block == null) {
            throw new IllegalArgumentException("Provide target, stand and block");
        }
        var target = data.target.checked();
        var stand = data.stand.checked();
        var block = Identifier.tryParse(data.block);
        if (block == null || !Registries.BLOCK.containsId(block) || target.equals(stand)
                || target.equals(stand.up()) || target.equals(stand.down())
                || target.getSquaredDistance(stand) > 16) {
            throw new IllegalArgumentException("Invalid project block or standing position");
        }
        return new ProjectBlock(target, stand, block);
    }

    ProjectQueue.Work reserve(ProjectQueue.Work work) {
        Set<String> locks = new HashSet<>(work.locks());
        locks.add(key(target));
        locks.add(key(stand));
        locks.add(key(stand.down()));
        locks.add(key(stand.up()));
        return new ProjectQueue.Work(work.id(), work.kind(), work.payload(), work.needs(), locks);
    }

    static String key(BlockPos block) {
        return "block:" + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
