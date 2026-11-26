package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Explicit inventory tasks; only the 36 main inventory slots are modified. */
final class BotInventory {
    record Destination(String server, String dimension, Vec3d position, int controller) {
        boolean sameWorld(LocalFormation.Peer here) {
            return here != null && server.equals(here.server()) && dimension.equals(here.dimension());
        }
    }
    record Request(String action, Item item, int count, String recipient, Destination destination) {}
    private static Request task;
    private static boolean paused;
    private static boolean manualPause,baritoneTask;
    private static Vec3d excessDropCenter;
    private static Vec3d lastRecipient;
    private static CompletableFuture<Vec3d> locationRead;
    private static int recipientSeen, nextLocationRead;
    private static String completion = "Done";
    static void pause(boolean value) {
        if (value) routeRecovery.reset();
        manualPause = value;
    }
    private static int age, delivered, waitTicks;
    private static boolean projectDelivery;
    private static List<ProjectQueue.ResourceSite> supplyTrail = List.of();
    private static BaritoneSettings supplySettings;
    private static int supplyWaypoint, supplyProgressAt;
    private static Vec3d supplyProgress;
    private static Vec3d spreadGoal;
    private static final DroppedItemSearch pickup = new DroppedItemSearch();
    private static final StallRecovery routeRecovery = new StallRecovery();
    private static String world;
    private static Object session;
    private static volatile String status = "";
    private static final Set<String> ACTIONS = Set.of("deliver", "collect", "keep", "only", "drop", "gather", "spread", "stop");

