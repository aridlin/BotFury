package com.botfury;

import net.minecraft.block.Block;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

final class BaritoneSettings implements AutoCloseable {
    private final Object breaking, placing, avoidance, mobAvoidance, oldBreak, oldPlace, oldMobAvoidance;
    private final List<?> oldAvoidance;
    private final Object excludedFromBreaking;
    private final List<?> oldExcludedFromBreaking;
    private final Object fallHeight, bucketFall, autoTool, oldFallHeight, oldBucketFall, oldAutoTool;
    private final Field value;

    BaritoneSettings() throws ReflectiveOperationException {
        this(true);
    }

    BaritoneSettings(boolean allowBreak) throws ReflectiveOperationException {
        this(allowBreak, false);
    }

    BaritoneSettings(boolean allowBreak, boolean preserveGates) throws ReflectiveOperationException {
        this(allowBreak, preserveGates, false);
    }

    BaritoneSettings(boolean allowBreak, boolean preserveGates, boolean recovery) throws ReflectiveOperationException {
        this(allowBreak, preserveGates, recovery ? BaritoneSettings::recoverableTerrain : null, false);
    }

    static BaritoneSettings prospecting(boolean allowBreak) throws ReflectiveOperationException {
        return new BaritoneSettings(allowBreak, true, BaritoneSettings::recoverableTerrain, true);
    }

    static BaritoneSettings surfaceGathering() throws ReflectiveOperationException {
        return new BaritoneSettings(false, true, null, true);
    }

    static BaritoneSettings explorationExit(boolean equipped) throws ReflectiveOperationException {
        return new BaritoneSettings(true, true,
                equipped ? BaritoneSettings::recoverableTerrain : BaritoneSettings::handDiggableTerrain, true);
    }

    static BaritoneSettings lowerStandApproach() throws ReflectiveOperationException {
        return new BaritoneSettings(true, true, block -> recoverableTerrain(block) || block instanceof LeavesBlock, true);
    }

    static BaritoneSettings canopyApproach() throws ReflectiveOperationException {
        return new BaritoneSettings(true, true, block -> block instanceof LeavesBlock, true);
    }

    private BaritoneSettings(boolean allowBreak, boolean preserveGates, Predicate<Block> canBreak, boolean prospecting)
            throws ReflectiveOperationException {
        Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
        breaking = settings.getClass().getField("allowBreak").get(settings);
        placing = settings.getClass().getField("allowPlace").get(settings);
        avoidance = preserveGates ? settings.getClass().getField("blocksToAvoid").get(settings) : null;
        mobAvoidance = settings.getClass().getField("avoidance").get(settings);
        excludedFromBreaking = canBreak == null ? null : settings.getClass().getField("blocksToDisallowBreaking").get(settings);
        fallHeight = prospecting ? settings.getClass().getField("maxFallHeightNoWater").get(settings) : null;
        bucketFall = prospecting ? settings.getClass().getField("allowWaterBucketFall").get(settings) : null;
        autoTool = prospecting ? settings.getClass().getField("autoTool").get(settings) : null;
        value = breaking.getClass().getField("value");
        oldBreak = value.get(breaking);
        oldPlace = value.get(placing);
        oldMobAvoidance = value.get(mobAvoidance);
        oldAvoidance = avoidance == null ? null : (List<?>) value.get(avoidance);
        oldExcludedFromBreaking = canBreak == null ? null : (List<?>) value.get(excludedFromBreaking);
        oldFallHeight = prospecting ? value.get(fallHeight) : null;
        oldBucketFall = prospecting ? value.get(bucketFall) : null;
        oldAutoTool = prospecting ? value.get(autoTool) : null;
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
            if (canBreak != null) {
                var blocked = new LinkedHashSet<Object>(oldExcludedFromBreaking);
                for (Block block : Registries.BLOCK) {
                    if (!canBreak.test(block)) blocked.add(block);
                }
                value.set(excludedFromBreaking, List.copyOf(blocked));
            }
            if (prospecting) {
                value.set(fallHeight, 1);
                value.set(bucketFall, false);
                value.set(autoTool, false);
            }
        } catch (ReflectiveOperationException error) {
            close();
            throw error;
        }
    }

    static boolean recoverableTerrain(Block block) {
        var state = block.getDefaultState();
        return state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.BASE_STONE_NETHER);
    }

    static boolean handDiggableTerrain(Block block) {
        return block.getDefaultState().isIn(BlockTags.DIRT) && !block.getDefaultState().isToolRequired();
    }

    @Override public void close() {
        try {
            value.set(breaking, oldBreak);
            value.set(placing, oldPlace);
            value.set(mobAvoidance, oldMobAvoidance);
            if (avoidance != null) value.set(avoidance, oldAvoidance);
            if (excludedFromBreaking != null) value.set(excludedFromBreaking, oldExcludedFromBreaking);
            if (fallHeight != null) {
                value.set(fallHeight, oldFallHeight);
                value.set(bucketFall, oldBucketFall);
                value.set(autoTool, oldAutoTool);
            }
        } catch (ReflectiveOperationException error) {
            BotEvents.add("warn", "movement", "Could not restore Baritone settings");
        }
    }
}
