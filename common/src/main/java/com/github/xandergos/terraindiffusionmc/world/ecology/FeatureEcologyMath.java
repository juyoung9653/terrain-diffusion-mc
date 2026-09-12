package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Coordinate-continuous fields shared by ecological decoration features. */
public final class FeatureEcologyMath {
    private static final int SOIL_PATCH_MODEL_PIXELS = 64;
    private static final float SOIL_MOISTURE_VARIATION = 0.12F;
    public record SpeciesWeights(float spruce, float pine, float oak, float birch, float acacia, float jungle) {
        public float total() {
            return spruce + pine + oak + birch + acacia + jungle;
        }
    }

    private FeatureEcologyMath() {
    }

    public static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    /** Smooth value noise whose cells are deliberately much larger than biome quart samples. */
    public static float broadPatch(long seed, int blockX, int blockZ, int cellSize) {
        int cellX = Math.floorDiv(blockX, cellSize);
        int cellZ = Math.floorDiv(blockZ, cellSize);
        float localX = Math.floorMod(blockX, cellSize) / (float)cellSize;
        float localZ = Math.floorMod(blockZ, cellSize) / (float)cellSize;
        float sx = smooth(localX);
        float sz = smooth(localZ);
        float nw = hash01(seed, cellX, cellZ);
        float ne = hash01(seed, cellX + 1, cellZ);
        float sw = hash01(seed, cellX, cellZ + 1);
        float se = hash01(seed, cellX + 1, cellZ + 1);
        return lerp(lerp(nw, ne, sx), lerp(sw, se, sx), sz);
    }

    /**
     * Moisture adjusted by a broad, continuous soil field, in the source's 0..2 domain.
     * Its physical cell width is 64 model pixels at the active terrain scale.
     */
    public static float surfaceMoisture(float moisture, long seed, int blockX, int blockZ, int scale) {
        if (scale <= 0) throw new IllegalArgumentException("scale must be positive");
        int cellSize = Math.multiplyExact(SOIL_PATCH_MODEL_PIXELS, scale);
        float patch = broadPatch(seed, blockX, blockZ, cellSize);
        return Math.max(0.0F, Math.min(2.0F, moisture + (patch - 0.5F) * SOIL_MOISTURE_VARIATION));
    }

    /** Compatibility overload for callers that explicitly use the historic scale-two layout. */
    public static float surfaceMoisture(float moisture, long seed, int blockX, int blockZ) {
        return surfaceMoisture(moisture, seed, blockX, blockZ, 2);
    }

    /**
     * Climate-compatible tree weights. Smooth eligibility curves retain gradual ecotones while
     * excluding tropical broadleaf species from freezing climates and conifers from hot lowlands.
     */
    public static SpeciesWeights speciesWeights(float temperature, float moisture) {
        float conifer = 1.0F - smoothRange(14.0F, 22.0F, temperature);
        float temperateBroadleaf = smoothRange(-2.0F, 8.0F, temperature)
                * (1.0F - smoothRange(20.0F, 28.0F, temperature));
        float warmDry = smoothRange(17.0F, 24.0F, temperature)
                * (1.0F - smoothRange(0.50F, 1.05F, moisture));
        float warmWet = smoothRange(20.0F, 25.0F, temperature)
                * smoothRange(0.75F, 1.35F, moisture);
        float dry = 1.0F - smoothRange(0.50F, 1.05F, moisture);
        float wet = smoothRange(0.75F, 1.35F, moisture);
        return new SpeciesWeights(
                1.25F * conifer * (0.40F + 0.60F * wet),
                conifer * (0.60F + 0.40F * dry),
                0.90F * temperateBroadleaf,
                0.55F * temperateBroadleaf * (0.35F + 0.65F * wet),
                1.40F * warmDry,
                1.60F * warmWet
        );
    }

    private static float smooth(float value) {
        return value * value * (3.0F - 2.0F * value);
    }

    private static float smoothRange(float low, float high, float value) {
        return smooth(clamp01((value - low) / (high - low)));
    }

    private static float lerp(float a, float b, float amount) {
        return a + (b - a) * amount;
    }

    private static float hash01(long seed, int x, int z) {
        long value = seed + 0x9E3779B97F4A7C15L * x + 0xC2B2AE3D27D4EB4FL * z;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (float)((value >>> 40) & 0xFFFFFFL) / 16777215.0F;
    }
}
