package com.github.xandergos.terraindiffusionmc.world;

/** New deep-world bathymetry. A monotone shelf/slope/basin curve in model meters. */
public final class OceanDepthProfile {
    private static final double[] METERS = {200, 1000, 4000, 10000, 16000};
    private static final double[][] DEPTHS = new double[6][];
    private static final double[][] SLOPES = new double[6][];

    static {
        for (int scale = 1; scale <= 6; scale++) {
            double[] depths = {1 + Math.sqrt(210) - Math.sqrt(10),
                    48 * scale, 120 * scale, 180 * scale, 220 * scale};
            double[] slopes = new double[METERS.length];
            slopes[0] = 1 / (2 * Math.sqrt(210));
            for (int i = 1; i < slopes.length - 1; i++) {
                double left = (depths[i] - depths[i - 1]) / (METERS[i] - METERS[i - 1]);
                double right = (depths[i + 1] - depths[i]) / (METERS[i + 1] - METERS[i]);
                slopes[i] = 2 * left * right / (left + right);
            }
            // Zero slope at the lower limit keeps the deepest ocean above deep bedrock.
            slopes[slopes.length - 1] = 0;
            DEPTHS[scale - 1] = depths;
            SLOPES[scale - 1] = slopes;
        }
    }

    private OceanDepthProfile() { }

    public static int oceanBoundary(short meters, int scale) {
        if (meters >= 0 || scale < 1 || scale > 6)
            throw new IllegalArgumentException("Ocean elevation must be negative and scale in 1..6");
        double depth = -(double) meters;
        // Keep all existing coastal shallows exactly, including their rounding.
        if (depth <= METERS[0])
            return 62 + (int) (-Math.sqrt(depth + 10) + Math.sqrt(10));
        double[] values = DEPTHS[scale - 1], slopes = SLOPES[scale - 1];
        if (depth >= METERS[METERS.length - 1]) return 63 - 220 * scale;
        int i = 0;
        while (depth > METERS[i + 1]) i++;
        double span = METERS[i + 1] - METERS[i], t = (depth - METERS[i]) / span;
        double t2 = t * t, t3 = t2 * t;
        double result = (2 * t3 - 3 * t2 + 1) * values[i]
                + (t3 - 2 * t2 + t) * span * slopes[i]
                + (-2 * t3 + 3 * t2) * values[i + 1]
                + (t3 - t2) * span * slopes[i + 1];
        return 63 - (int) Math.floor(result);
    }
}
