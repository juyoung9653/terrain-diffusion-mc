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
            // .64 stays above the woodland cover cut-off and below the dry-climate moisture limit.
            Map.entry("dry_woodland", new Climate(22.0F, .64F)),
            Map.entry("dry_grassland", new Climate(22.0F, .33F)),
            Map.entry("tropical_forest", new Climate(28.0F, 1.50F)),
            Map.entry("tropical_woodland", new Climate(28.0F, .85F)),
            Map.entry("desert", new Climate(28.0F, .10F)));

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

    /** Produces the normal classifier sample from a fixed representative climate. */
    public static EcologyClassifier.Sample sample(String name, int x, int z, float slope) {
        if (!EcologyClassifier.BIOMES.contains(name)) {
            throw new IllegalArgumentException("Unknown ecology showcase profile: " + name);
        }
        Climate climate = CLIMATES.get(name);
        if (climate == null) {
            throw new IllegalStateException("Missing ecology showcase climate: " + name);
        }
        float rainfall = (500.0F + 20.0F * Math.max(0.0F, climate.temperature())) * climate.moisture();
        return EcologyClassifier.select(x, z, 100.0F, slope, false,
                climate.temperature(), rainfall, 100.0F).sample();
    }
}
