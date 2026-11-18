package com.github.xandergos.terraindiffusionmc.pipeline;

/** Immutable finite-neighbourhood shoreline measurements; this is not hydrologic connectivity. */
public final class ShoreContext {
    private final float[] waterDistanceBlocks, openWaterFraction, reliefSlope, valleyPotential, marinePotential;
    private final WetlandTerrain.Context wetlandTerrain;
    public ShoreContext(float[] distance, float[] open, float[] relief) {
        this(distance, open, relief, new float[distance == null ? 0 : distance.length]);
    }
    public ShoreContext(float[] distance, float[] open, float[] relief, float[] valley) {
        this(distance, open, relief, valley, new float[distance == null ? 0 : distance.length]);
    }
    public ShoreContext(float[] distance, float[] open, float[] relief, float[] valley, float[] marine) {
        this(distance, open, relief, valley, marine, null);
    }
    public ShoreContext(float[] distance, float[] open, float[] relief, float[] valley, float[] marine,
                        WetlandTerrain.Context wetlandTerrain) {
        if (distance == null || open == null || relief == null
                || valley == null || distance.length != open.length || distance.length != relief.length
                || distance.length != valley.length || marine == null || distance.length != marine.length)
            throw new IllegalArgumentException("shore arrays must be non-null and equally sized");
        this.waterDistanceBlocks = distance.clone();
        this.openWaterFraction = open.clone();
        this.reliefSlope = relief.clone();
        this.valleyPotential = valley.clone();
        this.marinePotential = marine.clone();
        this.wetlandTerrain = wetlandTerrain;
    }
    public float waterDistanceBlocks(int index) { return waterDistanceBlocks[index]; }
    public float openWaterFraction(int index) { return openWaterFraction[index]; }
    public float reliefSlope(int index) { return reliefSlope[index]; }
    /** Terrain-only drainage convergence proxy; never depends on later Streams generation. */
    public float valleyPotential(int index) { return valleyPotential[index]; }
    /** Lowland-connected marine habitat; independent of the narrow beach measurements. */
    public float marinePotential(int index) { return marinePotential[index]; }
    public WetlandTerrain.Context wetlandTerrain() { return wetlandTerrain; }
    static ShoreContext empty(int n) {
        float[] distance = new float[n];
        java.util.Arrays.fill(distance, Float.POSITIVE_INFINITY);
        return new ShoreContext(distance, new float[n], new float[n]);
    }
}
