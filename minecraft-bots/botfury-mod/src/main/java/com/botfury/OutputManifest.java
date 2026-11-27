package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A finite set of stock targets, with each item's batches able to run alongside the other items. */
final class OutputManifest {
    private static final Gson JSON = new Gson();
    private static final int MAX_ITEMS = 16;
    private static final int BATCH_SIZE = 64;

    private record Target(ProjectItem item, int count, int batchLimit, List<MaterialRecipe.Batch> batches) {}

    private OutputManifest() {}

    static int submit(MinecraftClient client, String requested) {
        var here = LocalFormation.here();
        int port = BotFuryMod.controlPort();
        if (here == null || client.world == null || port < 1024 || Boolean.getBoolean("botfury.managedHttp")) return 0;
        try {
            int crew = (int) BotTracker.targets().stream().filter(bot -> bot.fresh() && bot.sameWorld()
                    && bot.connected() && bot.alive()).count();
            var targets = parse(client, requested, crew);
            boolean newStorage = !(client.crosshairTarget instanceof BlockHitResult hit)
                    || hit.getType() != HitResult.Type.BLOCK || !client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.CHEST);
            var origin = client.player.getBlockPos();
            var area = new ProjectGather.Area(origin, origin);
            ProjectChest.Storage storage;
            ProjectBlock chest;
            if (newStorage) {
                chest = ProjectWorkstations.chooseSite(client, origin, area, Identifier.of("minecraft", "chest"));
                storage = new ProjectChest.Storage(chest.target(), null);
            } else {
                storage = ProjectChest.aimedStorage(client, client.crosshairTarget);
                chest = storage.approach(client, ignored -> true);
                if (chest == null) throw new IllegalArgumentException("No clear stand next to the chest");
            }
            if (targets.stream().mapToInt(OutputManifest::requiredSlots).sum() > storage.slots()) {
                throw new IllegalArgumentException("Manifest can exceed this chest's capacity");
            }

            var table = OutputOrder.findStation(client, storage.key(), Identifier.of("minecraft", "crafting_table"));
            var furnace = OutputOrder.findStation(client, storage.key(), Identifier.of("minecraft", "furnace"));
            area = StructureSupply.union(storage.include(new ProjectGather.Area(chest.target(), chest.target())), chest, table, furnace);
            var jobs = jobs(client, targets, storage, chest, table, furnace, area, newStorage);
            if (jobs.size() > ProjectQueue.MAX_JOBS && crew > 1) {
                targets = parse(client, requested, 1);
                jobs = jobs(client, targets, storage, chest, table, furnace, area, newStorage);
            }
            String id = "manifest-" + UUID.randomUUID().toString().substring(0, 8);
            ProjectBroker.submitManifest(id, here, jobs, ProjectBlock.key(storage.key()));
            BotUiConfig.trackRequest(targets.stream().flatMap(target -> target.item.ids().stream())
                    .map(Identifier::toString).toList());
            int workers = BotTracker.sendSelected("project:worker " + port + " " + id);
            ClientActions.message(client, "Queued " + id + " for " + workers + " bots at " + storage.key().toShortString());
            return 1;
        } catch (IOException | IllegalArgumentException error) {
            ClientActions.message(client, "Could not queue output manifest. " + error.getMessage());
            return 0;
        }
    }

    private static List<ProjectQueue.Work> jobs(MinecraftClient client, List<Target> targets,
                                                 ProjectChest.Storage storage, ProjectBlock chest,
                                                 ProjectBlock table, ProjectBlock furnace, ProjectGather.Area area,
                                                 boolean newStorage) throws IOException {
        var recipes = targets.stream().flatMap(target -> target.batches.stream()).map(MaterialRecipe.Batch::plan).toList();
        var stations = ProjectWorkstations.prepare(client, storage.key(), area, table, furnace,
                newStorage || recipes.stream().anyMatch(MaterialRecipe.Plan::needsTable),
                recipes.stream().anyMatch(MaterialRecipe.Plan::needsFurnace));
        var jobs = new ArrayList<>(stations.jobs());
        if (newStorage) jobs.addAll(stations.storageJobs(client, chest));
        var delivered = new ArrayList<String>();
        for (int index = 0; index < targets.size(); index++) {
            delivered.add(addBatches(jobs, targets.get(index), index, storage, chest, stations, newStorage));
        }
        for (int index = 0; index < targets.size(); index++) {
            var target = targets.get(index);
            jobs.add(transfer("verify-stock-" + index, storage, chest, target.item, target.count,
                    ProjectChest.Direction.VERIFY_STOCK, delivered, null));
        }
        return jobs;
    }

    static int batchLimit(int quantity, int crew) {
        int workers = Math.max(1, crew);
        // Amortize travel and tool setup across at least eight requested items when possible.
        return Math.min(quantity, Math.min(BATCH_SIZE, Math.max(8, (quantity + workers - 1) / workers)));
    }

    private static List<Target> parse(MinecraftClient client, String requested, int crew) {
        String[] entries = requested.strip().split("\\s+");
        if (entries.length > MAX_ITEMS) throw new IllegalArgumentException("Use at most " + MAX_ITEMS + " item totals");
        var items = new HashSet<Identifier>();
        var targets = new ArrayList<Target>();
        var recipes = new MaterialRecipe(client, true, true);
        for (String entry : entries) {
            String[] parts = entry.split("=", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Use item=count for each target");
            var item = ProjectItem.parse(parts[0]);
            int count = Integer.parseInt(parts[1]);
            if (count < 1 || count > 3456) throw new IllegalArgumentException("Choose a total between 1 and 3456");
            if (!java.util.Collections.disjoint(items, item.ids())) throw new IllegalArgumentException("Item targets overlap");
            items.addAll(item.ids());
            int limit = batchLimit(count, crew);
            var batches = item.grouped() ? List.<MaterialRecipe.Batch>of()
                    : recipes.batches(item.ids().get(0), count, Math.min(limit, 9 * item.sample().getMaxCount()));
            targets.add(new Target(item, count, limit, batches));
        }
        return List.copyOf(targets);
    }

    private static int requiredSlots(Target target) {
        int stack = target.item.sample().getMaxCount();
        // Different variants cannot share a slot even when they count toward one target.
        return Math.min(target.count, (target.count + stack - 1 + (stack - 1) * (target.item.ids().size() - 1)) / stack);
    }

    private static String addBatches(List<ProjectQueue.Work> jobs, Target target, int index,
                                     ProjectChest.Storage storage, ProjectBlock chest, ProjectWorkstations stations,
                                     boolean newStorage) {
        String previousDeposit = null;
        int total = 0;
        int batchIndex = 0;
        while (total < target.count) {
            String prefix = "stock-" + index + "-" + batchIndex + "-";
            var deps = new ArrayList<String>();
            if (newStorage) deps.add("setup-storage");
            String produced;
            if (target.item.grouped()) {
                int count = Math.min(target.batchLimit, target.count - total);
                var area = stations.protectedArea();
                String payload = JSON.toJson(Map.of("resource", target.item.key(), "variant", "both",
                        "item", target.item.key(), "count", count, "exclude", Map.of(
                        "min", StructureSupply.position(area.min()), "max", StructureSupply.position(area.max()))));
                produced = prefix + "gather";
                jobs.add(new ProjectQueue.Work(produced, ProjectQueue.Kind.GATHER, payload, deps, Set.of()));
                total += count;
            } else {
                var batch = target.batches.get(batchIndex);
                var recipe = batch.plan();
                deps.addAll(stations.prerequisites(recipe));
                var production = recipe.jobs(prefix + "produce-", stations.protectedArea(),
                        recipe.needsTable() ? stations.table() : null, stations.furnace(), deps);
                jobs.addAll(production);
                produced = production.get(production.size() - 1).id();
                total += batch.count();
            }
            var depositNeeds = previousDeposit == null ? List.of(produced) : List.of(produced, previousDeposit);
            previousDeposit = prefix + "deposit";
            jobs.add(transfer(previousDeposit, storage, chest, target.item, total, ProjectChest.Direction.DEPOSIT,
                    depositNeeds, produced));
            batchIndex++;
        }
        return previousDeposit;
    }

    private static ProjectQueue.Work transfer(String id, ProjectChest.Storage storage, ProjectBlock chest,
                                               ProjectItem item, int count, ProjectChest.Direction direction,
                                               List<String> deps, String workerFrom) {
        JsonObject payload = MaterialRecipe.stationPayload(chest);
        payload.addProperty("direction", direction.name());
        payload.addProperty("item", item.key());
        payload.addProperty("stock", count);
        storage.addPartner(payload);
        return ProjectChest.parse(payload.toString()).reserve(new ProjectQueue.Work(id,
                ProjectQueue.Kind.TRANSFER, payload.toString(), deps, Set.of(), workerFrom));
    }
}
