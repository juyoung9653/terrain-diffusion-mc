package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.Map;

/** Canonical climates for deterministic, single-ecology showcase worlds. */
public final class EcologyShowcaseProfile {
    private record Climate(float temperature, float moisture) {
    }

    private static final Map<String, Climate> CLIMATES = Map.ofEntries(
            Map.entry("temperate_forest", new Climate(13.0F, 1.25F)),
            Map.entry("temperate_woodland", new Climate(13.0F, .80F)),
            Map.entry("temperate_grassland", new Climate(13.0F, .50F)),
            Map.entry("boreal_forest", new Climate(2.0F, 1.25F)),
            Map.entry("boreal_woodland", new Climate(2.0F, .80F)),
            Map.entry("cold_grassland", new Climate(0.0F, .50F)),
            Map.entry("dry_woodland", new Climate(22.0F, .60F)),
            Map.entry("dry_grassland", new Climate(22.0F, .33F)),
            Map.entry("tropical_forest", new Climate(28.0F, 1.50F)),
            Map.entry("tropical_woodland", new Climate(28.0F, .85F)),
            Map.entry("desert", new Climate(28.0F, .10F)),
            Map.entry("snowy_boreal_forest", new Climate(-3.0F, 1.25F)),
            Map.entry("temperate_rainforest", new Climate(13.0F, 1.60F)),
            Map.entry("montane_forest", new Climate(10.0F, 1.25F)),
            Map.entry("steppe", new Climate(13.0F, .45F)),
            Map.entry("snowy_boreal_woodland", new Climate(-4.0F, .80F)),
            Map.entry("snowy_tundra", new Climate(-5.0F, .40F)),
            Map.entry("alpine_tundra", new Climate(-6.0F, .50F)),
            Map.entry("glacial_highlands", new Climate(-12.0F, .35F)));

    private EcologyShowcaseProfile() {
    }

    /** Only land ecology is fixed: native hydrology must still see oceans and shores. */
    public static boolean retainsTerrainBiome(short terrainBiomeId) {
        return TerralithBiomeClassifier.isOcean(terrainBiomeId)
                || terrainBiomeId == -1 || terrainBiomeId == -2 || terrainBiomeId == -3;
    }

    public static boolean isVanillaOcean(short biomeId) {
        return biomeId == 41 || biomeId == 44 || biomeId == 46 || biomeId == 48;
    }

    /** Keeps the named identity and its fixed climate, including older saved showcases. */
    public static EcologyClassifier.Sample sample(String name, int x, int z, float slope) {
        if (!EcologyClassifier.BIOMES.contains(name)) {
            throw new IllegalArgumentException("Unknown ecology showcase profile: " + name);
        }
        Climate climate = CLIMATES.get(name);
        if (climate == null) {
            throw new IllegalStateException("Missing ecology showcase climate: " + name);
        }
        float rainfall = (500.0F + 20.0F * Math.max(0.0F, climate.temperature())) * climate.moisture();
        float elevation = name.equals("montane_forest") ? 900.0F : 100.0F;
        var sample = EcologyClassifier.select(x, z, elevation, slope, false,
                climate.temperature(), rainfall, elevation).sample();
        return new EcologyClassifier.Sample(EcologyClassifier.BIOMES.indexOf(name) + 1,
                name.equals("alpine_tundra") || name.equals("glacial_highlands") ? 0 : sample.treeCover(),
                sample.temperature(), sample.moisture(), sample.slope());
    }
}
