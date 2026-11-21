package com.botfury;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BotUiConfig {
    static boolean hud = true;
    static boolean esp = true;
    static boolean defense;
    static String item = "";
    static final int TRACK_LIMIT = 6;
    static final List<String> trackedItems = new ArrayList<>();
    static boolean mouseMigrated;
    private static Path path() {
        return LocalFormation.settingsPath().toAbsolutePath().resolveSibling("botfury-ui.json");
    }
    static void load() {
        try {
            var value = new Gson().fromJson(Files.readString(path()), com.google.gson.JsonObject.class);
            if (value.has("hud")) hud = value.get("hud").getAsBoolean();
            if (value.has("esp")) esp = value.get("esp").getAsBoolean();
            if (value.has("defense")) defense = value.get("defense").getAsBoolean();
            if (value.has("item")) item = value.get("item").getAsString();
            trackedItems.clear();
            if (value.has("trackedItems") && value.get("trackedItems").isJsonArray()) {
                for (var entry : value.getAsJsonArray("trackedItems")) {
                    if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) addTracked(entry.getAsString());
                }
            } else if (value.has("trackedItem")) addTracked(value.get("trackedItem").getAsString());

        } catch (Exception ignored) { }
        mouseMigrated = Files.exists(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("botfury-mouse-forward-v1"));
    }
    static void markMouseMigration() {
        mouseMigrated=true;
        try { Path marker=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("botfury-mouse-forward-v1");Files.createDirectories(marker.getParent());Files.writeString(marker,"mouse-forward-v1"); }
        catch(Exception error){BotEvents.add("warn","settings","Could not save input preference");}
    }
    static void toggle() {
        hud = !hud;
        save();
    }
    static void toggleEsp() { esp = !esp; save(); }
    static boolean addTracked(String item) {
        var id = net.minecraft.util.Identifier.tryParse(item);
        if (id == null || id.equals(net.minecraft.util.Identifier.of("minecraft", "air"))
                || !net.minecraft.registry.Registries.ITEM.containsId(id)
                || trackedItems.contains(id.toString()) || trackedItems.size() >= TRACK_LIMIT) return false;
        return trackedItems.add(id.toString());
    }

    static void save() {
        try { Files.createDirectories(path().getParent()); Files.writeString(path(), new Gson().toJson(Map.of("hud", hud, "esp", esp, "item", item, "trackedItems", trackedItems, "defense", defense))); }
        catch (Exception error) { BotEvents.add("warn", "settings", "Could not save HUD preference"); }
    }
}
