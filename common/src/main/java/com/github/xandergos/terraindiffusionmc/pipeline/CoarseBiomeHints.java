package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;

/** Approximate surface biomes used only to prioritize structure-search tiles. */
public final class CoarseBiomeHints {
    private final int minX, minZ, width, height, blocksPerCell;
    private final short[] vanilla, terralith;

    /** Input has a one-cell border on all sides; channels match WorldPipeline's coarse map. */
    public CoarseBiomeHints(FloatTensor tensor, int minX, int minZ, int width, int height,
                            int blocksPerCell, float metersPerCell) {
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.height = height;
        this.blocksPerCell = blocksPerCell;
        int paddedWidth = width + 2, paddedHeight = height + 2;
        int plane = paddedWidth * paddedHeight;
        float[] paddedElevation = new float[plane];
        for (int i = 0; i < plane; i++) {
            float value = channel(tensor.data, plane, 0, i);
            paddedElevation[i] = value * Math.abs(value);
        }
        float[] elevation = new float[width * height];
        float[] climate = new float[4 * width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int src = (z + 1) * paddedWidth + x + 1;
                int dst = z * width + x;
                elevation[dst] = paddedElevation[src];
                for (int ch = 0; ch < 4; ch++) {
                    climate[ch * width * height + dst] = channel(tensor.data, plane, ch + 2, src);
                }
            }
        }
        terralith = new short[width * height];
        vanilla = BiomeClassifier.classify(elevation, climate, minZ, minX,
                paddedElevation, height, width, metersPerCell, terralith);
    }

    private static float channel(float[] data, int plane, int channel, int index) {
        float weight = data[6 * plane + index];
        return weight > 1e-6f ? data[channel * plane + index] / weight : 0f;
    }

    public short vanillaAt(int blockX, int blockZ) { return vanilla[index(blockX, blockZ)]; }
    public short terralithAt(int blockX, int blockZ) { return terralith[index(blockX, blockZ)]; }

    private int index(int blockX, int blockZ) {
        int x = Math.floorDiv(blockX, blocksPerCell) - minX;
        int z = Math.floorDiv(blockZ, blocksPerCell) - minZ;
        if (x < 0 || x >= width || z < 0 || z >= height)
            throw new IllegalArgumentException("Position outside coarse biome hint rectangle");
        return z * width + x;
    }
}
