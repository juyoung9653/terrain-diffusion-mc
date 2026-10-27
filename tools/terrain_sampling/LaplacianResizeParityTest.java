package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Frozen-reference parity checks for full and cropped bilinear resizing. */
public final class LaplacianResizeParityTest {
    public static void main(String[] args) {
        int cases = 0;
        int[][] sourceSizes = {{1, 1}, {1, 2}, {2, 1}, {2, 2}, {3, 5}, {7, 4}, {19, 23}};
        for (int[] sourceSize : sourceSizes) {
            float[][] source = source(sourceSize[0], sourceSize[1], sourceSize[0] * 1009L + sourceSize[1]);
            for (int scale = 1; scale <= 6; scale++) {
                int dstH = sourceSize[0] * scale + 1;
                int dstW = sourceSize[1] * scale + 2;
                float[][] expected = frozenResize(source, dstH, dstW);
                compare(expected, LaplacianUtils.bilinearResize(source, dstH, dstW), "full " + Arrays.toString(sourceSize) + " scale=" + scale);
                int cropDstH = sourceSize[0] * scale, cropDstW = sourceSize[1] * scale;
                cases += checkInteriorCrops(frozenResize(source, cropDstH, cropDstW), source, cropDstH, cropDstW, scale);
            }
        }
        System.out.println("LaplacianResizeParityTest: PASS cases=" + cases);
        if (Arrays.asList(args).contains("--benchmark")) benchmark();
    }

    private static int checkInteriorCrops(float[][] full, float[][] source, int dstH, int dstW, int scale) {
        if (source.length < 2 || source[0].length < 2) return 0;
        int firstR = firstInterior(source.length, dstH), lastR = lastInterior(source.length, dstH);
        int firstC = firstInterior(source[0].length, dstW), lastC = lastInterior(source[0].length, dstW);
        checkCrop(full, source, dstH, dstW, scale, firstR, firstC);
        checkCrop(full, source, dstH, dstW, scale, lastR, lastC);
        checkCrop(full, source, dstH, dstW, scale, (firstR + lastR) / 2, (firstC + lastC) / 2);
        return 3;
    }
    private static int firstInterior(int source, int destination) { for (int p = 0; p < destination; p++) if (interior(p, source, destination)) return p; throw new AssertionError(); }
    private static int lastInterior(int source, int destination) { for (int p = destination - 1; p >= 0; p--) if (interior(p, source, destination)) return p; throw new AssertionError(); }
    private static boolean interior(int point, int source, int destination) { float coordinate = ((point + .5f) * source / destination) - .5f; int floor = (int) Math.floor(coordinate); return floor >= 0 && floor + 1 < source; }
    private static void checkCrop(float[][] full, float[][] source, int dstH, int dstW, int scale, int r0, int c0) {
        int h = 1, w = 1;
        float[] flattened = flatten(source);
        float[] actual = ExactTerrainSampling.resizeCrop(flattened, 0, 0, source.length, source[0].length,
                0, 0, source.length, source[0].length, scale, r0, c0, h, w);
        bits(full[r0][c0], actual[0], "crop " + r0 + "," + c0);
        float[] packed = new float[flattened.length + 6];
        System.arraycopy(flattened, 0, packed, 3, flattened.length);
        float[] offset = ExactTerrainSampling.resizeCrop(packed, 3, 0, 0, source.length, source[0].length,
                0, 0, source.length, source[0].length, scale, r0, c0, h, w);
        bits(full[r0][c0], offset[0], "offset crop " + r0 + "," + c0);
    }

    private static float[][] frozenResize(float[][] src, int dstH, int dstW) {
        int srcH = src.length, srcW = src[0].length;
        float[][] dst = new float[dstH][dstW];
        for (int r = 0; r < dstH; r++) {
            float srcR = ((r + 0.5f) * srcH / dstH) - 0.5f;
            int r0 = (int) Math.floor(srcR); int r1 = r0 + 1;
            float wr = srcR - r0;
            r0 = Math.max(0, Math.min(srcH - 1, r0)); r1 = Math.max(0, Math.min(srcH - 1, r1));
            for (int c = 0; c < dstW; c++) {
                float srcC = ((c + 0.5f) * srcW / dstW) - 0.5f;
                int c0 = (int) Math.floor(srcC); int c1 = c0 + 1;
                float wc = srcC - c0;
                c0 = Math.max(0, Math.min(srcW - 1, c0)); c1 = Math.max(0, Math.min(srcW - 1, c1));
                dst[r][c] = (1 - wr) * (1 - wc) * src[r0][c0]
                        + (1 - wr) * wc * src[r0][c1]
                        + wr * (1 - wc) * src[r1][c0]
                        + wr * wc * src[r1][c1];
            }
        }
        return dst;
    }

