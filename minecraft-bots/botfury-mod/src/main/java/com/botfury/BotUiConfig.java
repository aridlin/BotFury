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
    static boolean paths = true;
    static boolean defense;
    static boolean fleetGuideSeen;
    static String item = "";
    static final int TRACK_LIMIT = 6;
    static final List<String> trackedItems = new ArrayList<>();
    private static List<String> requestedItems = List.of();
    static final Map<String, List<String>> squads = new java.util.LinkedHashMap<>();

    static boolean validSquadName(String name) {
        return !name.isBlank() && name.length() <= 24 && !name.equalsIgnoreCase("All bots");
    }

    record Tracked(String icon, List<String> items) {
        int count(Map<String, Integer> inventory) {
            return items.stream().mapToInt(id -> inventory.getOrDefault(id, 0)).sum();
        }
    }
    static void trackRequest(List<String> ids) {
        requestedItems = ids.stream().filter(id -> {
            var item = net.minecraft.util.Identifier.tryParse(id);
            return item != null && !id.equals("minecraft:air") && net.minecraft.registry.Registries.ITEM.containsId(item);
        }).distinct().toList();
    }
    static List<Tracked> displayedItems() {
        var result = new ArrayList<Tracked>();
        if (!requestedItems.isEmpty()) result.add(new Tracked(requestedItems.get(0), requestedItems));
        for (String id : trackedItems) if (!requestedItems.contains(id)) result.add(new Tracked(id, List.of(id)));
        return result;
    }
    static boolean mouseMigrated;
    private static Path path() {
        return LocalFormation.settingsPath().toAbsolutePath().resolveSibling("botfury-ui.json");
    }
    static void load() {
        try {
            var value = new Gson().fromJson(Files.readString(path()), com.google.gson.JsonObject.class);
            if (value.has("hud")) hud = value.get("hud").getAsBoolean();
            if (value.has("paths")) paths = value.get("paths").getAsBoolean();
            if (value.has("esp")) esp = value.get("esp").getAsBoolean();
            if (value.has("defense")) defense = value.get("defense").getAsBoolean();
            if (value.has("fleetGuideSeen")) fleetGuideSeen = value.get("fleetGuideSeen").getAsBoolean();
            if (value.has("item")) item = value.get("item").getAsString();
            squads.clear();
            if (value.has("squads") && value.get("squads").isJsonObject()) {
                for (var entry : value.getAsJsonObject("squads").entrySet()) {
                    if (!validSquadName(entry.getKey()) || !entry.getValue().isJsonArray()) continue;
                    var members = new ArrayList<String>();
                    for (var member : entry.getValue().getAsJsonArray()) {
                        if (member.isJsonPrimitive() && member.getAsJsonPrimitive().isString()
                                && member.getAsString().matches("[A-Za-z0-9_]{1,16}")) members.add(member.getAsString());
                    }
                    if (!members.isEmpty()) squads.put(entry.getKey(), members.stream().distinct().toList());
                }
            }
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
    static void togglePaths() { paths = !paths; save(); }
    static void toggleEsp() { esp = !esp; save(); }
    static void markFleetGuideSeen() {
        if (fleetGuideSeen) return;
        fleetGuideSeen = true;
        save();
    }
    static boolean addTracked(String item) {
        var id = net.minecraft.util.Identifier.tryParse(item);
        if (id == null || id.equals(net.minecraft.util.Identifier.of("minecraft", "air"))
                || !net.minecraft.registry.Registries.ITEM.containsId(id)
                || trackedItems.contains(id.toString()) || trackedItems.size() >= TRACK_LIMIT) return false;
        return trackedItems.add(id.toString());
    }

    static void save() {
        try { Files.createDirectories(path().getParent()); Files.writeString(path(), new Gson().toJson(Map.of("hud", hud, "esp", esp, "paths", paths, "item", item, "trackedItems", trackedItems, "defense", defense, "fleetGuideSeen", fleetGuideSeen, "squads", squads))); }
        catch (Exception error) { BotEvents.add("warn", "settings", "Could not save HUD preference"); }
    }
}
