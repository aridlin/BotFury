package com.botfury;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads block volumes from the Sponge v2 and v3 .schem formats. */
final class SpongeSchematic {
    private SpongeSchematic() {}

    static Map<BlockPos, String> blocks(NbtCompound root, int cellLimit) {
        boolean wrapped = root.contains("Schematic", NbtElement.COMPOUND_TYPE);
        var schematic = wrapped ? root.getCompound("Schematic") : root;
        int version = schematic.getInt("Version");
        if (!schematic.contains("Version", NbtElement.INT_TYPE) || (wrapped ? version != 3 : version != 2)) {
            throw new IllegalArgumentException("Expected a WorldEdit .schem in Sponge v2 or v3 format");
        }
        int width = dimension(schematic, "Width");
        int height = dimension(schematic, "Height");
        int length = dimension(schematic, "Length");
        long volume = (long) width * height * length;
        if (volume > cellLimit) throw new IllegalArgumentException("Schematic volume exceeds " + cellLimit + " blocks");
        var blocks = wrapped ? schematic.getCompound("Blocks") : schematic;
        if (!emptyList(schematic, "Entities") || !emptyList(blocks, "BlockEntities")) {
            throw new IllegalArgumentException("Schematic contains entities or block entities");
        }

        var paletteTag = blocks.getCompound("Palette");
        var palette = new HashMap<Integer, String>();
        for (String state : paletteTag.getKeys()) {
            int id = paletteTag.getInt(state);
            if (!paletteTag.contains(state, NbtElement.INT_TYPE) || id < 0 || palette.put(id, state) != null) {
                throw new IllegalArgumentException("Invalid schematic block palette");
            }
        }
        byte[] data = blocks.getByteArray(wrapped ? "Data" : "BlockData");
        var decoded = new LinkedHashMap<BlockPos, String>();
        int cursor = 0;
        for (int i = 0; i < volume; i++) {
            int id = 0;
            for (int shift = 0; ; shift += 7) {
                if (cursor >= data.length) throw new IllegalArgumentException("Truncated schematic block data");
                int octet = Byte.toUnsignedInt(data[cursor++]);
                if (shift == 28 && (octet & 0xf8) != 0) throw new IllegalArgumentException("Invalid schematic palette index");
                id |= (octet & 0x7f) << shift;
                if ((octet & 0x80) == 0) break;
            }
            String state = palette.get(id);
            if (state == null) throw new IllegalArgumentException("Schematic references missing palette index " + id);
            decoded.put(new BlockPos(i % width, i / (width * length), (i / width) % length), state);
        }
        if (cursor != data.length) throw new IllegalArgumentException("Unexpected trailing schematic block data");
        return decoded;
    }

    private static int dimension(NbtCompound schematic, String key) {
        int value = Short.toUnsignedInt(schematic.getShort(key));
        if (!schematic.contains(key, NbtElement.SHORT_TYPE) || value == 0) {
            throw new IllegalArgumentException("Invalid schematic " + key.toLowerCase(java.util.Locale.ROOT));
        }
        return value;
    }

    private static boolean emptyList(NbtCompound tag, String key) {
        return !tag.contains(key) || tag.get(key) instanceof NbtList list && list.isEmpty();
    }
}
