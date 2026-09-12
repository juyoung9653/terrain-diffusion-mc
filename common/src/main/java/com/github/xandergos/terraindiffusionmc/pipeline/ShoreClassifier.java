package com.github.xandergos.terraindiffusionmc.pipeline;

/** Narrow shore aprons selected from local exposure and relief, without changing terrain. */
public final class ShoreClassifier {
    // Negative IDs are vanilla shore biomes; positive IDs remain the stable Terralith palette.
    public static final short BEACH = -1, SNOWY_BEACH = -2, STONY_SHORE = -3;
    private static final short GRAVEL = (short) (TerralithBiomeClassifier.biomeNames()
            .indexOf("terralith:gravel_beach") + 1);
    private static final FastNoiseLite SUBSTRATE = substrate();

    private ShoreClassifier() { }

    private static FastNoiseLite substrate() {
        FastNoiseLite noise = new FastNoiseLite(19073);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFrequency(1f / 768f);
        return noise;
    }

    /** Zero retains the adjacent land ecosystem. Distances are blocks; elevations are physical metres. */
    public static short select(int x, int z, float elevation, float slope, float temperature,
                               float moisture, float distance, float openWater, float relief) {
        if (elevation < 0 || distance > 24 || openWater < .25f) return 0;
        float exposure = EcologyClassifier.smooth(.25f, .5f, openWater);
        float rock = Math.max(slope, relief);
        if (rock >= .32f) {
            return distance <= 4 + 6 * exposure && elevation <= 150 ? STONY_SHORE : 0;
        }
        // Wet, sheltered banks retain soil and vegetation instead of a universal mineral rim.
        float sediment = .5f + SUBSTRATE.GetNoise(x, z);
        float wetBank = EcologyClassifier.smooth(.85f, 1.4f, moisture);
        float width = 4 + 10 * exposure - 5 * wetBank + 4 * sediment;
        if (wetBank > .6f && (exposure < .65f || sediment < .4f)) return 0;
        float maxElevation = 24 + 24 * exposure;
        if (distance > width || elevation > maxElevation) return 0;
        if (temperature < 0) return SNOWY_BEACH;
        // Regional substrate is coherent along the coast, never independent per-column noise.
        if (relief > .12f || (temperature < 12 && sediment > .5f)) return GRAVEL;
        return BEACH;
    }
}
