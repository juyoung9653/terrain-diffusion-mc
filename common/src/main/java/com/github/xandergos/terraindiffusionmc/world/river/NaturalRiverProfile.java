package com.github.xandergos.terraindiffusionmc.world.river;

/** Versioned TD river policy. Distances are world blocks, widths are half-widths. */
public final class NaturalRiverProfile {
    public static final int VERSION = 10;
    private NaturalRiverProfile() { }

    /** A coastal source must drain a substantial inland reach; tributary joins are exempt. */
    public static int minimumCoastalNodes(int gridSpacing, int scale) {
        if (gridSpacing <= 0) throw new IllegalArgumentException("gridSpacing must be positive");
        int blocks = 256 * Math.max(2, Math.min(6, scale)) / 2;
        return Math.max(1, (int) Math.ceil(blocks / (double) gridSpacing));
    }

    /** Four times the native minimum accumulation, without increasing every river's wetness. */
    public static double minimumAccumulation(double nativeValue) {
        return Math.max(24.0, nativeValue);
    }

    /** V2 rejects small independent sea-draining chains before native geometry is built. */
    public static int coastalNodesV2(int gridSpacing, int scale) {
        if (gridSpacing <= 0) throw new IllegalArgumentException("gridSpacing must be positive");
        int blocks = 640 * Math.max(2, Math.min(6, scale)) / 2;
        return Math.max(1, (int) Math.ceil(blocks / (double) gridSpacing));
    }

    /** A larger routing domain permits inland tributaries without widening the water. */
    public static int watershedPlateV3(int nativePlate, int scale) {
        return Math.min(8192, Math.max(nativePlate, 2048 * Math.max(2, Math.min(6, scale)) / 2));
    }

    /** Smooth monotone small-channel response; preserve source taper and larger rivers. */
    public static double narrowHalfWidthV4(double width) {
        if (!Double.isFinite(width) || width < 0) throw new IllegalArgumentException("Invalid river width");
        if (width <= .65 || width >= 12) return width;
        return .65 + 11.35 * Math.pow((width - .65) / 11.35, 1.7);
    }
}
