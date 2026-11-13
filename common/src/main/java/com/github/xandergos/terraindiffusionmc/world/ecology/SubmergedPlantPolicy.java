package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Seed-stable density for generated seagrass at a single submerged column. */
public final class SubmergedPlantPolicy {
    private static final long SEAGRASS_SALT = 0x5345414752415353L;
    private static final long PICKLE_SALT = 0x5345415049434B4CL;
    private SubmergedPlantPolicy() { }

    public static boolean allows(boolean naturalTerrain, boolean submerged, boolean marine,
                                 boolean freshwater, boolean sediment, int waterDepth,
                                 long seed, int x, int z) {
        return allows(naturalTerrain, submerged, marine, freshwater, sediment, waterDepth,
                false, 0, false, seed, x, z);
    }

    public static boolean allows(boolean naturalTerrain, boolean submerged, boolean marine,
                                 boolean freshwater, boolean sediment, int waterDepth,
                                 boolean sandy, double exposure, boolean tall,
                                 long seed, int x, int z) {
        return unit(seed, x, z, SEAGRASS_SALT)
                < probability(naturalTerrain, submerged, marine, freshwater, sediment,
                waterDepth, sandy, exposure, tall, seed, x, z);
    }

    public static double probability(boolean naturalTerrain, boolean submerged, boolean marine,
                                     boolean freshwater, boolean sediment, int waterDepth,
                                     long seed, int x, int z) {
        return probability(naturalTerrain, submerged, marine, freshwater, sediment,
                waterDepth, false, 0, false, seed, x, z);
    }

    /** Exposure is 0 for a protected bank and 1 for an open coast. */
    public static double probability(boolean naturalTerrain, boolean submerged, boolean marine,
                                     boolean freshwater, boolean sediment, int waterDepth,
                                     boolean sandy, double exposure, boolean tall,
                                     long seed, int x, int z) {
        if (!naturalTerrain) return 1;
        if (!submerged || waterDepth < 1) return 0;
        exposure = clamp(exposure);
        // Freshwater and non-marine water retain their existing density, without
        // borrowing the marine wave-exposure approximation.
        if (freshwater || !marine) {
            double patch = .65 * noise(seed, x, z, 14)
                    + .35 * noise(seed ^ 0x534541424544L, x, z, 37);
            double cover = smooth(.32, .72, patch);
            double density = freshwater ? .06 + .34 * cover : .04 + .22 * cover;
            double depthFactor = waterDepth == 1 ? .65 : waterDepth == 2 ? .9
                    : waterDepth <= 4 ? 1 : waterDepth <= 7 ? .75 : .45;
            double shortProbability = density * depthFactor * (sediment ? 1 : .5);
            return tall ? shortProbability * tallFraction(patch, waterDepth) : shortProbability;
        }
        // Broad, coherent marine beds leave low-cover exposed sand bare.
        double patch = .62 * noise(seed, x, z, 27)
                + .38 * noise(seed ^ 0x4D4152494E454245L, x, z, 71);
        double cover = smooth(.42, .74, patch);
        double shortProbability = (.015 * cover + .34 * cover * cover)
                * smooth(.8, 4.0, waterDepth) * (1 - smooth(16, 32, waterDepth));
        // The shallow exposed-sand penalty fades continuously, avoiding a depth band.
        double shallow = 1 - smooth(3, 10, waterDepth);
        shortProbability *= 1 - exposure * (sandy ? .92 : .55) * shallow;
        shortProbability *= sediment ? 1 : .45;
        return tall ? shortProbability * tallFraction(patch, waterDepth) : shortProbability;
    }

    /** Separate warm-marine policy for sea pickles, including coral feature writes. */
    public static boolean allowsSeaPickle(boolean naturalTerrain, boolean submerged,
                                          boolean marine, boolean freshwater, boolean warm,
                                          boolean coral, boolean sandy, int waterDepth,
                                          double exposure, long seed, int x, int z) {
        return unit(seed, x, z, PICKLE_SALT)
                < seaPickleProbability(naturalTerrain, submerged, marine, freshwater, warm,
                coral, sandy, waterDepth, exposure, seed, x, z);
    }

    public static double seaPickleProbability(boolean naturalTerrain, boolean submerged,
                                              boolean marine, boolean freshwater, boolean warm,
                                              boolean coral, boolean sandy, int waterDepth,
                                              double exposure, long seed, int x, int z) {
        if (!naturalTerrain) return 1;
        if (!submerged || freshwater || !marine || !warm || waterDepth < 1) return 0;
        exposure = clamp(exposure);
        double patch = .70 * noise(seed ^ PICKLE_SALT, x, z, 11)
                + .30 * noise(seed ^ 0x524545465049434BL, x, z, 29);
        double bed = smooth(coral ? .34 : .60, coral ? .72 : .82, patch);
        // Reef colonies are visible; bare sand is only an occasional accent.
        double density = coral ? (.025 + .22 * bed * bed) : .012 * bed * bed;
        double depth = smooth(.8, 3.5, waterDepth) * (1 - smooth(14, 30, waterDepth));
        double shallow = 1 - smooth(3, 9, waterDepth);
        return density * depth * (1 - exposure * (sandy ? .94 : .55) * shallow);
    }

    private static double tallFraction(double patch, int waterDepth) {
        return smooth(1, 3, waterDepth) * (.05 + .52 * smooth(.54, .82, patch));
    }

    private static double noise(long seed, int x, int z, int size) {
        int gx = Math.floorDiv(x, size), gz = Math.floorDiv(z, size);
        double fx = smooth(0, 1, Math.floorMod(x, size) / (double) size);
        double fz = smooth(0, 1, Math.floorMod(z, size) / (double) size);
        double a = unit(seed, gx, gz, 0), b = unit(seed, gx + 1, gz, 0);
        double c = unit(seed, gx, gz + 1, 0), d = unit(seed, gx + 1, gz + 1, 0);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private static double unit(long seed, int x, int z, long salt) {
        long h = (seed ^ salt) + 0x9E3779B97F4A7C15L * x + 0xC2B2AE3D27D4EB4FL * z;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return ((h ^ (h >>> 31)) >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double low, double high, double value) {
        double t = Math.max(0, Math.min(1, (value - low) / (high - low)));
        return t * t * (3 - 2 * t);
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
