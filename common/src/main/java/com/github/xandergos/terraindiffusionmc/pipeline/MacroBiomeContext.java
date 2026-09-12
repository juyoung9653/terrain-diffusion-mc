package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Builds the Terralith-only context from one globally addressed smooth native
 * terrain read.  It deliberately has no pipeline dependency, which keeps its
 * overlap behaviour numeric and testable.
 */
final class MacroBiomeContext {
    static final int REGION_BLOCKS = 128;
    static final int COAST_BLOCKS = 24;
    private static final int ANCHOR_JITTER = 32;
    private static final int CONTEXT_PAD = 192;
    private static final int[] RING_64_I = new int[16], RING_64_J = new int[16], RING_96_I = new int[16], RING_96_J = new int[16];
    static {
        for (int k = 0; k < 16; k++) {
            double angle = Math.PI * 2d * k / 16d;
            RING_64_I[k] = (int) Math.round(Math.sin(angle) * 64d);
            RING_64_J[k] = (int) Math.round(Math.cos(angle) * 64d);
            RING_96_I[k] = (int) Math.round(Math.sin(angle) * 96d);
            RING_96_J[k] = (int) Math.round(Math.cos(angle) * 96d);
        }
    }

    private MacroBiomeContext() { }

    static BiomeClassifier.TerralithContext build(float[] elevation, float[] climate,
                                                   int nativeI0, int nativeJ0, int nativeH, int nativeW,
                                                   int scale, float physicalPixelSize, int i0, int j0, int height, int width) {
        if (climate == null || climate.length < 4 * nativeH * nativeW)
            throw new IllegalArgumentException("macro context needs four climate channels");
        int fieldI0 = i0 - CONTEXT_PAD, fieldJ0 = j0 - CONTEXT_PAD;
        int fieldH = height + 2 * CONTEXT_PAD, fieldW = width + 2 * CONTEXT_PAD;
        float[] smooth = new float[fieldH * fieldW];
        for (int r = 0; r < fieldH; r++) for (int c = 0; c < fieldW; c++)
            smooth[r * fieldW + c] = sampleWorld(elevation, 0, nativeI0, nativeJ0, nativeH, nativeW,
                    scale, fieldI0 + r, fieldJ0 + c);

        int[] oceanDistance = coastDistance(smooth, fieldH, fieldW);
        int n = height * width;
        float[] e = new float[n], slope = new float[n], temp = new float[n], precip = new float[n], cv = new float[n];
        boolean[] coast = new boolean[n];
        float[] distance = new float[n], open = new float[n], relief = new float[n];
        for (int r = 0; r < height; r++) for (int c = 0; c < width; c++) {
            int worldI = i0 + r, worldJ = j0 + c;
            int anchorI = anchor(worldI, worldJ, true), anchorJ = anchor(worldI, worldJ, false);
            int fr = anchorI - fieldI0, fc = anchorJ - fieldJ0, out = r * width + c;
            e[out] = smooth[fr * fieldW + fc];
            float dx = smooth[fr * fieldW + fc + 1] - smooth[fr * fieldW + fc - 1];
            float dy = smooth[(fr + 1) * fieldW + fc] - smooth[(fr - 1) * fieldW + fc];
            slope[out] = (float) Math.sqrt(dx * dx + dy * dy) / (2f * physicalPixelSize);
            temp[out] = BiomeClassifier.terralithTemperature(sampleChannel(climate, 0, nativeI0, nativeJ0, nativeH, nativeW, scale, anchorI, anchorJ), anchorI, anchorJ);
            precip[out] = BiomeClassifier.terralithPrecipitation(sampleChannel(climate, 2, nativeI0, nativeJ0, nativeH, nativeW, scale, anchorI, anchorJ), anchorI, anchorJ);
            cv[out] = sampleChannel(climate, 3, nativeI0, nativeJ0, nativeH, nativeW, scale, anchorI, anchorJ);
            int local = (r + CONTEXT_PAD) * fieldW + c + CONTEXT_PAD;
            coast[out] = oceanDistance[local] <= COAST_BLOCKS * COAST_BLOCKS;
            // The bounded distance transform is exact only within the coast radius.
            distance[out] = coast[out] ? (float) Math.sqrt(oceanDistance[local]) : Float.POSITIVE_INFINITY;
            if (!coast[out]) continue;
            int wet = 0;
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            for (int k = 0; k < 16; k++) {
                int rr = worldI + RING_96_I[k] - fieldI0, cc = worldJ + RING_96_J[k] - fieldJ0;
                float v = smooth[rr * fieldW + cc];
                if (v < 0) wet++;
                lo = Math.min(lo, v); hi = Math.max(hi, v);
                rr = worldI + RING_64_I[k] - fieldI0;
                cc = worldJ + RING_64_J[k] - fieldJ0;
                v = smooth[rr * fieldW + cc];
                lo = Math.min(lo, v); hi = Math.max(hi, v);
            }
            open[out] = wet / 16f; relief[out] = (hi - lo) / (192f * physicalPixelSize);
        }
        return new BiomeClassifier.TerralithContext(e, slope, temp, precip, cv, coast, new ShoreContext(distance, open, relief));
    }

