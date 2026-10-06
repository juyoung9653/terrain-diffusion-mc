package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.List;

/** Continuous ecological controls; biome labels never drive the vegetation or soil blend. */
public final class EcologyClassifier {
    public static final List<String> BIOMES = List.of(
            "temperate_forest", "temperate_woodland", "temperate_grassland",
            "boreal_forest", "boreal_woodland", "cold_grassland",
            "dry_woodland", "dry_grassland", "tropical_forest", "tropical_woodland", "desert");
    private static final FastNoiseLite VARIATION = variation();
    public record Sample(int biomeId, float treeCover, float temperature, float moisture, float slope) { }
    public record Selection(Sample sample, short terrainBiomeId) { }

    private EcologyClassifier() { }

    private static FastNoiseLite variation() {
        FastNoiseLite noise = new FastNoiseLite(73119);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFrequency(1f / 768f);
        return noise;
    }

    public static Selection select(int x, int z, float elevation, float slope, boolean coast,
                                   float regionalTemperature, float regionalRainfall, float regionalElevation) {
        return select(x, z, elevation, slope, regionalTemperature, regionalRainfall, regionalElevation,
                coast ? 1 : Float.POSITIVE_INFINITY, coast ? .5f : 0, slope);
    }

    public static Selection select(int x, int z, float elevation, float slope,
                                   float regionalTemperature, float regionalRainfall, float regionalElevation,
                                   float waterDistance, float openWater, float relief) {
        // The model temperature already reflects its coarse altitude. Correct only the local difference.
        float temperature = regionalTemperature - .006f * (Math.max(0, elevation) - Math.max(0, regionalElevation));
        float regionalMoisture = clamp(regionalRainfall / (500f + 20f * Math.max(0, regionalTemperature)), 0, 2);
        // Relative relief changes soil retention, not rainfall or regional biome identity.
        // Multiplicative bounds preserve deserts and wet forests; no neighbouring tile is queried.
        float drainage = .08f * smooth(30, 450, elevation - regionalElevation)
                + .06f * smooth(.15f, 1.0f, slope);
        float shelter = .06f * smooth(30, 400, regionalElevation - elevation);
        float moisture = clamp(regionalMoisture * (1 + shelter - drainage), 0, 2);
        if (elevation < 0) {
            short water = TerralithBiomeClassifier.selectSurface(x, z, (short) 41,
                    elevation, slope, temperature, regionalRainfall, 0, false);
            return terrain(water, temperature, moisture, slope);
        }
        short shore = ShoreClassifier.select(x, z, elevation, slope, temperature, moisture,
                waterDistance, openWater, relief);
        if (shore != 0) return terrain(shore, temperature, moisture, slope);
        if (regionalElevation >= 1600 && regionalTemperature < 0)
            return terrain(terralith("alpine_highlands"), temperature, moisture, slope);

        float regionalCover = smooth(.38f, 1.22f, regionalMoisture);
        float localCover = smooth(.38f, 1.22f, moisture);
        // Slope is rise/run: soil-covered hills must not become treeless at 40 degrees.
        // Local substrate and tree clearance still reject unsuitable rocky ledges.
        float cover = localCover * (1 - smooth(.35f, 1.80f, slope))
                * smooth(-7, 3, temperature) * (.90f + .10f * VARIATION.GetNoise(x, z));
        // Thin the cold upland canopy before the alpine selector takes ownership at 1600 m.
        // Warm mountains retain their altitude-adjusted ecology; this is not a universal elevation cap.
        float coldUpland = (1 - smooth(0, 5, regionalTemperature))
                * smooth(900, 1600, regionalElevation);
        cover *= 1 - coldUpland;
        int id;
        // Regional fields determine labels. Local lapse and slope remain continuous controls for cover.
        if (regionalMoisture < .30f && regionalTemperature > 10) id = 11;
        else if (regionalTemperature < 5) id = regionalCover > .65f ? 4 : regionalCover > .22f ? 5 : 6;
        else if (regionalTemperature >= 23 && regionalMoisture >= .65f) id = regionalCover > .65f ? 9 : 10;
        // Dry woodland needs its own aridity band: the generic cover cut-off restricted
        // it to moisture 0.635..0.65. Cover itself remains continuous across this label.
        else if (regionalMoisture < .65f && regionalTemperature > 17) id = regionalMoisture >= .52f ? 7 : 8;
        else id = regionalCover > .65f ? 1 : regionalCover > .22f ? 2 : 3;
        return new Selection(new Sample(id, clamp(cover, 0, 1), temperature, moisture, slope), (short) 0);
    }

    private static Selection terrain(short id, float temperature, float moisture, float slope) {
        return new Selection(new Sample(0, 0, temperature, moisture, slope), id);
    }

    private static short terralith(String name) {
        int index = TerralithBiomeClassifier.biomeNames().indexOf("terralith:" + name);
        if (index < 0) throw new IllegalStateException("Missing ecological terrain biome: " + name);
        return (short) (index + 1);
    }

    public static float smooth(float low, float high, float value) {
        float t = clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
}
