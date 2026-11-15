package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;

/** Pure conventions shared by the screenshot river regression audit and its preflight. */
final class RiverRegressionContract {
    static final String DESERT_SOURCE_BIOME = "terrain-diffusion-mc:ecology/desert";

    private RiverRegressionContract() { }

    /** OCEAN_FLOOR_WG reports the first-air boundary; biome noise is sampled in quart coordinates. */
    static int sourceBiomeQuartY(int originalGroundY) {
        return Math.floorDiv(originalGroundY + 1, 4);
    }

    /** Ecology reserves zero for retained terrain, while BIOMES is a zero-based land-label list. */
    static int ecologyId(String label) {
        int index = EcologyClassifier.BIOMES.indexOf(label);
        if (index < 0) throw new IllegalArgumentException("unknown ecology label: " + label);
        return index + 1;
    }

    static int desertEcologyId() {
        return ecologyId("desert");
    }

    static boolean isDesertSourceAndEcology(String sourceBiome, int ecologyBiomeId) {
        return DESERT_SOURCE_BIOME.equals(sourceBiome) && ecologyBiomeId == desertEcologyId();
    }
}
