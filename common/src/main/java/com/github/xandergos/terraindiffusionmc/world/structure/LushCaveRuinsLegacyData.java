package com.github.xandergos.terraindiffusionmc.world.structure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Scoped placement and legacy-data rules for Lush Cave Ruins on Minecraft 1.21.1. */
public final class LushCaveRuinsLegacyData {
    public static final int MINIMUM_SEPARATION_CHUNKS = 63;
    private static final String REGISTRY = "minecraft:worldgen/configured_feature";
    private static final String FEATURE = "keeb_struc_lush:column_ruins_feature";
    private static final String POT_LOOT = "keeb_struc_lush:lush_cave_ruins_pot";
    private static final String COMMON_LOOT = "keeb_struc_lush:lush_cave_ruins_common";

    private LushCaveRuinsLegacyData() { }

    /**
     * Applies the exact LCR set's spacing rule and repairs its affected feature.
     * Other registry values retain identity.
     */
    public static JsonElement adapt(String registryId, String elementId, JsonElement original) {
        if ("minecraft:worldgen/structure_set".equals(registryId)
                && "keeb_struc_lush:lush_ruins".equals(elementId)) return spaceRuins(original);
        if (!REGISTRY.equals(registryId) || !FEATURE.equals(elementId)) return original;
        JsonElement copy = original.deepCopy();
        return adaptTree(copy) ? copy : original;
    }

    private static JsonElement spaceRuins(JsonElement original) {
        if (!original.isJsonObject()) return original;
        JsonObject root = original.getAsJsonObject();
        if (!root.has("placement") || !root.get("placement").isJsonObject()) return original;
        JsonObject placement = root.getAsJsonObject("placement");
        if (!placement.has("type") || !"minecraft:random_spread".equals(placement.get("type").getAsString())
                || !placement.has("spacing") || !placement.has("separation")) return original;
        int spacing = placement.get("spacing").getAsInt();
        int separation = placement.get("separation").getAsInt();
        if (separation >= MINIMUM_SEPARATION_CHUNKS || separation < 0 || spacing <= separation) return original;
        // Offsets are [0, spacing-separation). Adjacent regions are therefore
        // at least separation+1 chunks apart: 64 * 16 = 1,024 blocks.
        // Retain the original jitter width, salt, frequency and exclusion zone.
        JsonObject copy = root.deepCopy();
        JsonObject changed = copy.getAsJsonObject("placement");
        changed.addProperty("spacing", MINIMUM_SEPARATION_CHUNKS + spacing - separation);
        changed.addProperty("separation", MINIMUM_SEPARATION_CHUNKS);
        return copy;
    }

    /** Narrow item-id fallback for LCR 1.3-LTS loot on the 1.21.1 registry. */
    public static JsonElement adaptLoot(String tableId, JsonElement original) {
        String replacement = POT_LOOT.equals(tableId) ? "minecraft:clay_ball"
                : COMMON_LOOT.equals(tableId) ? "minecraft:bundle" : null;
        if (replacement == null) return original;
        JsonElement copy = original.deepCopy();
        return adaptLootTree(copy, tableId, replacement) ? copy : original;
    }

    private static boolean adaptLootTree(JsonElement element, String tableId, String replacement) {
        if (element.isJsonArray()) {
            boolean changed = false;
            for (JsonElement child : element.getAsJsonArray()) changed |= adaptLootTree(child, tableId, replacement);
            return changed;
        }
        if (!element.isJsonObject()) return false;
        JsonObject object = element.getAsJsonObject();
        boolean changed = false;
        if (object.has("type") && "minecraft:item".equals(object.get("type").getAsString()) && object.has("name")) {
            String name = object.get("name").getAsString();
            boolean matches = (POT_LOOT.equals(tableId) && "minecraft:resin_clump".equals(name))
                    || (COMMON_LOOT.equals(tableId) && ("minecraft:brown_bundle".equals(name)
                    || "minecraft:light_gray_bundle".equals(name) || "minecraft:gray_bundle".equals(name)
                    || "minecraft:green_bundle".equals(name)));
            if (matches) { object.addProperty("name", replacement); changed = true; }
        }
        for (var entry : object.entrySet()) changed |= adaptLootTree(entry.getValue(), tableId, replacement);
        return changed;
    }

    private static boolean adaptTree(JsonElement element) {
        if (element.isJsonArray()) {
            boolean changed = false;
            for (JsonElement child : element.getAsJsonArray()) changed |= adaptTree(child);
            return changed;
        }
        if (!element.isJsonObject()) return false;
        JsonObject object = element.getAsJsonObject();
        boolean changed = fallenTree(object);
        for (var entry : object.entrySet()) changed |= adaptTree(entry.getValue());
        return changed;
    }

    private static boolean fallenTree(JsonObject object) {
        if (!object.has("type") || !"minecraft:fallen_tree".equals(object.get("type").getAsString()))
            return false;
        if (!object.has("config") || !object.get("config").isJsonObject()) return false;
        JsonObject config = object.getAsJsonObject("config");
        if (!empty(config, "stump_decorators") || !empty(config, "log_decorators")
                || !config.has("trunk_provider")) return false;
        JsonObject pile = new JsonObject();
        pile.add("state_provider", config.get("trunk_provider").deepCopy());
        object.addProperty("type", "minecraft:block_pile");
        object.add("config", pile);
        return true;
    }

    private static boolean empty(JsonObject config, String name) {
        return config.has(name) && config.get(name).isJsonArray()
                && config.getAsJsonArray(name).isEmpty();
    }
}
