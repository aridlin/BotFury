package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Duration;

/** Recovers a field workbench placed by this worker, using normal mining and item pickup. */
final class WorkbenchPickup {
    private final BlockPos target;
    private final long deadline;
    private final int previousCount;
    private final DroppedItemSearch pickup = new DroppedItemSearch(5);
    private boolean breaking;

    WorkbenchPickup(BlockPos target, long now, MinecraftClient client) {
        this.target = target;
        deadline = now + Duration.ofSeconds(12).toNanos();
        previousCount = client.player.getInventory().count(Items.CRAFTING_TABLE);
    }

    boolean tick(MinecraftClient client, long now) {
        if (now >= deadline || BuildProtection.blocked(target)
                || !client.world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) return false;
        if (client.player.getInventory().count(Items.CRAFTING_TABLE) > previousCount) {
            BotEvents.add("info", "projects", "Recovered the portable workbench");
            return false;
        }
        var state = client.world.getBlockState(target);
        if (state.isAir()) {
            if (breaking) {
                client.interactionManager.cancelBlockBreaking();
                breaking = false;
            }
            pickup.tick(client, stack -> stack.isOf(Items.CRAFTING_TABLE), Vec3d.ofCenter(target));
            return true;
        }
        if (!state.isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)
                || client.world.getPlayers().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                        && player.getBlockPos().down().equals(target))) return false;
        var hit = ProjectWorker.excavationHit(client, target);
        if (hit == null) return false;
        breaking = true;
        client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, hit.getPos());
        if (client.interactionManager.updateBlockBreakingProgress(target, hit.getSide())) client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    void close(MinecraftClient client) {
        if (breaking && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        pickup.stop();
    }
}
