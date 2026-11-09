package com.github.xandergos.terraindiffusionmc.compat.vanillakelp;

/**
 * Bounded, world-coordinate kelp thinning for vanilla's two registered ocean
 * placements.  The smooth field deliberately does not use a chunk origin, so a
 * bed can cross a 16-block border without a density step.
 */
public final class VanillaKelpPlacementPolicy {
    public static final int CANDIDATES_PER_CHUNK = 64;
    public static final double COLD_ACCEPTANCE = 0.65;
    public static final double WARM_ACCEPTANCE = 0.50;
    private static final int CELL_SIZE = 48;

    private VanillaKelpPlacementPolicy() { }

    public static boolean accept(long worldSeed, long salt, double acceptance,
                                 int blockX, int blockZ) {
        return accept(worldSeed, salt, acceptance, 5, blockX, blockZ);
    }

    /**
     * Kelp needs open water above its root. Keep sparse pioneer plants on a
     * shallow sandy shelf, then restore ordinary bed density once a column has
     * enough water for a grown stalk. The caller caps the local depth read.
     */
    public static boolean accept(long worldSeed, long salt, double acceptance, int waterDepth,
                                 int blockX, int blockZ) {
        return random(worldSeed, salt ^ 0xD1342543DE82EF95L, blockX, blockZ)
                < probability(worldSeed, salt, acceptance, waterDepth, blockX, blockZ);
    }

    public static double probability(long worldSeed, long salt, double acceptance,
                                     int blockX, int blockZ) {
        return probability(worldSeed, salt, acceptance, 5, blockX, blockZ);
    }

    public static double probability(long worldSeed, long salt, double acceptance, int waterDepth,
                                     int blockX, int blockZ) {
        double density = density(worldSeed, salt, blockX, blockZ);
        double edge = clamp((density - 0.26) / 0.48);
        return acceptance * shallowWaterFactor(waterDepth) * smooth(edge);
    }

    /** One- and two-block shore shelves stay sparse; depth five is normal ocean kelp. */
    public static double shallowWaterFactor(int waterDepth) {
        if (waterDepth <= 0) return 0.0;
        if (waterDepth == 1) return 0.08;
        if (waterDepth == 2) return 0.18;
        if (waterDepth == 3) return 0.40;
        if (waterDepth == 4) return 0.70;
        return 1.0;
    }

    /** Continuous field in [0, 1), sampled only from world coordinates. */
    public static double density(long worldSeed, long salt, int blockX, int blockZ) {
        int cellX = Math.floorDiv(blockX, CELL_SIZE);
        int cellZ = Math.floorDiv(blockZ, CELL_SIZE);
        double x = Math.floorMod(blockX, CELL_SIZE) / (double) CELL_SIZE;
        double z = Math.floorMod(blockZ, CELL_SIZE) / (double) CELL_SIZE;
        double a = random(worldSeed, salt, cellX, cellZ);
        double b = random(worldSeed, salt, cellX + 1, cellZ);
        double c = random(worldSeed, salt, cellX, cellZ + 1);
        double d = random(worldSeed, salt, cellX + 1, cellZ + 1);
        double sx = smooth(x);
        double sz = smooth(z);
        return lerp(lerp(a, b, sx), lerp(c, d, sx), sz);
    }

    private static double random(long worldSeed, long salt, int x, int z) {
        long mixed = worldSeed ^ salt;
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
