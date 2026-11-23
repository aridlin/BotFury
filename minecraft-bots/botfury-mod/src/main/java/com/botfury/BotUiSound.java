package com.botfury;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

final class BotUiSound {
    private static final SoundEvent CLICK = SoundEvent.of(new Identifier("botfury", "ui.click"));
    private static final SoundEvent DOUBLE_CLICK = SoundEvent.of(new Identifier("botfury", "ui.double_click"));

    private BotUiSound() {}

    static void open() { play(DOUBLE_CLICK, 1.28f, .54f); }
    static void hover() { play(CLICK, 1.28f, .24f); }
    static void back() { play(CLICK, .77f, .58f); }
    static void confirm() { play(CLICK, 1.04f, .82f); }

    private static void play(SoundEvent sound, float pitch, float volume) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch, volume));
    }
}
