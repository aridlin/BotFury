package com.botfury;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpongeSchematicTest {
    @Test void readsV2WithXThenZThenYOrderingAndSparseVarints() {
        var schematic = schematic(2, 2, 2, 2);
        var palette = new NbtCompound();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:stone_bricks", 130);
        schematic.put("Palette", palette);
        schematic.putByteArray("BlockData", new byte[]{0, (byte) 0x82, 1, 0, 0, 0, 0, (byte) 0x82, 1, 0});
        var blocks = SpongeSchematic.blocks(schematic, 8);
        assertEquals(8, blocks.size());
        assertEquals("minecraft:stone_bricks", blocks.get(new BlockPos(1, 0, 0)));
        assertEquals("minecraft:stone_bricks", blocks.get(new BlockPos(0, 1, 1)));
        assertEquals("minecraft:air", blocks.get(new BlockPos(0, 0, 0)));
    }

    @Test void readsV3BlockContainerAndAcceptsAbsentOptionalData() {
        var root = v3(new byte[]{1});
        assertEquals("minecraft:stone", SpongeSchematic.blocks(root, 1).get(BlockPos.ORIGIN));
    }

    @Test void rejectsMalformedIndicesAndUnexpectedBlockData() {
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.blocks(v3(new byte[]{(byte) 0x80}), 1));
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.blocks(v3(new byte[]{1, 1}), 1));
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.blocks(v3(new byte[]{2}), 1));
        byte[] overflow = {(byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, 8};
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.blocks(v3(overflow), 1));
    }

    @Test void rejectsOversizedVolumesAndDataForEntities() {
        var root = v3(new byte[]{1});
        root.getCompound("Schematic").putShort("Width", (short) -1);
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.blocks(root, 256));
        root.getCompound("Schematic").putShort("Width", (short) 1);
        var entities = new NbtList();
        entities.add(new NbtCompound());
        root.getCompound("Schematic").getCompound("Blocks").put("BlockEntities", entities);
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.blocks(root, 256));
    }

    @Test void acceptsOnlyContainerRecordsMatchingTheirCells() {
        var root = v3(new byte[]{1});
        var blocks = root.getCompound("Schematic").getCompound("Blocks");
        var palette = blocks.getCompound("Palette");
        palette.remove("minecraft:stone");
        String expected = "minecraft:chest[facing=north,type=single,waterlogged=false]";
        palette.putInt(expected, 1);
        var entity = new NbtCompound();
        entity.putString("Id", "minecraft:chest");
        entity.putIntArray("Pos", new int[]{0, 0, 0});
        var saved = new NbtCompound();
        saved.putString("LootTable", "minecraft:chests/simple_dungeon");
        entity.put("Data", saved);
        var list = new NbtList();
        list.add(entity);
        blocks.put("BlockEntities", list);
        assertEquals(Map.of(BlockPos.ORIGIN, expected), SpongeSchematic.blocks(root, 1));
        entity.putIntArray("Pos", new int[]{-1, 0, 0});
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.blocks(root, 1));
        entity.putIntArray("Pos", new int[]{0, 0, 0});
        entity.putString("Id", "minecraft:hopper");
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.blocks(root, 1));
        entity.putString("Id", "minecraft:chest");
        list.add(entity.copy());
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.blocks(root, 1));
    }

    private static NbtCompound v3(byte[] data) {
        var root = new NbtCompound();
        var schematic = schematic(3, 1, 1, 1);
        var blocks = new NbtCompound();
        var palette = new NbtCompound();
        palette.putInt("minecraft:stone", 1);
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", data);
        schematic.put("Blocks", blocks);
        root.put("Schematic", schematic);
        return root;
    }

    private static NbtCompound schematic(int version, int width, int height, int length) {
        var schematic = new NbtCompound();
        schematic.putInt("Version", version);
        schematic.putShort("Width", (short) width);
        schematic.putShort("Height", (short) height);
        schematic.putShort("Length", (short) length);
        return schematic;
    }
}
