package com.github.xandergos.terraindiffusionmc.pipeline;

/** Immutable finite-neighbourhood shoreline measurements; this is not hydrologic connectivity. */
public final class ShoreContext {
    private final float[] waterDistanceBlocks, openWaterFraction, reliefSlope;
    public ShoreContext(float[] distance, float[] open, float[] relief) {
        if (distance == null || open == null || relief == null
                || distance.length != open.length || distance.length != relief.length)
            throw new IllegalArgumentException("shore arrays must be non-null and equally sized");
        this.waterDistanceBlocks = distance.clone();
        this.openWaterFraction = open.clone();
        this.reliefSlope = relief.clone();
    }
    public float waterDistanceBlocks(int index) { return waterDistanceBlocks[index]; }
    public float openWaterFraction(int index) { return openWaterFraction[index]; }
    public float reliefSlope(int index) { return reliefSlope[index]; }
    static ShoreContext empty(int n) {
        float[] distance = new float[n];
        java.util.Arrays.fill(distance, Float.POSITIVE_INFINITY);
        return new ShoreContext(distance, new float[n], new float[n]);
    }
}
