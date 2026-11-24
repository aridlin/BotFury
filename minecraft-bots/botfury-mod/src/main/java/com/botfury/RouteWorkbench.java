package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;

final class RouteWorkbench {
    static final Identifier TABLE = Identifier.of("minecraft", "crafting_table");
    private static final ProjectItem LOGS = ProjectItem.parse("any_logs");
    private ProjectProduction production;
    private boolean gathering;
    private String status;

    static boolean canSupply(MinecraftClient client) {
        if (client.player.getInventory().contains(Items.CRAFTING_TABLE.getDefaultStack())
                || ProjectProduction.canCraft(client, TABLE) || FieldWorkstation.plankRecipe(client, 4) != null) return true;
        var pos = client.player.getBlockPos();
        return client.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - pos.getY() <= 12;
    }

    String status() { return status; }

    ProjectProduction.Result tick(MinecraftClient client, long now, int coordinator) {
        if (client.player.getInventory().contains(Items.CRAFTING_TABLE.getDefaultStack())) {
            return new ProjectProduction.Result(true, "Observed a portable workbench in inventory");
        }
        if (production == null) {
            var spec = ProjectProduction.canCraft(client, TABLE)
                    ? new ProjectProduction.Spec(null, ProjectQueue.Kind.CRAFT, TABLE, null, null, 1)
                    : FieldWorkstation.plankRecipe(client, 4);
            if (spec == null) {
                if (!gathering) {
                    MiningTask.startProject(client, new MiningTask.Job("any_logs", "both", coordinator), null, null, LOGS);
                    gathering = true;
                }
                if (!MiningTask.active()) return new ProjectProduction.Result(false, MiningTask.lastStatus());
                status = "Gathering wood for an emergency workbench";
                MiningTask.tick(client);
                return null;
            }
            if (gathering) {
                MiningTask.stop();
                gathering = false;
            }
            production = new ProjectProduction(spec);
        }
        status = "Crafting an emergency workbench";
        var result = production.tick(client, now);
        if (result == null) return null;
        production.close(client);
        production = null;
        return result.success() && !client.player.getInventory().contains(Items.CRAFTING_TABLE.getDefaultStack())
                ? null : result;
    }

    void close(MinecraftClient client) {
        if (gathering) MiningTask.stop();
        if (production != null) production.close(client);
    }
}
