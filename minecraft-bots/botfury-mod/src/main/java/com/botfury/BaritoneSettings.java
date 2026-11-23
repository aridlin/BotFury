package com.botfury;

import net.minecraft.block.Block;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.registry.Registries;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;

final class BaritoneSettings implements AutoCloseable {
    private final Object breaking, placing, avoidance, mobAvoidance, oldBreak, oldPlace, oldMobAvoidance;
    private final List<?> oldAvoidance;
    private final Field value;

    BaritoneSettings() throws ReflectiveOperationException {
        this(true);
    }

    BaritoneSettings(boolean allowBreak) throws ReflectiveOperationException {
        this(allowBreak, false);
    }

    BaritoneSettings(boolean allowBreak, boolean preserveGates) throws ReflectiveOperationException {
        Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
        breaking = settings.getClass().getField("allowBreak").get(settings);
        placing = settings.getClass().getField("allowPlace").get(settings);
        avoidance = preserveGates ? settings.getClass().getField("blocksToAvoid").get(settings) : null;
        mobAvoidance = settings.getClass().getField("avoidance").get(settings);
        value = breaking.getClass().getField("value");
        oldBreak = value.get(breaking);
        oldPlace = value.get(placing);
        oldMobAvoidance = value.get(mobAvoidance);
        oldAvoidance = avoidance == null ? null : (List<?>) value.get(avoidance);
        try {
            value.set(breaking, allowBreak);
            value.set(placing, false);
            value.set(mobAvoidance, true);
            if (avoidance != null) {
                var blocked = new LinkedHashSet<Object>(oldAvoidance);
                for (Block block : Registries.BLOCK) {
                    if (block instanceof FenceGateBlock) blocked.add(block);
                }
                value.set(avoidance, List.copyOf(blocked));
            }
        } catch (ReflectiveOperationException error) {
            close();
            throw error;
        }
    }

    @Override public void close() {
        try {
            value.set(breaking, oldBreak);
            value.set(placing, oldPlace);
            value.set(mobAvoidance, oldMobAvoidance);
            if (avoidance != null) value.set(avoidance, oldAvoidance);
        } catch (ReflectiveOperationException error) {
            BotEvents.add("warn", "movement", "Could not restore Baritone settings");
        }
    }
}
