package com.github.xandergos.terraindiffusionmc.world.river;

/** Local, bounded river-edge policies; distances and widths are Minecraft blocks. */
public final class NaturalRiverBankMargin {
    /** Bounded dry lake-rim taper; never removes the solid block containing planned water. */
    public static int lakeShoreTarget(int originalTop, int safeWater, double distance, int x, int z) {
        long rise = (long) originalTop - safeWater;
        if (!Double.isFinite(distance) || distance < 1 || distance > 3 || rise < 1 || rise > 4)
            return originalTop;
        int step = (int) Math.floor(distance * .6 + .6 * NaturalRiverBankShape.exposure(x, z));
        long desired = (long) safeWater + step;
        return (int) Math.min(originalTop, Math.max((long) originalTop - 2, Math.max(safeWater, desired)));
    }
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

    /** V9 exposed sediment is a small part of the riparian apron, especially on tributaries. */
    public static double sedimentWidth(double halfWidth, int x, int z) {
        if (!Double.isFinite(halfWidth) || halfWidth <= 0) return 0;
        return Math.min(3.0, .25 + .28 * halfWidth)
                * (.75 + .5 * NaturalRiverBankShape.exposure(x, z));
    }

    /** Plan-only admission: availability or carving order of neighbouring chunks is irrelevant. */
    public static boolean drySediment(double distance, double halfWidth, int top, int water, int x, int z) {
        double margin = sedimentWidth(halfWidth, x, z);
        return margin > 0 && Double.isFinite(distance) && distance >= halfWidth
                && distance - halfWidth <= margin && Math.abs((long) top - water) <= 2;
    }

    /** The solid block at water Y contains a full source; no air-height cap is needed. */
    public static int containmentCrest(int version, int adjacentWater) {
        return adjacentWater + (version >= 7 ? 0 : 1);
    }
}
