package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.List;

/** Continuous ecological controls; biome labels never drive the vegetation or soil blend. */
public final class EcologyClassifier {
    public static final List<String> BIOMES = List.of(
            "temperate_forest", "temperate_woodland", "temperate_grassland",
            "boreal_forest", "boreal_woodland", "cold_grassland",
            "dry_woodland", "dry_grassland", "tropical_forest", "tropical_woodland", "desert",
            "snowy_boreal_forest", "temperate_rainforest", "montane_forest", "steppe",
            "snowy_boreal_woodland", "snowy_tundra", "alpine_tundra", "glacial_highlands");
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

    /** Shared cold exposure: a sparse snow/low-vegetation ecotone before the frozen core. */
    public static float coldState(float temperature) {
        return 1 - smooth(-4, 1, temperature);
    }

    /** Relief is rise/run, not absolute altitude. Flat polar lowlands remain tundra/forest. */
    public static float alpineExposure(float temperature, float slope, float relief) {
        return coldState(temperature) * smooth(.25f, .85f, Math.max(slope, relief));
    }

    /** Compatibility diagnostic alias for cold exposure; alpine identity additionally needs relief. */
    public static float alpineCoverage(int x, int z, float elevation,
                                       float regionalTemperature, float regionalElevation) {
        float temperature = localTemperature(elevation, regionalTemperature, regionalElevation);
        return alpineCoverage(x, z, elevation, temperature);
    }

    /** Cached feature-facing form: temperature has already received the local altitude correction. */
    public static float alpineCoverage(int x, int z, float elevation, float temperature) {
        return coldState(temperature);
    }

    /** Compatibility entry point; highland and lowland snow use exactly the same cold state. */
    public static boolean alpineSnowCover(int x, int z, float elevation, float temperature) {
        return naturalSnowCover(x, z, elevation, temperature);
    }

    /** Continuous cold-lowland and alpine exposure, using the actual local surface climate. */
    public static float naturalSnowCoverage(int x, int z, float elevation, float temperature) {
        return naturalSnowCoverage(temperature, 0);
    }

    public static float naturalSnowCoverage(float temperature, float retention) {
        float cold = coldState(temperature);
        // Aspect/concavity only shape an existing transition, never create a warm snow island.
        float retained = cold + .06f * clamp(retention, -.65f, .65f) * 4 * cold * (1 - cold);
        return smooth(.35f, .65f, retained);
    }

    /** One column decision for both forest floor and crown; biome names never override it. */
    public static boolean naturalSnowCover(int x, int z, float elevation, float temperature) {
        return naturalSnowCover(x, z, elevation, temperature, 0);
    }

    public static boolean naturalSnowCover(int x, int z, float elevation, float temperature, float retention) {
        float coverage = naturalSnowCoverage(temperature, retention);
        if (coverage <= 0) return false;
        if (coverage >= 1) return true;
        // Column-scale texture only. No broad noise mask can invent detached climate regions.
        long bits = (long) Math.floorDiv(x, 2) * 0x9E3779B97F4A7C15L
                ^ (long) Math.floorDiv(z, 2) * 0xC2B2AE3D27D4EB4FL;
        bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
        bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
        float threshold = ((bits ^ (bits >>> 31)) >>> 40) / 16777216f;
        return threshold < coverage;
    }

    /**
     * Water follows the same local cold transition as exposed land.  Keeping a
     * separate name makes the freeze contract explicit: a marginal climate must
     * not turn every water column in a model/tile region into one hard ice slab.
     */
    public static boolean naturalIceCover(int x, int z, float elevation, float temperature, float retention) {
        return naturalSnowCover(x, z, elevation, temperature, retention);
    }

    public static boolean naturalIceCover(int x, int z, float elevation, float temperature) {
        return naturalIceCover(x, z, elevation, temperature, 0);
    }

    /** Signed coverage margin retained for diagnostics; elevation never independently enables snow. */
    public static float naturalSnowMargin(float elevation, float temperature, float retention) {
        return naturalSnowCoverage(temperature, retention) - .5f;
    }

