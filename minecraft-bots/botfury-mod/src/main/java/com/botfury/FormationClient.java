package com.botfury;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.Entity;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

final class FormationClient {
    static KeyBinding wheel, quick;
    private static boolean pendingWheel;
    private FormationClient() {}
    private static int submitStructure(
            com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
            boolean supplied) {
        return StructureProject.submit(context.getSource().getClient(), StringArgumentType.getString(context, "file"),
                new net.minecraft.util.math.BlockPos(IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z")), supplied);
    }

    static void register() {
        BotUiConfig.load();
        wheel = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.botfury.wheel", InputUtil.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_5, "category.botfury"));
        quick = key("quick", GLFW.GLFW_KEY_K);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var root = ClientCommandManager.literal("bf").executes(context -> { open(context.getSource().getClient()); return 1; });
            for (String action : new String[]{"saturn", "lightning", "stop", "scatter", "gather", "spread", "hud", "esp", "bots", "resume", "status", "help"})
                root.then(ClientCommandManager.literal(action).executes(context -> { ClientActions.action(context.getSource().getClient(), action); return 1; }));
            var inventory = ClientCommandManager.literal("inventory");
            for (String action : new String[]{"collect", "deliver", "keep", "only", "drop"}) {
                var itemArgument = ClientCommandManager.argument("item", net.minecraft.command.argument.IdentifierArgumentType.identifier())
                        .suggests((context, builder) -> net.minecraft.command.CommandSource.suggestIdentifiers(
                                net.minecraft.registry.Registries.ITEM.getIds(), builder))
                        .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 2304))
                                .executes(context -> {
                                    ClientActions.inventory(context.getSource().getClient(), action,
                                            context.getArgument("item", net.minecraft.util.Identifier.class).toString(), IntegerArgumentType.getInteger(context, "count"));
                                    return 1;
                                }));
                if(action.equals("drop")){
                    com.mojang.brigadier.Command<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dropAll = context -> {
                        ClientActions.inventory(context.getSource().getClient(), "drop",
                                context.getArgument("item",net.minecraft.util.Identifier.class).toString(),2304);
                        return 1;
                    };
                    itemArgument.executes(dropAll).then(ClientCommandManager.literal("all").executes(dropAll));
                }
                inventory.then(ClientCommandManager.literal(action).then(itemArgument));
            }
            root.then(inventory);
            var projectCoordinates = ClientCommandManager.argument("z", IntegerArgumentType.integer())
                    .executes(context -> submitStructure(context, false))
                    .then(ClientCommandManager.literal("supplied").executes(context -> submitStructure(context, true)));
            var projectBuild = ClientCommandManager.literal("build")
                    .then(ClientCommandManager.argument("file", StringArgumentType.string())
                            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                            .then(projectCoordinates))));
            root.then(ClientCommandManager.literal("project").then(projectBuild)
                    .then(ClientCommandManager.literal("workers").executes(context -> {
                        int port = BotFuryMod.controlPort();
                        if (port < 1024 || Boolean.getBoolean("botfury.managedHttp")) return 0;
                        int sent = BotTracker.sendSelected("project:worker " + port);
                        ClientActions.message(context.getSource().getClient(),
                                sent > 0 ? "Project worker requested for " + sent + " bot(s)" : "No connected bots selected");
                        return sent > 0 ? 1 : 0;
                    })));
            var outputItem = ClientCommandManager.argument("item", net.minecraft.command.argument.IdentifierArgumentType.identifier())
                    .suggests((context, builder) -> net.minecraft.command.CommandSource.suggestIdentifiers(
                            net.minecraft.registry.Registries.ITEM.getIds(), builder))
                    .executes(context -> OutputOrder.submit(context.getSource().getClient(),
                            context.getArgument("item", net.minecraft.util.Identifier.class), 10))
                    .then(ClientCommandManager.argument("batch", IntegerArgumentType.integer(1, 64))
                            .executes(context -> OutputOrder.submit(context.getSource().getClient(),
                                    context.getArgument("item", net.minecraft.util.Identifier.class),
                                    IntegerArgumentType.getInteger(context, "batch"))));
            root.then(ClientCommandManager.literal("output").then(outputItem)
                    .then(ClientCommandManager.literal("cancel").executes(context -> OutputOrder.cancel(context.getSource().getClient()))));
            var allow = ClientCommandManager.literal("allow");
            for (String choice : new String[]{"packets", "movement", "off"}) allow.then(ClientCommandManager.literal(choice).executes(context -> {
                try {
                    if (choice.equals("off")) ClientActions.action(context.getSource().getClient(), "stop");
                    LocalFormation.setPermission(choice.equals("packets") ? "teleport" : choice.equals("off") ? "" : choice);
                    ClientActions.message(context.getSource().getClient(), "Movement mode: " + choice);
                } catch (java.io.IOException error) { ClientActions.message(context.getSource().getClient(), "Could not save movement mode"); }
                return 1;
            }));
            root.then(allow);
            var friends = ClientCommandManager.literal("friend");
            for (String action : new String[]{"add", "remove"}) friends.then(ClientCommandManager.literal(action)
                    .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(context -> {
                        ClientActions.friend(context.getSource().getClient(), action, StringArgumentType.getString(context, "name")); return 1;
                    })));
            friends.then(ClientCommandManager.literal("list").executes(context -> {
                ClientActions.message(context.getSource().getClient(), "Friends: " + String.join(", ", BotFriends.names())); return 1;
            }));
            root.then(friends);
            root.then(ClientCommandManager.literal("bot").then(ClientCommandManager.argument("name", StringArgumentType.word())
                .suggests((context,builder)->net.minecraft.command.CommandSource.suggestMatching(BotTracker.all().stream().map(BotTracker.Bot::name),builder))
                .then(ClientCommandManager.argument("action", StringArgumentType.word()).suggests((context,builder)->net.minecraft.command.CommandSource.suggestMatching(java.util.List.of("recall","hold","resume","render","select"),builder))
                    .executes(context->{String name=StringArgumentType.getString(context,"name"),action=StringArgumentType.getString(context,"action");
                        var bot=BotTracker.all().stream().filter(b->b.name().equalsIgnoreCase(name)).findFirst().orElse(null);
                        if(bot==null)ClientActions.message(context.getSource().getClient(),"Unknown tracked bot");
                        else if(action.equals("select"))BotTracker.select(bot.port());
                        else if(action.equals("render"))BotTracker.command(bot.port(),"toggle_render");
                        else if(java.util.Set.of("recall","hold","resume").contains(action))LocalFormation.manage(bot.port(),action);
                        else ClientActions.message(context.getSource().getClient(),"Use recall, hold, resume, render or select");return 1;}))));
            dispatcher.register(root);
            dispatcher.register(ClientCommandManager.literal("botfury-scatter").executes(context -> {
                ClientActions.action(context.getSource().getClient(), "scatter"); return 1;
            }));
        });
        BotMarkers.register();
        HudRenderCallback.EVENT.register(BotMarkers::render);
        HudRenderCallback.EVENT.register(BotHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!BotUiConfig.mouseMigrated && client.options != null) {
                wheel.setBoundKey(InputUtil.Type.MOUSE.createFromCode(GLFW.GLFW_MOUSE_BUTTON_5));
                KeyBinding.updateKeysByCode();
                client.options.write();
                BotUiConfig.markMouseMigration();
            }
            LocalFormation.tick(client);
            while (wheel.wasPressed()) if (client.player != null && client.currentScreen == null) open(client);
            while (quick.wasPressed()) if (client.player != null && client.currentScreen == null) quick(client);
            if (client.player == null) pendingWheel = false;
            else if (pendingWheel && client.currentScreen == null) {
                pendingWheel = false;
                client.setScreen(new CommandWheel());
            }
        });
    }
    static void open(MinecraftClient client) { if (client.player != null) pendingWheel = true; }
    static void quick(MinecraftClient client) {
        if (LocalFormation.mode().equals("strike")) LocalFormation.cancelStrike(client);
        else if (lightningTarget(client) != null) ClientActions.action(client, "lightning");
        else ClientActions.action(client, "stop");
    }
    static LivingEntity visibleTarget(MinecraftClient client) { return visibleTarget(client, false); }

    static LivingEntity lightningTarget(MinecraftClient client) { return visibleTarget(client, true); }

    private static LivingEntity visibleTarget(MinecraftClient client, boolean skipBots) {
        if (client.player == null || client.world == null) return null;
        Vec3d start = client.player.getEyePos();
        Vec3d end = start.add(client.player.getRotationVec(1.0f).multiply(1024));
        HitResult block = client.world.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
        double best = start.squaredDistanceTo(block.getPos());
        LivingEntity selected = null;
        for (Entity entity : client.world.getOtherEntities(client.player,
                client.player.getBoundingBox().stretch(end.subtract(start)).expand(1))) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()
                    || living.isSpectator() || (skipBots && BotVisuals.isBot(entity))) continue;
            var hit = entity.getBoundingBox().expand(entity.getTargetingMargin()).raycast(start, end);
            if (hit.isPresent() && start.squaredDistanceTo(hit.get()) < best) {
                best = start.squaredDistanceTo(hit.get()); selected = living;
            }
        }
        return selected;
    }
    private static KeyBinding key(String name, int defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.botfury." + name,
                InputUtil.Type.KEYSYM, defaultKey, "category.botfury"));
    }
}
