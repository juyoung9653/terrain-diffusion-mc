package com.github.xandergos.terraindiffusionmc.pipeline;

/** Bit-exact sparse/full resize comparisons for negative coordinates and all supported scales. */
public final class ExactTerrainSamplingTest {
    public static void main(String[] args) {
        long compared = 0;
        for (int scale = 2; scale <= 6; scale++) for (int base : new int[]{-768, -256, 0, 256}) {
            int ri = Math.floorDiv(base, scale) - 2, rj = Math.floorDiv(-base - 256, scale) - 2;
            int rh = -Math.floorDiv(-(base + 256), scale) + 2 - ri;
            int rw = -Math.floorDiv(base, scale) + 2 - rj;
            float[][] nativeData = new float[rh][rw];
            for (int r = 0; r < rh; r++) for (int c = 0; c < rw; c++)
                nativeData[r][c] = (float)(Math.sin((ri + r) * .13) * 1200 + Math.cos((rj + c) * .19) * 900);
            float[][] full = LaplacianUtils.bilinearResize(nativeData, rh * scale, rw * scale);
            for (int offset : new int[]{0, 16, 112, 240}) {
                int i = base + offset, j = -base - 256 + offset;
                int si = Math.floorDiv(i, scale) - 2, sj = Math.floorDiv(j, scale) - 2;
                int sh = -Math.floorDiv(-(i + 16), scale) + 2 - si;
                int sw = -Math.floorDiv(-(j + 16), scale) + 2 - sj;
                float[] subset = new float[sh * sw];
                for (int r = 0; r < sh; r++) for (int c = 0; c < sw; c++)
                    subset[r * sw + c] = nativeData[si - ri + r][sj - rj + c];
                float[] sparse = ExactTerrainSampling.resizeCrop(subset, si, sj, sh, sw, ri, rj, rh, rw,
                        scale, i - 1, j - 1, 18, 18);
                for (int r = 0; r < 18; r++) for (int c = 0; c < 18; c++) {
                    float expected = full[i - 1 - ri * scale + r][j - 1 - rj * scale + c];
                    if (Float.floatToIntBits(expected) != Float.floatToIntBits(sparse[r * 18 + c]))
                        throw new AssertionError("scale=" + scale + " base=" + base + " offset=" + offset);
                    compared++;
                }
            }
        }
        System.out.println("PASS ExactTerrainSamplingTest: " + compared + " bit-exact full/sparse samples (scales 2..6, negative boundaries, 1-pixel Sobel halo)");
    }
}