    private static void benchmark() {
        benchmarkCase("full256", 256, 256, 6);
        benchmarkCase("sparse16", 16, 16, 6);
    }

    private static void benchmarkCase(String name, int cropH, int cropW, int scale) {
        int srcH = (cropH + scale - 1) / scale + 4, srcW = (cropW + scale - 1) / scale + 4;
        int dstH = srcH * scale, dstW = srcW * scale;
        int cropR = 2 * scale, cropC = 2 * scale;
        float[][][] planes = new float[5][][];
        for (int channel = 0; channel < planes.length; channel++) planes[channel] = source(srcH, srcW, 3000 + channel);
        for (int n = 0; n < 3; n++) { oldFive(planes, dstH, dstW, cropR, cropC, cropH, cropW); newFive(planes, dstH, dstW, cropR, cropC, cropH, cropW); }
        long[] oldTimes = new long[9], newTimes = new long[9]; long oldChecksum = 0, newChecksum = 0;
        for (int n = 0; n < oldTimes.length; n++) {
            long start = System.nanoTime(); oldChecksum ^= checksum(oldFive(planes, dstH, dstW, cropR, cropC, cropH, cropW)); oldTimes[n] = System.nanoTime() - start;
            start = System.nanoTime(); newChecksum ^= checksum(newFive(planes, dstH, dstW, cropR, cropC, cropH, cropW)); newTimes[n] = System.nanoTime() - start;
        }
        Arrays.sort(oldTimes); Arrays.sort(newTimes);
        if (oldChecksum != newChecksum) throw new AssertionError(name + " checksum");
        System.out.printf(Locale.ROOT, "%s 5-plane scale%d: old median=%.3f ms, new median=%.3f ms, checksum=%016x%n", name, scale,
                oldTimes[4] / 1_000_000d, newTimes[4] / 1_000_000d, oldChecksum);
    }

    private static float[] oldFive(float[][][] planes, int dstH, int dstW, int r0, int c0, int h, int w) {
        float[] result = new float[planes.length * h * w];
        for (int channel = 0; channel < planes.length; channel++) {
            float[][] full = frozenResize(planes[channel], dstH, dstW);
            for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) result[channel * h * w + r * w + c] = full[r0 + r][c0 + c];
        }
        return result;
    }
    private static float[] newFive(float[][][] planes, int dstH, int dstW, int r0, int c0, int h, int w) {
        float[] result = new float[planes.length * h * w];
        for (int channel = 0; channel < planes.length; channel++)
            System.arraycopy(ExactTerrainSampling.resizeCrop(flatten(planes[channel]), 0, 0, planes[channel].length, planes[channel][0].length,
                    0, 0, planes[channel].length, planes[channel][0].length, dstH / planes[channel].length, r0, c0, h, w), 0, result, channel * h * w, h * w);
        return result;
    }
    private static long checksum(float[] values) { long h = 0xcbf29ce484222325L; for (float value : values) h = (h ^ Float.floatToRawIntBits(value)) * 0x100000001b3L; return h; }
    private static float[] flatten(float[][] values) { int h = values.length, w = values[0].length; float[] result = new float[h * w]; for (int r = 0; r < h; r++) System.arraycopy(values[r], 0, result, r * w, w); return result; }
    private static float[][] source(int h, int w, long seed) { Random random = new Random(seed); float[][] result = new float[h][w]; for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) result[r][c] = random.nextFloat() * 2000f - 1000f; return result; }
    private static void compare(float[][] expected, float[][] actual, String where) { for (int r = 0; r < expected.length; r++) for (int c = 0; c < expected[r].length; c++) bits(expected[r][c], actual[r][c], where + " " + r + "," + c); }
    private static void bits(float expected, float actual, String where) { if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual)) throw new AssertionError(where + ": " + expected + " != " + actual); }
}
