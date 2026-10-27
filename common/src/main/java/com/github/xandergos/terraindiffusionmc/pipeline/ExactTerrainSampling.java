package com.github.xandergos.terraindiffusionmc.pipeline;

/** Sparse reads preserving the full tile resize's floating-point operation order. */
final class ExactTerrainSampling {
    private ExactTerrainSampling() { }

    static float[] resizeCrop(float[] source, int sourceI, int sourceJ, int sourceH, int sourceW,
                              int referenceI, int referenceJ, int referenceH, int referenceW,
                              int scale, int i, int j, int h, int w) {
        return resizeCrop(source, 0, sourceI, sourceJ, sourceH, sourceW,
                referenceI, referenceJ, referenceH, referenceW, scale, i, j, h, w);
    }

    /** Reads one contiguous plane from a packed source without copying it first. */
    static float[] resizeCrop(float[] source, int sourceOffset,
                              int sourceI, int sourceJ, int sourceH, int sourceW,
                              int referenceI, int referenceJ, int referenceH, int referenceW,
                              int scale, int i, int j, int h, int w) {
        float[] result = new float[h * w];
        for (int r = 0; r < h; r++) {
            int outputR = i + r - referenceI * scale;
            float sr = ((outputR + .5f) * referenceH / (referenceH * scale)) - .5f;
            int r0 = (int) Math.floor(sr), r1 = r0 + 1;
            float wr = sr - r0;
            r0 += referenceI - sourceI; r1 += referenceI - sourceI;
            for (int c = 0; c < w; c++) {
                int outputC = j + c - referenceJ * scale;
                float sc = ((outputC + .5f) * referenceW / (referenceW * scale)) - .5f;
                int c0 = (int) Math.floor(sc), c1 = c0 + 1;
                float wc = sc - c0;
                c0 += referenceJ - sourceJ; c1 += referenceJ - sourceJ;
                if (r0 < 0 || r1 >= sourceH || c0 < 0 || c1 >= sourceW)
                    throw new IllegalArgumentException("Sparse elevation read lacks interpolation padding");
                result[r * w + c] = (1 - wr) * (1 - wc) * source[sourceOffset + r0 * sourceW + c0]
                        + (1 - wr) * wc * source[sourceOffset + r0 * sourceW + c1]
                        + wr * (1 - wc) * source[sourceOffset + r1 * sourceW + c0]
                        + wr * wc * source[sourceOffset + r1 * sourceW + c1];
            }
        }
        return result;
    }
}
