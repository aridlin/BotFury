package com.botfury;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.datafixer.Schemas;
import net.minecraft.datafixer.fix.BlockStateFlattening;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;

import java.util.HashMap;

/** Normalizes vanilla numeric-ID schematics into the same structure palette used by the planner. */
final class LegacySchematic {
    private LegacySchematic() {}

    static NbtCompound convert(NbtCompound input, int limit) {
        int width = Short.toUnsignedInt(input.getShort("Width"));
        int height = Short.toUnsignedInt(input.getShort("Height"));
        int length = Short.toUnsignedInt(input.getShort("Length"));
        long volume = (long) width * height * length;
        if (!"Alpha".equals(input.getString("Materials")) || volume < 1 || volume > limit) {
            throw new IllegalArgumentException("Expected a vanilla Alpha schematic with at most " + limit + " cells");
        }
        if (!input.getList("Entities", NbtElement.COMPOUND_TYPE).isEmpty()
                || !input.getList("TileEntities", NbtElement.COMPOUND_TYPE).isEmpty()) {
            throw new IllegalArgumentException("Legacy schematic entity data requires conversion to a modern .schem file");
        }
        byte[] blocks = input.getByteArray("Blocks");
        byte[] data = input.getByteArray("Data");
        if (blocks.length != volume || data.length != volume) {
            throw new IllegalArgumentException("Legacy schematic block arrays do not match its dimensions");
        }
        for (byte highBits : input.getByteArray("AddBlocks")) {
            if (highBits != 0) throw new IllegalArgumentException("Legacy schematic contains non-vanilla numeric block IDs");
        }

        var indices = new HashMap<Integer, Integer>();
        var palette = new NbtList();
        var cells = new NbtList();
        for (int index = 0; index < blocks.length; index++) {
            int block = Byte.toUnsignedInt(blocks[index]);
            int metadata = data[index] & 15;
            int packed = block << 4 | metadata;
            int entry = indices.computeIfAbsent(packed, key -> {
                var converted = BlockStateFlattening.lookupState(key).convert(NbtOps.INSTANCE).getValue();
                if (!(converted instanceof NbtCompound state)
                        || block != 0 && state.getString("Name").equals("minecraft:air")) {
                    throw new IllegalArgumentException("Unknown legacy block " + block + ":" + metadata);
                }
                palette.add(state);
                return palette.size() - 1;
            });
            var cell = new NbtCompound();
            cell.put("pos", position(index % width, index / width / length, index / width % length));
            cell.putInt("state", entry);
            cells.add(cell);
        }
        var structure = new NbtCompound();
        structure.put("size", position(width, height, length));
        structure.put("palette", palette);
        structure.put("blocks", cells);
        return DataFixTypes.STRUCTURE.update(Schemas.getFixer(), structure, 1519);
    }

    private static NbtList position(int x, int y, int z) {
        var coordinates = new NbtList();
        coordinates.add(NbtInt.of(x));
        coordinates.add(NbtInt.of(y));
        coordinates.add(NbtInt.of(z));
        return coordinates;
    }
}
