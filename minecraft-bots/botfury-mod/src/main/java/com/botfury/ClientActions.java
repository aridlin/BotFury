package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

final class ClientActions {
    static void action(MinecraftClient client, String action) {
        if (client.player == null) return;
        switch (action) {
            case "saturn", "orbit" -> LocalFormation.activate(client, "orbit");
            case "scatter" -> LocalFormation.activate(client, "scatter");
            case "stop" -> {
                BotTracker.all().forEach(bot -> BotTracker.command(bot.port(), "control:stop"));
                LocalFormation.pauseMovement();
                BotInventory.hold();
                LocalFormation.fleetCommand("control:stop");
            }
            case "lightning" -> {
                LivingEntity target = FormationClient.lightningTarget(client);
                if (target == null) message(client, "Aim at a visible living target.");
                else LocalFormation.strike(client, target);
            }
            case "gather", "spread" -> inventory(client, action, "minecraft:air", 1);
            case "hud" -> BotUiConfig.toggle();
            case "esp" -> BotUiConfig.toggleEsp();
            case "bots" -> FormationClient.openRoster();
            case "resume" -> BotTracker.manageSelected("resume");
            case "status" -> message(client, "BotFury · " + LocalFormation.mode() + " · " + LocalFormation.peers()
                    + " bots · " + LocalFormation.stage());
            case "help" -> message(client, "/bf saturn | lightning | stop | scatter | gather | spread | hud | status\n"
                    + "/bf inventory <collect|deliver|keep|only|drop> <item> <count>\n/bf friend <add|remove> <name>\n/bf tutorial");
            default -> message(client, "Unknown BotFury action. Use /bf help.");
        }
    }
    static String heldItem(MinecraftClient client) {
        var stack = client.player == null ? Items.IRON_PICKAXE.getDefaultStack() : client.player.getMainHandStack();
        return Registries.ITEM.getId(stack.isEmpty() ? Items.IRON_PICKAXE : stack.getItem()).toString();
    }
    static String taskItem(MinecraftClient client) {
        var id=net.minecraft.util.Identifier.tryParse(BotUiConfig.item);
        return id!=null&&Registries.ITEM.containsId(id)?id.toString():heldItem(client);
    }
    static void inventory(MinecraftClient client, String action, String item, int count) {
        if (client.player == null) return;
        String name = client.player.getGameProfile().getName();
        try {
            BotInventory.parse(action + "|" + item + "|" + count + "|" + name);
            String command="inventory:" + action + "|" + item + "|" + count + "|" + name;
            var here = LocalFormation.here();
            int port = BotFuryMod.controlPort();
            String coordinates = "";
            if ((action.equals("gather") || action.equals("deliver")) && here != null && port >= 1024) {
                coordinates = "|" + here.server() + "|" + here.dimension() + "|" + here.x() + "|" + here.y() + "|" + here.z() + "|" + port;
            }
            if (BotTracker.targets().isEmpty()) LocalFormation.fleetCommand(command);
            else if (BotTracker.sendSelected(command, coordinates) > 0 && !item.equals("minecraft:air")) {
                BotUiConfig.trackRequest(java.util.List.of(item));
            }
        } catch (IllegalArgumentException error) { message(client, error.getMessage()); }
    }
    static void friend(MinecraftClient client, String operation, String name) {
        try {
            BotFriends.edit(operation, name);
            LocalFormation.fleetCommand("friends:" + operation + ":" + name);
            message(client, operation.equals("add") ? "Friend added: " + name : "Friend removed: " + name);
        } catch (Exception error) { message(client, error.getMessage()); }
    }
    static void message(MinecraftClient client, String message) {
        if (message == null) message = "Action could not be completed";
        String level = message.startsWith("Aim") || message.startsWith("Enable") || message.startsWith("Could")
                || message.startsWith("Unknown") || message.startsWith("That target") ? "warn" : "info";
        BotEvents.add(level, "controls", message);
        if (client.player != null) client.player.sendMessage(Text.literal(message), false);
    }
}
