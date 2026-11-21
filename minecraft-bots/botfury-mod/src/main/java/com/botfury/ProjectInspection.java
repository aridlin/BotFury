package com.botfury;

import com.google.gson.Gson;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.BlockView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies exact block states across a bounded set of cells in one loaded column of chunks. */
record ProjectInspection(BlockPos stand, Map<BlockPos, BlockState> expected) {
    static final int MAX_CELLS = 32;
    private static final Gson JSON = new Gson();

    record Check(ProjectBlock.Position pos, String block, Map<String, String> properties) {
        static Check of(BlockPos pos, BlockState state) {
            return new Check(StructureSupply.position(pos), Registries.BLOCK.getId(state.getBlock()).toString(),
                    ProjectBlock.properties(state));
        }
    }

    private record Payload(ProjectBlock.Position stand, List<Check> checks) {}

    static String payload(BlockPos stand, List<Check> checks) {
        return JSON.toJson(new Payload(StructureSupply.position(stand), checks));
    }

    static ProjectInspection parse(String json) {
        var payload = JSON.fromJson(json, Payload.class);
        if (payload == null || payload.stand == null || payload.checks == null
                || payload.checks.isEmpty() || payload.checks.size() > MAX_CELLS) {
            throw new IllegalArgumentException("Invalid inspection cells or standing position");
        }
        var stand = payload.stand.checked();
        var expected = new LinkedHashMap<BlockPos, BlockState>();
        long chunk = chunk(payload.checks.get(0));
        for (var check : payload.checks) {
            if (chunk(check) != chunk) throw new IllegalArgumentException("Inspection crosses a chunk boundary");
            var pos = check.pos.checked();
            if (Math.abs((stand.getX() >> 4) - (pos.getX() >> 4)) > 1
                    || Math.abs((stand.getZ() >> 4) - (pos.getZ() >> 4)) > 1) {
                throw new IllegalArgumentException("Inspection stand is too far from its chunk");
            }
            var state = ProjectBlock.state(Identifier.tryParse(check.block), check.properties);
            if (expected.put(pos, state) != null) throw new IllegalArgumentException("Duplicate inspection cell");
        }
        return new ProjectInspection(stand, Collections.unmodifiableMap(expected));
    }

    private static long chunk(Check check) {
        if (check == null || check.pos == null || check.block == null || check.properties == null) {
            throw new IllegalArgumentException("Invalid inspection cell");
        }
        var pos = check.pos.checked();
        return ChunkPos.toLong(pos);
    }

    ProjectQueue.Work reserve(ProjectQueue.Work work) {
        Set<String> locks = new HashSet<>(work.locks());
        expected.keySet().forEach(pos -> locks.add(ProjectBlock.key(pos)));
        locks.add(ProjectBlock.key(stand));
        locks.add(ProjectBlock.key(stand.up()));
        locks.add(ProjectBlock.key(stand.down()));
        return new ProjectQueue.Work(work.id(), work.kind(), work.payload(), work.needs(), locks, work.sameWorkerAs());
    }

    String mismatch(BlockView world) {
        for (var entry : expected.entrySet()) {
            var wanted = entry.getValue();
            var observed = world.getBlockState(entry.getKey());
            if (wanted.isAir() ? !observed.isAir() : !wanted.equals(observed)) {
                return "Expected " + wanted + " but observed " + observed + " at " + entry.getKey().toShortString();
            }
        }
        return null;
    }
}
