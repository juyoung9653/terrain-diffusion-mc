package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Seed-stable density for generated seagrass at a single submerged column. */
public final class SubmergedPlantPolicy {
    private SubmergedPlantPolicy() { }

    public static boolean allows(boolean naturalTerrain, boolean submerged, boolean marine,
                                 boolean freshwater, boolean sediment, int waterDepth,
                                 long seed, int x, int z) {
        return unit(seed, x, z, 0x5345414752415353L)
                < probability(naturalTerrain, submerged, marine, freshwater,
                sediment, waterDepth, seed, x, z);
    }

    public static double probability(boolean naturalTerrain, boolean submerged, boolean marine,
                                     boolean freshwater, boolean sediment, int waterDepth,
                                     long seed, int x, int z) {
        if (!naturalTerrain) return 1;
        if (!submerged || waterDepth < 1) return 0;
        // The two smooth scales form beds without tying density to chunk boundaries.
        double patch = .65 * noise(seed, x, z, 14)
                + .35 * noise(seed ^ 0x534541424544L, x, z, 37);
        double cover = smooth(.32, .72, patch);
        double density = freshwater ? .06 + .34 * cover
                : marine ? .025 + .25 * cover : .04 + .22 * cover;
        double depthFactor = waterDepth == 1 ? .65 : waterDepth == 2 ? .9
                : waterDepth <= 4 ? 1 : waterDepth <= 7 ? .75 : .45;
        return density * depthFactor * (sediment ? 1 : .5);
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
}