    /**
     * One-block terrain retention from the existing smooth elevation halo. Heights and spacing are metres.
     * Rows run south (+Z), columns east (+X): positive dZ is a north-facing, shaded downslope.
     * The bounded local Laplacian indicates concavity only; it is not a basin or snow-transport simulation.
     */
    public static float[] snowRetention(float[] padded, int height, int width, float metersPerBlock) {
        if (height <= 0 || width <= 0 || padded.length != (height + 2) * (width + 2)
                || !Float.isFinite(metersPerBlock) || metersPerBlock <= 0)
            throw new IllegalArgumentException("Snow retention requires a one-block halo and positive metre spacing");
        float[] retention = new float[height * width];
        int stride = width + 2;
        float inverseSpacing = 1 / metersPerBlock;
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            int p = (z + 1) * stride + x + 1;
            float west = padded[p - 1], east = padded[p + 1];
            float north = padded[p - stride], south = padded[p + stride];
            float nw = padded[p - stride - 1], ne = padded[p - stride + 1];
            float sw = padded[p + stride - 1], se = padded[p + stride + 1];
            float dx = (ne + 2 * east + se - nw - 2 * west - sw) * .125f * inverseSpacing;
            float dz = (sw + 2 * south + se - nw - 2 * north - ne) * .125f * inverseSpacing;
            float slope = (float) Math.sqrt(dx * dx + dz * dz);
            float concavity = (west + east + north + south - 4 * padded[p]) * inverseSpacing * inverseSpacing;
            float value = .28f * dz / (float) Math.sqrt(1 + slope * slope)
                    - .22f * smooth(.4f, 1.6f, slope) + .12f * clamp(24 * concavity, -1, 1);
            retention[z * width + x] = Float.isFinite(value) ? clamp(value, -.65f, .65f) : 0;
        }
        return retention;
    }

    public static boolean alpineSnowCover(int x, int z, float elevation,
                                         float regionalTemperature, float regionalElevation) {
        return alpineSnowCover(x, z, elevation, localTemperature(elevation, regionalTemperature, regionalElevation));
    }

    public static float localTemperature(float elevation, float regionalTemperature, float regionalElevation) {
        float lapse = .006f * (Math.max(0, elevation) - Math.max(0, regionalElevation));
        return regionalTemperature - 2 * (float) Math.tanh(lapse / 2);
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
        float temperature = localTemperature(elevation, regionalTemperature, regionalElevation);
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
        float cold = coldState(temperature);
        float alpine = alpineExposure(temperature, slope, relief);

        float regionalCover = smooth(.38f, 1.22f, regionalMoisture);
        float localCover = smooth(.38f, 1.22f, moisture);
        // Slope is rise/run: soil-covered hills must not become treeless at 40 degrees.
        // Local substrate and tree clearance still reject unsuitable rocky ledges.
        float cover = localCover * (1 - smooth(.35f, 1.80f, slope))
                * smooth(-12, -4, temperature) * (.90f + .10f * VARIATION.GetNoise(x, z));
        // Finish thinning before the alpine identity starts; temperature and relief vary continuously.
        cover *= 1 - smooth(.15f, .65f, alpine);
        int id;
        // Warm identities stay regional. Cold identities below share the actual local cold state.
        if (regionalMoisture < .30f && regionalTemperature > 10) id = 11;
        else if (regionalTemperature < 5) id = regionalCover > .65f ? 4 : regionalCover > .22f ? 5 : 6;
        else if (regionalTemperature >= 23 && regionalMoisture >= .65f) id = regionalCover > .65f ? 9 : 10;
        // Dry woodland needs its own aridity band: the generic cover cut-off restricted
        // it to moisture 0.635..0.65. Cover itself remains continuous across this label.
        else if (regionalMoisture < .65f && regionalTemperature > 17) id = regionalMoisture >= .52f ? 7 : 8;
        else id = regionalCover > .65f ? 1 : regionalCover > .22f ? 2 : 3;
        // Append finer regional identities without changing existing numeric IDs
        // or using labels to change continuous vegetation/material controls.
        if (cold >= .5f) {
            if (alpine >= .65f) id = temperature <= -8 ? 19 : 18;
            else if (temperature <= -10) id = 17;
            else id = localCover > .65f ? 12 : localCover > .22f ? 16 : 17;
        }
        else if (id == 1 && regionalElevation >= 700) id = 14;
        else if (id == 1 && regionalMoisture >= 1.45f) id = 13;
        else if (id == 3 && regionalTemperature <= 17 && regionalMoisture < .52f) id = 15;
        return new Selection(new Sample(id, clamp(cover, 0, 1), temperature, moisture, slope), (short) 0);
    }

    private static Selection terrain(short id, float temperature, float moisture, float slope) {
        return new Selection(new Sample(0, 0, temperature, moisture, slope), id);
    }

    public static float smooth(float low, float high, float value) {
        float t = clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
}
