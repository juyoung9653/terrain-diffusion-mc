package com.github.xandergos.terraindiffusionmc.pipeline;

/** Exact deterministic detail added to upsampled TD terrain. */
final class ElevationDetail {
    private static final FastNoiseLite COARSE = noise(99999, 1f / 24f, 3, 2f, .5f);
    private static final FastNoiseLite FINE = noise(88888, 1f / 6f, 2, 2f, .6f);

    private ElevationDetail() { }

    static float[] apply(float[] smooth, float[] padded, int i0, int j0, int height, int width,
                         float metresPerBlock, float nativeResolution) {
        if (smooth.length != height * width || padded.length != (height + 2) * (width + 2))
            throw new IllegalArgumentException("Elevation detail dimensions differ");
        float[] result = smooth.clone();
        float[] gradient = gradient(padded, height + 2, width + 2, height, width);
        float norm = 40f * metresPerBlock / nativeResolution;
        float coarse = 100f * metresPerBlock / nativeResolution;
        float fine = 70f * metresPerBlock / nativeResolution;
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            int at = z * width + x;
            if (smooth[at] < 0) continue;
            float slope = Math.min(1f, gradient[at] / norm);
            slope = slope * slope * (float) Math.sqrt(slope);
            result[at] = smooth[at]
                    + COARSE.GetNoise(j0 + x, i0 + z) * coarse * slope
                    + FINE.GetNoise(j0 + x, i0 + z) * fine * slope;
        }
        return result;
    }

    /** Adds detail to the interior of a padded absolute-coordinate field. */
    static float[] interior(float[] padded, int paddedHeight, int paddedWidth, int i0, int j0,
                            float metresPerBlock, float nativeResolution) {
        if (paddedHeight < 3 || paddedWidth < 3 || padded.length != paddedHeight * paddedWidth)
            throw new IllegalArgumentException("Invalid padded elevation field");
        int height = paddedHeight - 2, width = paddedWidth - 2;
        float[] smooth = new float[height * width];
        for (int z = 0; z < height; z++)
            System.arraycopy(padded, (z + 1) * paddedWidth + 1, smooth, z * width, width);
        return apply(smooth, padded, i0 + 1, j0 + 1, height, width, metresPerBlock, nativeResolution);
    }

    private static FastNoiseLite noise(int seed, float frequency, int octaves, float lacunarity, float gain) {
        FastNoiseLite value = new FastNoiseLite(seed);
        value.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        value.SetFrequency(frequency);
        value.SetFractalType(FastNoiseLite.FractalType.FBm);
        value.SetFractalOctaves(octaves);
        value.SetFractalLacunarity(lacunarity);
        value.SetFractalGain(gain);
        return value;
    }

    private static float[] gradient(float[] padded, int paddedHeight, int paddedWidth,
                                    int height, int width) {
        int[] sx = {-1, 0, 1, -2, 0, 2, -1, 0, 1};
        int[] sz = {-1, -2, -1, 0, 0, 0, 1, 2, 1};
        float[] result = new float[height * width];
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            float dx = 0, dz = 0;
            for (int k = 0; k < 9; k++) {
                float value = padded[(z + k / 3) * paddedWidth + x + k % 3];
                dx += value * sx[k]; dz += value * sz[k];
            }
            dx /= 8f; dz /= 8f;
            result[z * width + x] = (float) Math.sqrt(dx * dx + dz * dz);
        }
        return result;
    }
}
