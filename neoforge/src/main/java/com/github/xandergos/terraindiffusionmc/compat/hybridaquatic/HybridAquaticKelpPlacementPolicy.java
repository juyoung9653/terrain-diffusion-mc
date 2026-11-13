package com.github.xandergos.terraindiffusionmc.compat.hybridaquatic;

/** Stable world-coordinate thinning for Hybrid Aquatic's dense marine plants. */
public final class HybridAquaticKelpPlacementPolicy {
    public static final int CANDIDATES_PER_CHUNK = 48;
    public static final double ACCEPTANCE = 0.8;
    private static final int CELL_SIZE = 40;

    private HybridAquaticKelpPlacementPolicy() { }

    /** A separate coordinate random value is compared to the continuous probability. */
    public static boolean accept(long worldSeed, long speciesSalt, int blockX, int blockZ) {
        return accept(worldSeed, speciesSalt, 12, blockX, blockZ);
    }

    /** Applies the shallow-shelf exclusion only to a stalk-forming kelp entry. */
    public static boolean accept(long worldSeed, long speciesSalt, int waterDepth,
                                 int blockX, int blockZ) {
        return random(worldSeed, speciesSalt ^ 0xD1342543DE82EF95L, blockX, blockZ)
                < probability(worldSeed, speciesSalt, waterDepth, blockX, blockZ);
    }

    /** Continuous bed-density field in [0,1). */
    public static double density(long worldSeed, long speciesSalt, int blockX, int blockZ) {
        int cellX = Math.floorDiv(blockX, CELL_SIZE);
        int cellZ = Math.floorDiv(blockZ, CELL_SIZE);
        double x = Math.floorMod(blockX, CELL_SIZE) / (double) CELL_SIZE;
        double z = Math.floorMod(blockZ, CELL_SIZE) / (double) CELL_SIZE;
        double a = value(worldSeed, speciesSalt, cellX, cellZ);
        double b = value(worldSeed, speciesSalt, cellX + 1, cellZ);
        double c = value(worldSeed, speciesSalt, cellX, cellZ + 1);
        double d = value(worldSeed, speciesSalt, cellX + 1, cellZ + 1);
        double sx = smooth(x);
        double sz = smooth(z);
        return lerp(lerp(a, b, sx), lerp(c, d, sx), sz);
    }

    /**
     * Smoothly graded chance, capped at .8. The threshold range produces broad
     * beds that fade through sparse gaps instead of a dense carpet with holes.
     */
    public static double probability(long worldSeed, long speciesSalt, int blockX, int blockZ) {
        return probability(worldSeed, speciesSalt, 12, blockX, blockZ);
    }

    public static double probability(long worldSeed, long speciesSalt, int waterDepth,
                                     int blockX, int blockZ) {
        double edge = clamp((density(worldSeed, speciesSalt, blockX, blockZ) - 0.3) / 0.45);
        return ACCEPTANCE * shallowWaterFactor(waterDepth) * smooth(edge);
    }

    /** Matches vanilla kelp's deterministic six-block shallow-shelf exclusion. */
    public static double shallowWaterFactor(int waterDepth) {
        if (waterDepth <= 6) return 0.0;
        if (waterDepth >= 12) return 1.0;
        return smooth((waterDepth - 6) / 6.0);
    }

    private static double value(long worldSeed, long speciesSalt, int x, int z) {
        return random(worldSeed, speciesSalt, x, z);
    }

    private static double random(long worldSeed, long speciesSalt, int x, int z) {
        long mixed = worldSeed ^ speciesSalt;
        mixed ^= (long) x * 0x632BE59BD9B4E019L;
        mixed ^= (long) z * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double clamp(double value) {
        return value < 0.0 ? 0.0 : Math.min(value, 1.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
