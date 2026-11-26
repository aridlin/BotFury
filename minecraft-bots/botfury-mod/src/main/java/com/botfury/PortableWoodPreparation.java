package com.botfury;

import java.time.Duration;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;

final class PortableWoodPreparation {
    static final Identifier TABLE = Identifier.of("minecraft", "crafting_table");
    private static final ProjectItem LOGS = ProjectItem.parse("any_logs");
    private final Item product;
    private final Identifier recipe;
    private final int targetCount;
    private final ProjectGather.Area excluded;
    private final ProjectQueue.ResourceSite site;
    private ProjectProduction production;
    private boolean craftingProduct;
    private long belowSurfaceSince;
    private boolean gathering;
    private String status;

    PortableWoodPreparation() { this(Items.CRAFTING_TABLE, 1, null, null); }

    PortableWoodPreparation(Item product, int targetCount, ProjectGather.Area excluded, ProjectQueue.ResourceSite site) {
        this.product = product;
        recipe = Registries.ITEM.getId(product);
        this.targetCount = targetCount;
        this.excluded = excluded;
        this.site = site;
    }

    static boolean canSupply(MinecraftClient client) {
        if (client.player.getInventory().contains(Items.CRAFTING_TABLE.getDefaultStack())
                || ProjectProduction.canCraft(client, TABLE) || FieldWorkstation.plankRecipe(client, 4) != null) return true;
        return nearLoadedSurface(client);
    }

    private static boolean nearLoadedSurface(MinecraftClient client) {
        var pos = client.player.getBlockPos();
        if (!client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        // MOTION_BLOCKING is sent to the client. The no-leaves variant is server-side.
        int depth = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ()) - pos.getY();
        return depth >= 0 && depth <= 12;
    }

    String status() { return status; }

    boolean needsSticks(MinecraftClient client) {
        return product == Items.STICK && production == null && client.player.getInventory().count(Items.STICK) < targetCount;
    }

    void pauseGathering() {
        if (gathering) MiningTask.stop();
        gathering = false;
    }

    ProjectProduction.Result tick(MinecraftClient client, long now, int coordinator) {
        var inventory = client.player.getInventory();
        int held = inventory.count(product);
        if (held >= targetCount) {
            return new ProjectProduction.Result(true, "Observed " + held + " " + recipe + " in worker inventory");
        }
        boolean sticks = product == Items.STICK;
        if (production == null) {
            craftingProduct = ProjectProduction.canCraft(client, recipe);
            var spec = craftingProduct
                    ? new ProjectProduction.Spec(null, ProjectQueue.Kind.CRAFT, recipe, null, null,
                            Math.min(targetCount, held + (sticks ? 4 : 1)))
                    : FieldWorkstation.plankRecipe(client, sticks ? 2 : 4);
            if (spec == null) {
                if (!sticks && !nearLoadedSurface(client)) {
                    if (belowSurfaceSince == 0) belowSurfaceSince = now;
                    if (now - belowSurfaceSince >= Duration.ofSeconds(6).toNanos()) {
                        return new ProjectProduction.Result(false, "Underground without materials for a workbench");
                    }
                } else belowSurfaceSince = 0;
                if (!gathering) {
                    MiningTask.startProject(client, new MiningTask.Job("any_logs", "both", coordinator), excluded, site, LOGS, 0);
                    gathering = true;
                }
                if (!MiningTask.active()) return new ProjectProduction.Result(false, MiningTask.lastStatus());
                MiningTask.tick(client);
                status = MiningTask.status();
                return null;
            }
            belowSurfaceSince = 0;
            if (gathering) {
                MiningTask.stop();
                gathering = false;
            }
            production = new ProjectProduction(spec);
        }
        status = sticks ? craftingProduct ? "Crafting sticks" : "Crafting planks for sticks" : "Crafting an emergency workbench";
        var result = production.tick(client, now);
        if (result == null) return null;
        production.close(client);
        production = null;
        return result.success() ? null : result;
    }

    void close(MinecraftClient client) {
        pauseGathering();
        if (production != null) production.close(client);
    }
}
