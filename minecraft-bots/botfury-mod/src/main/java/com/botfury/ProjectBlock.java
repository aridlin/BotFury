package com.botfury;

import com.google.gson.Gson;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

/** Explicit position, expected block, and an approved place to stand while working. */
record ProjectBlock(BlockPos target, BlockPos stand, Identifier block, Map<String, String> properties, Direction face) {
    ProjectBlock(BlockPos target, BlockPos stand, Identifier block) {
        this(target, stand, block, Map.of(), Direction.UP);
    }

    BlockState desired() { return state(block, properties); }
    BlockPos support() { return target.offset(face.getOpposite()); }
    boolean edgePlacement() { return face.getAxis().isHorizontal() && stand.equals(support().up()); }
    static Vec3d edgePosition(BlockPos stand, Direction face) {
        return Vec3d.ofBottomCenter(stand).add(Vec3d.of(face.getVector()).multiply(.66));
    }

    boolean matches(BlockState actual) {
        if (block.equals(Identifier.of("minecraft", "air"))) return actual.isAir();
        return Registries.BLOCK.getId(actual.getBlock()).equals(block) && (properties.isEmpty() || actual.equals(desired()));
    }

    static Map<String, String> properties(BlockState state) {
        var values = new TreeMap<String, String>();
        for (var property : state.getProperties()) values.put(property.getName(), propertyValue(state, property));
        return values;
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }

    static BlockState state(Identifier id, Map<String, String> values) {
        if (id == null || !Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Unknown block " + id);
        var state = Registries.BLOCK.get(id).getDefaultState();
        for (var entry : values.entrySet()) {
            var property = state.getBlock().getStateManager().getProperty(entry.getKey());
            if (property == null || entry.getValue() == null) throw new IllegalArgumentException("Unknown property " + entry.getKey() + " on " + id);
            state = apply(state, property, entry.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState apply(BlockState state, Property<T> property, String value) {
        return state.with(property, property.parse(value)
                .orElseThrow(() -> new IllegalArgumentException("Invalid " + property.getName() + " value " + value)));
    }

    record Position(Integer x, Integer y, Integer z) {
        BlockPos checked() {
            if (x == null || y == null || z == null || Math.abs((long)x) > 29_999_983
                    || Math.abs((long)z) > 29_999_983 || y < -64 || y > 319) {
                throw new IllegalArgumentException("Invalid block coordinates");
            }
            return new BlockPos(x, y, z);
        }
    }
    record Payload(Position target, Position stand, String block, Map<String, String> properties, String face) {
        Payload(Position target, Position stand, String block) {
            this(target, stand, block, null, null);
        }
    }

    static ProjectBlock parse(String json) {
        Payload data = new Gson().fromJson(json, Payload.class);
        if (data == null || data.target == null || data.stand == null || data.block == null) {
            throw new IllegalArgumentException("Provide target, stand and block");
        }
        var target = data.target.checked();
        var stand = data.stand.checked();
        var block = Identifier.tryParse(data.block);
        if (block == null || !Registries.BLOCK.containsId(block) || !validActionStand(target, stand)) {
            throw new IllegalArgumentException("Invalid project block or standing position");
        }
        var properties = data.properties == null ? Map.<String, String>of() : data.properties;
        state(block, properties);
        var face = data.face == null ? Direction.UP : Direction.byName(data.face);
        if (face == null) throw new IllegalArgumentException("Invalid placement face");
        return new ProjectBlock(target, stand, block, Map.copyOf(properties), face);
    }

    static boolean validStand(BlockPos target, BlockPos stand) {
        return !target.equals(stand) && !target.equals(stand.up()) && !target.equals(stand.down())
                && target.getSquaredDistance(stand) <= 16;
    }

    static boolean validActionStand(BlockPos target, BlockPos stand) {
        if (validStand(target, stand)) return true;
        int height = target.getY() - stand.getY();
        if (height < 2 || height > 6) return false;
        double dx = Math.max(0, Math.abs(target.getX() - stand.getX()) - .5);
        double dz = Math.max(0, Math.abs(target.getZ() - stand.getZ()) - .5);
        double dy = height - 1.62;
        return dx * dx + dy * dy + dz * dz <= 4.4 * 4.4;
    }

    ProjectQueue.Work reserve(ProjectQueue.Work work) {
        Set<String> locks = new HashSet<>(work.locks());
        locks.addAll(stationCells(target, stand));
        if (work.kind() == ProjectQueue.Kind.BUILD) locks.add(key(support()));
        if (work.kind() == ProjectQueue.Kind.BUILD && edgePlacement()) {
            locks.add(key(target.up()));
            locks.add(key(target.up(2)));
        }
        if (work.cleanup() != null && !key(target).equals(work.cleanup().site())) {
            throw new IllegalArgumentException("Cleanup site must match its target");
        }
        return new ProjectQueue.Work(work.id(), work.kind(), work.payload(), work.needs(), locks, work.sameWorkerAs(), work.cleanup(), work.constructionMaterial());
    }

    static java.util.List<String> stationCells(BlockPos target, BlockPos stand) {
        return java.util.List.of(key(target), key(stand), key(stand.down()), key(stand.up()));
    }

    static String key(BlockPos block) {
        return "block:" + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
