package com.github.xandergos.terraindiffusionmc.compat.streams;

/** Pure arithmetic for TD's per-engine Streams scaling policy. */
final class StreamsScaleMath {
    static final int DEFAULT_SCALE = 2;
    static final int MAX_NATIVE_PLATE_BLOCKS = 8192;
    static final double MAX_NATIVE_HALF_WIDTH = 32.0D;

    private StreamsScaleMath() {
    }

    /** Plate scale is already measured in world blocks, independently of grid spacing. */
    static int plateScale(int nativePlateScale, int scale) {
        if (scale <= DEFAULT_SCALE) return nativePlateScale;
        long scaled = Math.round(Math.max(1, nativePlateScale) * (scale / (double) DEFAULT_SCALE));
        return (int) Math.max(1L, Math.min(MAX_NATIVE_PLATE_BLOCKS, scaled));
    }

    static double widthScale(double nativeWidthScale, int scale) {
        if (scale <= DEFAULT_SCALE) return nativeWidthScale;
        return nativeWidthScale * (1.0D + 0.10D * (scale - DEFAULT_SCALE));
    }

    static double maxWidth(double nativeMaxWidth, int scale) {
        if (scale <= DEFAULT_SCALE) return nativeMaxWidth;
        return Math.min(MAX_NATIVE_HALF_WIDTH,
                nativeMaxWidth * (scale / (double) DEFAULT_SCALE));
    }

}
