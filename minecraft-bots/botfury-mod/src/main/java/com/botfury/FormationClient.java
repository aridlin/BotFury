package com.botfury;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

final class FormationClient {
    private FormationClient() {}

    static void register() {
        KeyBinding saturn = key("saturn", GLFW.GLFW_KEY_J);
        KeyBinding strike = key("strike", GLFW.GLFW_KEY_K);
        KeyBinding stop = key("stop", GLFW.GLFW_KEY_RIGHT_BRACKET);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (saturn.wasPressed()) send(client, FormationServer.SATURN, -1);
            while (strike.wasPressed()) {
                if (client.crosshairTarget instanceof EntityHitResult hit
                        && hit.getEntity() instanceof LivingEntity living) {
                    send(client, FormationServer.STRIKE, living.getId());
                } else if (client.player != null) {
                    client.player.sendMessage(Text.translatable("botfury.formation.aim"), true);
                }
            }
            while (stop.wasPressed()) send(client, FormationServer.STOP, -1);
        });
    }

    private static KeyBinding key(String name, int defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.botfury." + name, InputUtil.Type.KEYSYM, defaultKey, "category.botfury"));
    }

    private static void send(MinecraftClient client, int action, int entityId) {
        if (client.player == null) return;
        if (!ClientPlayNetworking.canSend(FormationServer.CHANNEL)) {
            client.player.sendMessage(Text.translatable("botfury.formation.unavailable"), true);
            return;
        }
        PacketByteBuf message = PacketByteBufs.create();
        message.writeByte(action);
        if (action == FormationServer.STRIKE) message.writeInt(entityId);
        ClientPlayNetworking.send(FormationServer.CHANNEL, message);
    }
}