    /** Jittered nearest regional anchor avoids a visible 128-block checkerboard. */
    private static int anchor(int i, int j, boolean row) {
        int cellI = Math.floorDiv(i, REGION_BLOCKS), cellJ = Math.floorDiv(j, REGION_BLOCKS);
        long best = Long.MAX_VALUE; int selected = 0;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            int ri = cellI + di, rj = cellJ + dj, hash = hash(ri, rj);
            int ai = ri * REGION_BLOCKS + REGION_BLOCKS / 2 + Math.floorMod(hash, 2 * ANCHOR_JITTER + 1) - ANCHOR_JITTER;
            int aj = rj * REGION_BLOCKS + REGION_BLOCKS / 2 + Math.floorMod(Integer.rotateLeft(hash, 15), 2 * ANCHOR_JITTER + 1) - ANCHOR_JITTER;
            long dI = (long) i - ai, dJ = (long) j - aj, distance = dI * dI + dJ * dJ;
            if (distance < best) { best = distance; selected = row ? ai : aj; }
        }
        return selected;
    }

    private static int hash(int i, int j) {
        int h = 0x6d2b79f5; h = Integer.rotateLeft(h * 0x9e3779b9 + i, 13);
        h = Integer.rotateLeft(h * 0x85ebca6b + j, 11); h ^= h >>> 16; h *= 0x85ebca6b; h ^= h >>> 13;
        return h ^ h >>> 16;
    }

    private static float sampleChannel(float[] data, int channel, int i0, int j0, int h, int w, int scale, int i, int j) {
        int offset = channel * h * w;
        return sampleWorld(data, offset, i0, j0, h, w, scale, i, j);
    }
    private static float sampleWorld(float[] data, int offset, int i0, int j0, int h, int w, int scale, int worldI, int worldJ) {
        double i = ((double) worldI + .5d) / scale - .5d, j = ((double) worldJ + .5d) / scale - .5d;
        double y = i - i0, x = j - j0; int y0 = (int)Math.floor(y), x0 = (int)Math.floor(x); float fy = (float)(y - y0), fx = (float)(x - x0);
        if (y0 < 0 || y0 + 1 >= h || x0 < 0 || x0 + 1 >= w)
            throw new IllegalArgumentException("macro context read lacks its required halo");
        float a = data[offset + y0 * w + x0], b = data[offset + y0 * w + x0 + 1], c = data[offset + (y0 + 1) * w + x0], d = data[offset + (y0 + 1) * w + x0 + 1];
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    /** Squared Euclidean distance to an ocean cell, with row passes instead of a 49x49 scan per cell. */
    private static int[] coastDistance(float[] elevation, int h, int w) {
        int[] horizontal = new int[h * w], result = new int[h * w];
        final int inf = 1_000_000;
        for (int r = 0; r < h; r++) {
            int left = inf; for (int c = 0; c < w; c++) { if (elevation[r*w+c] < 0) left = 0; else if (left < inf) left++; horizontal[r*w+c] = left; }
            int right = inf; for (int c = w - 1; c >= 0; c--) { if (elevation[r*w+c] < 0) right = 0; else if (right < inf) right++; horizontal[r*w+c] = Math.min(horizontal[r*w+c], right); }
        }
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int best = inf;
            for (int dy = -COAST_BLOCKS; dy <= COAST_BLOCKS; dy++) {
                int rr = r + dy;
                if (rr >= 0 && rr < h) {
                    int dx = horizontal[rr*w+c];
                    if (dx < inf) best = Math.min(best, dx * dx + dy * dy);
                }
            }
            result[r*w+c] = best;
        }
        return result;
    }
}
