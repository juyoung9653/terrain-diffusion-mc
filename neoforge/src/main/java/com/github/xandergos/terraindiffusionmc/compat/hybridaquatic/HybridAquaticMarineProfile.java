package com.github.xandergos.terraindiffusionmc.compat.hybridaquatic;

/** TD placement coordinates for HA's unchanged native marine density/biome rules. */
public final class HybridAquaticMarineProfile {
    private HybridAquaticMarineProfile() { }

    public static double continentalnessForHeight(int y) {
        // Native trench band [-.71, -.63] occupies pre-carve depths 48..64 blocks.
        // Shallow water (depth <=24) stays outside both the trench and deep-ocean bands.
        if (y <= -33) return -1.05;
        if (y < -1) return interpolate(y, -33, -1, -1.05, -.71);
        if (y < 15) return interpolate(y, -1, 15, -.71, -.63);
        if (y < 39) return interpolate(y, 15, 39, -.63, -.455);
        if (y < 63) return interpolate(y, 39, 63, -.455, -.19);
        return y < 127 ? interpolate(y, 63, 127, -.19, 1) : 1;
    }

    private static double interpolate(double value, double lo, double hi, double a, double b) {
        return a + (value - lo) / (hi - lo) * (b - a);
    }

    /** Matching alias only; never replaces a biome unless a native HA injector matches. */
    public static String oceanAlias(String namespace, String path, int groundY) {
        if (!namespace.equals("minecraft") && !namespace.equals("terralith")) return null;
        if (groundY >= 63) return null;
        boolean deep = groundY <= 39;
        return switch (path) {
            case "warm_ocean", "deep_warm_ocean" -> "warm_ocean";
            case "lukewarm_ocean", "deep_lukewarm_ocean" -> deep ? "deep_lukewarm_ocean" : "lukewarm_ocean";
            case "cold_ocean", "deep_cold_ocean" -> deep ? "deep_cold_ocean" : "cold_ocean";
            case "frozen_ocean", "deep_frozen_ocean" -> deep ? "deep_frozen_ocean" : "frozen_ocean";
            case "ocean", "deep_ocean" -> deep ? "deep_ocean" : "ocean";
            default -> null;
        };
    }
}
