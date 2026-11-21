package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import java.util.Comparator;
import java.util.Set;

/** Explicit inventory tasks; only the 36 main inventory slots are modified. */
final class BotInventory {
    record Request(String action, Item item, int count, String recipient) {}
    private static Request task;
    private static boolean paused;
    private static boolean manualPause,baritoneTask;
    private static Vec3d excessDropCenter;
    private static Vec3d lastRecipient;
    private static String completion = "Done";
    static void pause(boolean value) { manualPause=value; }
    private static int age, delivered, waitTicks;
    private static Vec3d spreadGoal;
    private static String world;
    private static Object session;
    private static volatile String status = "";
    private static final Set<String> ACTIONS = Set.of("deliver", "collect", "keep", "only", "drop", "gather", "spread", "stop");

    static Request parse(String input) {
        String[] parts = input.split("\\|", -1);
        if (parts.length == 4 && parts[0].equals("stop"))
            return new Request("stop", net.minecraft.item.Items.AIR, 1, "FuryMaster");
        if (parts.length != 4 || !ACTIONS.contains(parts[0]) || !parts[3].matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("Invalid inventory task or recipient");
        boolean needsItem = !Set.of("gather", "spread").contains(parts[0]);
        Identifier id = Identifier.tryParse(needsItem ? parts[1] : "minecraft:air");
        if (id == null || !Registries.ITEM.containsId(id)) throw new IllegalArgumentException("Unknown item ID");
        int count;
        try { count = needsItem ? (parts[0].equals("drop")&&(parts[2].isBlank()||parts[2].equalsIgnoreCase("all"))?2304:Integer.parseInt(parts[2])) : 1; } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid quantity"); }
        if (count < 1 || count > 2304) throw new IllegalArgumentException("Quantity must be 1–2304");
        return new Request(parts[0], Registries.ITEM.get(id), count, parts[3]);
    }

    static String status() { return status; }
    static void hold() { finish("Paused"); paused = true; }
    static void baritone(String command) { hold(); baritoneTask=true; status = "Baritone: " + command; }
    static void resume() { finish("Resumed formations"); paused = false; }
    static void disconnected() { finish("Cancelled: disconnected"); paused = false; }
    static boolean active() { return paused; }
    static boolean working() { return !manualPause&&(task!=null||baritoneTask); }
    static void evaded() { if(spreadGoal!=null){spreadGoal=null;waitTicks=0;} }
    static String recipient() { return task==null?"":task.recipient; }
    static void start(MinecraftClient client, Request request) {
        MiningTask.stop();
        finish("");
        manualPause=false;lastRecipient=null;excessDropCenter=null;completion="Done";
        if (request.action.equals("stop")) { paused = false; status = "Stopped"; return; }
        if (client.player == null || client.world == null) { status = "Not in game"; return; }
        paused = true;
        task = request;
        session = client.getNetworkHandler();
        age = delivered = waitTicks = 0;
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
        if (spreadGoal != null) {
            if (client.player.getPos().squaredDistanceTo(spreadGoal) < 2) {
                finish(completion); return;
            }
            if (++waitTicks > 160) { finish("Could not finish spreading out"); return; }
            path(client, spreadGoal); return;
        }
        PlayerEntity recipient = client.world.getPlayers().stream()
                .filter(p -> p.getGameProfile().getName().equalsIgnoreCase(task.recipient)).findFirst().orElse(null);
        int held = count(client, task.item);
        switch (task.action) {
            case "deliver", "gather" -> {
                if(recipient!=null)lastRecipient=recipient.getPos();
                if(task.action.equals("deliver")&&(delivered>=task.count||held==0)) {
                    completion="Dropped "+delivered+" of "+task.count+" near "+task.recipient;
                    spread(client);return;
                }
                if (recipient == null || recipient == client.player || !recipient.isAlive()) {
                    status = "Waiting for recipient in loaded area"; return;
                }
                if (client.player.squaredDistanceTo(recipient) > 6.25 || !client.player.canSee(recipient)) {
                    path(client, recipient.getPos()); return;
                }
                BaritoneClient.execute("stop");
                if (task.action.equals("gather")) { finish("Arrived"); return; }
                if (delivered >= task.count || held == 0) {
                    finish("Dropped " + delivered + " of " + task.count + " near " + task.recipient); return;
                }
                client.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, recipient.getEyePos());
                client.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                        client.player.getYaw(), client.player.getPitch(), client.player.isOnGround()));
                delivered += drop(client, task.item, task.count - delivered, false);
            }
            case "drop" -> {
                if (delivered >= task.count || held == 0) { completion="Dropped "+delivered;spread(client);return; }
                delivered += drop(client, task.item, task.count - delivered, false);
            }
            case "collect" -> {
                if (held >= task.count) {
                    if (held > task.count) { BaritoneClient.execute("stop"); drop(client, task.item, held - task.count, false); return; }
                    spread(client); return;
                }
                collectDropped(client);

            }
            case "keep", "only" -> {
                if (task.action.equals("only") && drop(client, task.item, Integer.MAX_VALUE, true) > 0) return;
                if (held < task.count) { collectDropped(client); return; }
                if (held > task.count) { BaritoneClient.execute("stop"); drop(client, task.item, held - task.count, false); return; }
                spread(client);
            }
            case "spread" -> spread(client);
            default -> finish("Stopped");
        }
    }

    private static void collectDropped(MinecraftClient client) {
        ItemEntity nearest=client.world.getEntitiesByClass(ItemEntity.class,
                client.player.getBoundingBox().expand(32),e->e.isAlive()&&e.getStack().isOf(task.item)).stream()
                .min(Comparator.comparingDouble(client.player::squaredDistanceTo)).orElse(null);
        if(nearest==null){status="Waiting for dropped "+task.item.getName().getString();return;}
        status="Collecting "+task.item.getName().getString();
        path(client,nearest.getPos());
    }
    private static int count(MinecraftClient client, Item item) {
        int total = 0;
        for (int i = 0; i < 36; ++i) if (client.player.getInventory().getStack(i).isOf(item))
            total += client.player.getInventory().getStack(i).getCount();
        return total;
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
        if (age % 20 != 0 && age != 1 && spreadGoal == null) return;
        if (spreadGoal != null && waitTicks % 20 != 0) return;
        var pos = net.minecraft.util.math.BlockPos.ofFloored(goal);
        BaritoneClient.execute("goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }
    private static void finish(String message) {
        if (task != null || baritoneTask) BaritoneClient.execute("stop");
        task = null;
        baritoneTask=false;
        spreadGoal = null;
        status = message;
        if (!message.isEmpty()) BotEvents.add(message.contains("Cannot") || message.contains("Timed") || message.contains("Could not") ? "warn" : "info", "inventory", message);
    }
}
