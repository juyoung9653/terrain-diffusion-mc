package com.github.xandergos.terraindiffusionmc.world.river;

/**
 * Pure, conservative target-height policy for the dry banks beside narrow
 * native river channels.  Callers remain responsible for deciding whether a
 * particular block may safely be replaced.
 */
public final class NaturalRiverBankShape {
    private static final double CORRIDOR_WIDTH = 5.0;
    private static final double FULL_WIDTH = 2.0;
    private static final double MAX_WIDTH = 4.0;
    private static final double STEEP_SLOPE = 0.20;
    private static final double GENTLE_RAMP = 0.45;
    private static final double STEEP_RAMP = 0.80;
    private static final int MAX_CUT = 3;

    private NaturalRiverBankShape() {}

    /**
     * Returns a conservative top-block height for a narrow river bank.
     * Invalid, wide, in-channel, or out-of-corridor samples retain their
     * native height.  The result only lowers excessive dry soil, retains a
     * one-block freeboard above water, and never cuts more than three blocks.
     */
    public static int targetHeight(int originalTop, int safeWater, int seaLevel,
                                   double distance, double halfWidth, double slope) {
        if (!Double.isFinite(distance) || !Double.isFinite(halfWidth) || !Double.isFinite(slope)
                || halfWidth <= 0.0 || halfWidth >= MAX_WIDTH || slope < 0.0
                || safeWater <= seaLevel + 2) {
            return originalTop;
        }

        double bankDistance = distance - halfWidth;
        if (bankDistance <= 0.0 || bankDistance >= CORRIDOR_WIDTH) return originalTop;

        double widthFade = halfWidth <= FULL_WIDTH ? 1.0 : (MAX_WIDTH - halfWidth) / (MAX_WIDTH - FULL_WIDTH);
        double edgeFade = 1.0 - bankDistance / CORRIDOR_WIDTH;
        double slopeBlend = smooth(Math.min(1.0, slope / STEEP_SLOPE));
        double ramp = lerp(GENTLE_RAMP, STEEP_RAMP, slopeBlend);
        double desired = safeWater + 1.0 + bankDistance * ramp;
        double availableCut = originalTop - Math.ceil(desired);
        int uncappedCut = availableCut <= 0.0 ? 0 : (int) Math.min(MAX_CUT, availableCut);
        int cut = Math.min(uncappedCut, (int) Math.round(uncappedCut * widthFade * edgeFade));
        if (cut <= 0) return originalTop;

        int target = originalTop - cut;
        return Math.max(safeWater + 1, target);
    }

    /** V7 wide-bank apron; capped to the caller's eight-block plan halo. */
    public static double wideCorridor(double halfWidth) {
        return !Double.isFinite(halfWidth) || halfWidth < 4 ? 0 : Math.min(8, 4 + Math.sqrt(halfWidth));
    }

    /**
     * Soften only excessive dry soil on a wide river's outer slope. The solid
     * block at the highest neighbouring water Y remains, even at stepped channels.
     * The final 1.5 blocks taper the cut into retained terrain; no cut exceeds two.
     */
    public static int wideTargetHeight(int originalTop, int safeWater, int seaLevel,
                                       double distance, double halfWidth, double slope, int x, int z) {
        double apron = wideCorridor(halfWidth);
        double edge = distance - halfWidth;
        if (apron == 0 || !Double.isFinite(distance) || !Double.isFinite(slope) || slope < 0
                || edge <= .5 || edge >= apron || safeWater <= seaLevel + 2) return originalTop;
        double slopeBlend = smooth(Math.min(1, slope / STEEP_SLOPE));
        double ramp = lerp(.35, .8, slopeBlend);
        double drift = .6 * (exposure(x, z) - .5);
        // Flat channels do not need a second dry block above their containing block.
        // Blend that extra relief back on steep reaches instead of leaving a uniform green lip.
        int desired = safeWater + (int) Math.floor(Math.max(0, edge - 1.5 + drift) * ramp + slopeBlend);
        int available = Math.min(2, Math.max(0, originalTop - desired));
        double fade = smooth(Math.min(1, (apron - edge) / 1.5));
        int cut = Math.min(available, (int) Math.round(available * fade));
        return originalTop - cut;
    }

    /**
     * Deterministic, spatially smooth coordinate field for shore-material
     * variation.  It is independent of world state and stays in {@code [0,1]}.
     */
    public static double exposure(int x, int z) {
        final int cellSize = 16;
        int cellX = Math.floorDiv(x, cellSize);
        int cellZ = Math.floorDiv(z, cellSize);
        double localX = Math.floorMod(x, cellSize) / (double) cellSize;
        double localZ = Math.floorMod(z, cellSize) / (double) cellSize;
        double smoothX = smooth(localX);
        double smoothZ = smooth(localZ);
        double a = lerp(value(cellX, cellZ), value(cellX + 1, cellZ), smoothX);
        double b = lerp(value(cellX, cellZ + 1), value(cellX + 1, cellZ + 1), smoothX);
        return lerp(a, b, smoothZ);
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double a, double b, double amount) {
        return a + (b - a) * amount;
    }

    private static double value(int x, int z) {
        long bits = ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL);
        bits ^= bits >>> 30;
        bits *= 0xBF58476D1CE4E5B9L;
        bits ^= bits >>> 27;
        bits *= 0x94D049BB133111EBL;
        bits ^= bits >>> 31;
        return (bits >>> 11) * 0x1.0p-53;
    }
}
