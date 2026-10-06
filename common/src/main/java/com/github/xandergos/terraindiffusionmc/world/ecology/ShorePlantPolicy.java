package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Bounded, seed-stable emergent reed cover in world blocks, independent of terrain scale. */
public final class ShorePlantPolicy {
    private ShorePlantPolicy() { }

    public static boolean allows(boolean marineShore, boolean freshwater, boolean sandy,
                                 long seed, int x, int z) {
        return unit(seed, x, z, 0x52454544L) < probability(marineShore, freshwater, sandy, seed, x, z);
    }

    public static double probability(boolean marineShore, boolean freshwater, boolean sandy,
                                     long seed, int x, int z) {
        // TD's exposed beaches are mineral aprons; sheltered wet banks retain their land biome.
        if (marineShore && !freshwater) return 0;
        double patch = noise(seed, x, z, 12) * .65 + noise(seed ^ 0x53484F5245L, x, z, 31) * .35;
        double cover = smooth(.25, .72, patch);
        double density = freshwater ? .25 + .55 * cover : .04 + .24 * cover;
        return sandy && !freshwater ? density * .35 : density;
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
