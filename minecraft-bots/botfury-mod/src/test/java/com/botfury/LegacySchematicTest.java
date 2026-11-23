package com.botfury;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacySchematicTest {
    @BeforeAll static void loadGameVersion() {
        SharedConstants.createGameVersion();
    }

    @Test void preservesBlockMetadataAndCellOrder() {
        var structure = LegacySchematic.convert(fixture(), 4);
        var blocks = structure.getList("blocks", NbtElement.COMPOUND_TYPE);
        var palette = structure.getList("palette", NbtElement.COMPOUND_TYPE);
        var birch = blocks.getCompound(1);
        var cobble = blocks.getCompound(2);
        assertEquals("minecraft:birch_planks", palette.getCompound(birch.getInt("state")).getString("Name"));
        assertEquals("minecraft:cobblestone", palette.getCompound(cobble.getInt("state")).getString("Name"));
        assertEquals(1, birch.getList("pos", NbtElement.INT_TYPE).getInt(0));
        assertEquals(1, cobble.getList("pos", NbtElement.INT_TYPE).getInt(2));
    }

    @Test void rejectsOutOfBudgetAndUnmappedIds() {
        assertThrows(IllegalArgumentException.class, () -> LegacySchematic.convert(fixture(), 3));
        var modded = fixture();
        modded.putByteArray("AddBlocks", new byte[]{0, 1});
        assertThrows(IllegalArgumentException.class, () -> LegacySchematic.convert(modded, 4));
    }

    private static NbtCompound fixture() {
        var input = new NbtCompound();
        input.putString("Materials", "Alpha");
        input.putShort("Width", (short) 2);
        input.putShort("Height", (short) 1);
        input.putShort("Length", (short) 2);
        input.putByteArray("Blocks", new byte[]{1, 5, 4, 24});
        input.putByteArray("Data", new byte[]{0, 2, 0, 1});
        return input;
    }
}
