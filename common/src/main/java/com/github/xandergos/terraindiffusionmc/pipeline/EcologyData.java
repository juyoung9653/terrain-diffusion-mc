package com.github.xandergos.terraindiffusionmc.pipeline;

/** Immutable, tile-owned ecology fields alongside the unchanged TD heightmap. */
public final class EcologyData {
    public final int width, height;
    private final short[] biomeIds, terrainBiomeIds;
    private final float[] cover, temperature, moisture, slope;

    public EcologyData(int width, int height, short[] biomeIds, short[] terrainBiomeIds,
                       float[] cover, float[] temperature, float[] moisture, float[] slope) {
        this.width = width; this.height = height;
        this.biomeIds = biomeIds; this.terrainBiomeIds = terrainBiomeIds;
        this.cover = cover; this.temperature = temperature; this.moisture = moisture; this.slope = slope;
    }
    public int biomeId(int x, int z) { return biomeIds[z * width + x]; }
    public short terrainBiomeId(int x, int z) { return terrainBiomeIds[z * width + x]; }
    public EcologyClassifier.Sample sample(int x, int z) {
        int i = z * width + x;
        return new EcologyClassifier.Sample(biomeIds[i], cover[i], temperature[i], moisture[i], slope[i]);
    }
}
