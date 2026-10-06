package com.github.xandergos.terraindiffusionmc.world.river;

/** Local, bounded river-edge policies; distances and widths are Minecraft blocks. */
public final class NaturalRiverBankMargin {
    private NaturalRiverBankMargin() { }

    /** A wider channel has a wider apron, with coherent bends instead of block noise. */
    public static double width(double halfWidth, int x, int z) {
        if (!Double.isFinite(halfWidth) || halfWidth <= 0) return 0;
        double base = Math.min(5.0, .75 + .65 * Math.sqrt(halfWidth * 2));
        return base * (.8 + .4 * NaturalRiverBankShape.exposure(x, z));
    }

    /** Maps the local apron to the ecology policy's four-block moisture falloff. */
    public static float moistureDistance(double distance, double halfWidth, int x, int z) {
        double margin = width(halfWidth, x, z);
        if (margin == 0 || !Double.isFinite(distance)) return Float.POSITIVE_INFINITY;
        return (float) (4 * Math.max(0, distance - halfWidth) / margin);
    }

    /** The solid block at water Y contains a full source; no air-height cap is needed. */
    public static int containmentCrest(int version, int adjacentWater) {
        return adjacentWater + (version >= 7 ? 0 : 1);
    }
}