    static Request parse(String input) {
        String[] parts = input.split("\\|", -1);
        if (parts.length == 4 && parts[0].equals("stop"))
            return new Request("stop", net.minecraft.item.Items.AIR, 1, "FuryMaster", null);
        if ((parts.length != 4 && parts.length != 10) || !ACTIONS.contains(parts[0]) || !parts[3].matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("Invalid inventory task or recipient");
        boolean needsItem = !Set.of("gather", "spread").contains(parts[0]);
        Identifier id = Identifier.tryParse(needsItem ? parts[1] : "minecraft:air");
        if (id == null || !Registries.ITEM.containsId(id)) throw new IllegalArgumentException("Unknown item ID");
        int count;
        try { count = needsItem ? (parts[0].equals("drop")&&(parts[2].isBlank()||parts[2].equalsIgnoreCase("all"))?2304:Integer.parseInt(parts[2])) : 1; } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid quantity"); }
        if (count < 1 || count > 2304) throw new IllegalArgumentException("Quantity must be 1–2304");
        Destination destination = null;
        if (parts.length == 10) {
            if (!parts[0].equals("gather") && !parts[0].equals("deliver")) throw new IllegalArgumentException("Unexpected recipient coordinates");
            try {
                var location = new Vec3d(Double.parseDouble(parts[6]), Double.parseDouble(parts[7]), Double.parseDouble(parts[8]));
                int port = Integer.parseInt(parts[9]);
                if (!validLocation(location) || parts[4].isBlank() || Identifier.tryParse(parts[5]) == null
                        || port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid recipient coordinates");
                destination = new Destination(parts[4], parts[5], location, port);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid recipient coordinates");
            }
        }
        return new Request(parts[0], Registries.ITEM.get(id), count, parts[3], destination);
    }

    static boolean validLocation(Vec3d point) {
        return Math.abs(point.x) <= 30_000_000 && Math.abs(point.z) <= 30_000_000
                && point.y >= -2048 && point.y <= 2048;
    }

    static String status() { return status; }
    static int deliveredItems() { return delivered; }
    static void hold() { finish("Paused"); paused = true; }
    static void baritone(String command) { hold(); baritoneTask=true; status = "Baritone: " + command; }
    static void resume() { finish("Resumed formations"); paused = false; }
    static void disconnected() { finish("Cancelled: disconnected"); paused = false; }
    static boolean active() { return paused; }
    static boolean working() { return !manualPause&&(task!=null||baritoneTask); }
    static void evaded() {
        if (spreadGoal != null) { spreadGoal = null; waitTicks = 0; }
        pickup.repath();
    }
    static String recipient() { return task==null?"":task.recipient; }
    static void start(MinecraftClient client, Request request) {
        ProjectWorker.stop(client);
        MiningTask.stop();
        begin(client, request);
        projectDelivery = false;
    }

    static void startProjectDelivery(MinecraftClient client, Item item, int quantity, String recipient,
                                     Destination destination, List<ProjectQueue.ResourceSite> waypoints) {
        begin(client, new Request("deliver", item, quantity, recipient, destination));
        projectDelivery = true;
        if (task == null || waypoints.isEmpty()) return;
        try {
            supplySettings = new BaritoneSettings(false);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            finish("Cannot follow the shared supply trail");
            return;
        }
        supplyTrail = waypoints;
        supplyProgress = client.player.getPos();
        BotEvents.add("info", "inventory", "Following " + waypoints.size() + " shared supply waypoints");
    }

    private static void begin(MinecraftClient client, Request request) {
        finish("");
        delivered = 0;
        manualPause=false;lastRecipient=null;excessDropCenter=null;completion="Done";
        if (request.action.equals("stop")) { paused = false; status = "Stopped"; return; }
        if (client.player == null || client.world == null) { status = "Not in game"; return; }
        paused = true;
        task = request;
        session = client.getNetworkHandler();
        age = delivered = waitTicks = recipientSeen = nextLocationRead = 0;
        locationRead = null;
        if (request.destination != null && request.destination.sameWorld(LocalFormation.here())) {
            lastRecipient = request.destination.position;
        }
        spreadGoal = null;
        world = client.world.getRegistryKey().getValue().toString();
        status = request.action + " active";
        BotEvents.add("info", "inventory", status);
    }

    static void tick(MinecraftClient client) {
        if (task == null || manualPause) return;
        if (client.player == null || client.world == null || session != client.getNetworkHandler() || !world.equals(client.world.getRegistryKey().getValue().toString())) {
            finish("Cancelled: world changed"); paused = false; return;
        }
        if (++age > 2400) { finish("Timed out"); return; }
        if (!client.player.isAlive()) return;
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) {
            status = "Close the bot's container to continue"; return;
        }
        if (followSupplyTrail(client)) return;
        if (spreadGoal != null) {
            if (client.player.getPos().squaredDistanceTo(spreadGoal) < 2) {
                finish(completion); return;
            }
            if (++waitTicks > 400) { finish("Could not finish spreading out"); return; }
            path(client, spreadGoal); return;
        }
        PlayerEntity recipient = client.world.getPlayers().stream()
                .filter(p -> p.getGameProfile().getName().equalsIgnoreCase(task.recipient)).findFirst().orElse(null);
        int held = count(client, task.item);
        switch (task.action) {
            case "deliver", "gather" -> {
                boolean visible = recipient != null && recipient != client.player && recipient.isAlive();
                if (visible) {
                    lastRecipient = recipient.getPos();
                    recipientSeen = age;
                    locationRead = null;
                }
                if(task.action.equals("deliver")&&(delivered>=task.count||held==0)) {
                    completion="Dropped "+delivered+" of "+task.count+" near "+task.recipient;
                    spread(client);return;
                }
                if (!visible) {
                    followCoordinates(client);
                    return;
                }
                if (client.player.squaredDistanceTo(recipient) > 6.25 || !client.player.canSee(recipient)) {
                    status = "Approaching " + task.recipient;
                    path(client, recipient.getPos()); return;
                }
                routeRecovery.reset();
                BaritoneClient.execute("stop");
                if (task.action.equals("gather")) { finish("Arrived"); return; }
                if (delivered >= task.count || held == 0) {
                    finish("Dropped " + delivered + " of " + task.count + " near " + task.recipient); return;
                }
                client.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, recipient.getEyePos());
                client.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                        client.player.getYaw(), client.player.getPitch(), client.player.isOnGround()));
                delivered += projectDelivery ? dropProjectItem(client, task.item)
                        : drop(client, task.item, task.count - delivered, false);
            }
            case "drop" -> {
                if (delivered >= task.count || held == 0) { completion="Dropped "+delivered;spread(client);return; }
                delivered += drop(client, task.item, task.count - delivered, false);
            }
            case "collect", "keep", "only" -> {
                if (task.action.equals("only") && drop(client, task.item, Integer.MAX_VALUE, true) > 0) return;
                if (held < task.count) { collectDropped(client, held); return; }
                pickup.stop();
                if (held > task.count) {
                    drop(client, task.item, held - task.count, false);
                    return;
                }
                completion = "Kept " + held + " " + task.item.getName().getString();
                spread(client);
            }
            case "spread" -> spread(client);
            default -> finish("Stopped");
        }
    }

    private static boolean followSupplyTrail(MinecraftClient client) {
        while (supplyWaypoint < supplyTrail.size()) {
            var step = supplyTrail.get(supplyWaypoint);
            var target = new BlockPos(step.x(), step.y(), step.z());
            var feet = client.player.getPos();
            if (feet.squaredDistanceTo(Vec3d.ofBottomCenter(target)) <= 1.5) {
                supplyWaypoint++;
                supplyProgressAt = age;
                supplyProgress = feet;
                continue;
            }
            if (feet.squaredDistanceTo(supplyProgress) >= 1) {
                supplyProgress = feet;
                supplyProgressAt = age;
            }
            if (age - supplyProgressAt >= 160) {
                finish("Could not reach a shared supply waypoint");
                return true;
            }
            if ((age == 1 || age % 10 == 0) && !BaritoneClient.pursuing(target)
                    && !BaritoneClient.goTo(target)) finish("Cannot follow the shared supply trail");
            else status = "Following shared supply waypoint " + (supplyWaypoint + 1) + "/" + supplyTrail.size();
            return true;
        }
        return false;
    }

    private static void followCoordinates(MinecraftClient client) {
        var destination = task.destination;
        if (destination == null || !destination.sameWorld(LocalFormation.here())) {
            status = "Waiting for recipient in loaded area";
            return;
        }
        if (locationRead != null && locationRead.isDone()) {
            var point = locationRead.getNow(null);
            if (point != null) {
                lastRecipient = point;
                recipientSeen = age;
            }
            locationRead = null;
        }
        if (locationRead == null && age >= nextLocationRead) {
            nextLocationRead = age + 20;
            locationRead = BotTracker.recipientLocation(destination, task.recipient);
        }
        if (lastRecipient != null && age - recipientSeen <= 240) {
            status = "Following coordinates to " + task.recipient;
            path(client, lastRecipient);
        } else {
            if (!status.equals("Waiting for fresh recipient coordinates")) BaritoneClient.execute("stop");
            status = "Waiting for fresh recipient coordinates";
        }
    }

    private static void collectDropped(MinecraftClient client, int held) {
        status = switch (pickup.tick(client, task.item)) {
            case SEARCHING -> "Looking for dropped ";
            case APPROACHING -> "Collecting ";
            case UNAVAILABLE -> "Cannot collect: ";
        } + task.item.getName().getString() + " (" + held + "/" + task.count + ")";
        if (status.startsWith("Cannot")) finish(status + " — Baritone unavailable");
    }

    private static int count(MinecraftClient client, Item item) {
        int total = 0;
        for (int i = 0; i < 36; ++i) if (client.player.getInventory().getStack(i).isOf(item))
            total += client.player.getInventory().getStack(i).getCount();
        return total;
    }
    private static int dropProjectItem(MinecraftClient client, Item item) {
        var player = client.player;
        var inventory = player.getInventory();
        int selected = -1;
        int bestUses = 20;
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            int uses = stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
            if (stack.isOf(item) && uses > bestUses) {
                selected = slot;
                bestUses = uses;
            }
        }
        if (selected < 0) return 0;
        if (selected >= 9) {
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, selected,
                    inventory.selectedSlot, SlotActionType.SWAP, player);
            return 0;
        }
        inventory.selectedSlot = selected;
        player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(selected));
        return player.dropSelectedItem(false) ? 1 : 0;
    }

    private static int drop(MinecraftClient client, Item item, int amount, boolean otherItems) {
        if (client.interactionManager == null || amount <= 0) return 0;
        int total = 0;
        // Bound click traffic while finishing partial stacks before their pickup delay expires.
        for (int clicks = 0; clicks < 8 && total < amount; ++clicks) {
            boolean found = false;
            for (int i = 0; i < 36; ++i) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isEmpty() || stack.isOf(item) == otherItems) continue;
                int take = stack.getCount() <= amount - total ? stack.getCount() : 1;
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId,
                        i < 9 ? 36 + i : i, take == stack.getCount() ? 1 : 0, SlotActionType.THROW, client.player);
                total += take;
                found = true;
                break;
            }
            if (!found) break;
        }
        if(total>0&&task!=null&&!task.action.equals("deliver")) {
            Vec3d direction=client.player.getRotationVector().multiply(1,0,1);
            if(direction.lengthSquared()<.001)direction=new Vec3d(0,0,1);
            excessDropCenter=client.player.getPos().add(direction.normalize().multiply(1.5));
        }
        return total;
    }
    private static void spread(MinecraftClient client) {
        if (!BaritoneClient.available()) { finish("Cannot spread: Baritone unavailable"); return; }
        Vec3d center=excessDropCenter!=null?excessDropCenter:lastRecipient;
        if(center==null&&task!=null) center=client.world.getPlayers().stream().filter(p->p.getGameProfile().getName().equalsIgnoreCase(task.recipient)).map(p->p.getPos()).findFirst().orElse(null);
        Vec3d away=center==null?Vec3d.ZERO:client.player.getPos().subtract(center).multiply(1,0,1);
        if(away.lengthSquared()<0.01){double angle=(client.player.getUuid().hashCode()&1023)*Math.PI/512;away=new Vec3d(Math.cos(angle),0,Math.sin(angle));}
        away=away.normalize();
        double lane=((Integer.getInteger("botfury.port",8765)%3)-1)*1.25;
        spreadGoal=client.player.getPos().add(away.multiply(6)).add(-away.z*lane,0,away.x*lane);
        status=completion.equals("Done")?"Moving outward":completion+" · moving outward";
        path(client, spreadGoal);
    }
    private static void path(MinecraftClient client, Vec3d goal) {
        if (!BaritoneClient.available()) { finish("Cannot move: Baritone unavailable"); return; }
        var pos = net.minecraft.util.math.BlockPos.ofFloored(goal);
        var recovery = routeRecovery.tick(client, pos, candidate -> true);
        if (recovery == StallRecovery.State.DETOUR) {
            status = "Clearing a stalled inventory route";
            return;
        }
        if (recovery != StallRecovery.State.REPLAN) {
            if (age % 20 != 0 && age != 1 && spreadGoal == null) return;
            if (spreadGoal != null && waitTicks % 20 != 0) return;
        }
        BaritoneClient.execute("goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }
    private static void finish(String message) {
        routeRecovery.reset();
        if (task != null || baritoneTask) BaritoneClient.execute("stop");
        if (supplySettings != null) supplySettings.close();
        supplySettings = null;
        supplyTrail = List.of();
        supplyWaypoint = supplyProgressAt = 0;
        supplyProgress = null;
        pickup.reset();
        task = null;
        locationRead = null;
        baritoneTask=false;
        spreadGoal = null;
        status = message;
        if (!message.isEmpty()) BotEvents.add(message.contains("Cannot") || message.contains("Timed") || message.contains("Could not") ? "warn" : "info", "inventory", message);
    }
}
