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
    // Includes every coastal path plus the exposure window at its sea-facing seed.
    static final int CONTEXT_PAD = 288;
    private static final int HABITAT_RADIUS = 16;
    private static final int MARINE_EXPOSURE_RADIUS = 64;
    // This is a displacement of an existing coastal contour, never an
    // independent habitat mask. Its size stays far below the connected
    // lowland reach, so a coast retains its broad connected form.
    private static final int MARINE_TRANSITION_CELL = 64;
    private static final float MARINE_TRANSITION_SHIFT_BLOCKS = 6f;
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
        return build(elevation, climate, nativeI0, nativeJ0, nativeH, nativeW,
                scale, physicalPixelSize, i0, j0, height, width, 0L, false);
    }
    static BiomeClassifier.TerralithContext build(float[] elevation, float[] climate,
                                                   int nativeI0, int nativeJ0, int nativeH, int nativeW,
                                                   int scale, float physicalPixelSize, int i0, int j0,
                                                   int height, int width, long worldSeed) {
        return build(elevation, climate, nativeI0, nativeJ0, nativeH, nativeW,
                scale, physicalPixelSize, i0, j0, height, width, worldSeed, false);
    }

    /** Ground requests need the same habitat geometry, without the Terralith climate planes. */
    static ShoreContext terrain(float[] elevation, int nativeI0, int nativeJ0, int nativeH, int nativeW,
                                int scale, float physicalPixelSize, int i0, int j0, int height, int width) {
        return build(elevation, null, nativeI0, nativeJ0, nativeH, nativeW,
                scale, physicalPixelSize, i0, j0, height, width, 0L, true).shore();
    }
    static ShoreContext terrain(float[] elevation, int nativeI0, int nativeJ0, int nativeH, int nativeW,
                                int scale, float physicalPixelSize, int i0, int j0, int height, int width,
                                long worldSeed) {
        return build(elevation, null, nativeI0, nativeJ0, nativeH, nativeW,
                scale, physicalPixelSize, i0, j0, height, width, worldSeed, true).shore();
    }

    private static BiomeClassifier.TerralithContext build(float[] elevation, float[] climate,
                                                   int nativeI0, int nativeJ0, int nativeH, int nativeW,
                                                   int scale, float physicalPixelSize, int i0, int j0,
                                                   int height, int width, long worldSeed, boolean terrainOnly) {
        if (!terrainOnly && (climate == null || climate.length < 4 * nativeH * nativeW))
            throw new IllegalArgumentException("macro context needs four climate channels");
        int fieldI0 = i0 - CONTEXT_PAD, fieldJ0 = j0 - CONTEXT_PAD;
        int fieldH = height + 2 * CONTEXT_PAD, fieldW = width + 2 * CONTEXT_PAD;
        float[] smooth = new float[fieldH * fieldW];
        for (int r = 0; r < fieldH; r++) for (int c = 0; c < fieldW; c++)
            smooth[r * fieldW + c] = sampleWorld(elevation, 0, nativeI0, nativeJ0, nativeH, nativeW,
                    scale, fieldI0 + r, fieldJ0 + c);

        int[] oceanDistance = coastDistance(smooth, fieldH, fieldW, height, width);
        int[] waterArea = waterArea(smooth, fieldH, fieldW);
        int[] marineDistance = marineDistances(smooth, waterArea, fieldH, fieldW, physicalPixelSize);
        // Habitat follows a valley floor, not individual block-scale bumps.
        // Keep this field separate: coast distance, climate anchors and terrain
        // continue to use the original samples. The global halo keeps tile overlaps exact.
        float[] habitat = habitatHeights(smooth, fieldH, fieldW);
        int n = height * width;
        float[] e = new float[n], slope = new float[n], temp = new float[n], precip = new float[n], cv = new float[n];
        boolean[] coast = new boolean[n];
        float[] distance = new float[n], open = new float[n], relief = new float[n], valley = new float[n];
        float[] marine = new float[n];
        AnchorTable anchors = terrainOnly ? null : new AnchorTable(i0, j0, height, width);
        for (int r = 0; r < height; r++) for (int c = 0; c < width; c++) {
            int worldI = i0 + r, worldJ = j0 + c;
            int out = r * width + c;
            if (!terrainOnly) {
                Anchor anchor = anchors.nearest(worldI, worldJ);
                int anchorI = anchor.i, anchorJ = anchor.j;
                int fr = anchorI - fieldI0, fc = anchorJ - fieldJ0;
                anchor.sample(smooth, fieldW, fr, fc, physicalPixelSize, climate,
                        nativeI0, nativeJ0, nativeH, nativeW, scale);
                e[out] = anchor.e;
                slope[out] = anchor.slope;
                temp[out] = anchor.temp;
                precip[out] = anchor.precip;
                cv[out] = anchor.cv;
            }
            int local = out;
            coast[out] = oceanDistance[local] <= COAST_BLOCKS * COAST_BLOCKS;
            // The bounded distance transform is exact only within the coast radius.
            distance[out] = coast[out] ? (float) Math.sqrt(oceanDistance[local]) : Float.POSITIVE_INFINITY;
            // The same global halo is used on both sides of a tile border. Opposite
            // samples cancel planar hillsides; a concave valley floor retains water.
            int center = (worldI - fieldI0) * fieldW + worldJ - fieldJ0;
            float centerHeight = habitat[center];
            float crossValley = 0;
            for (int k = 0; k < 8; k++) {
                float left = habitat[(worldI + RING_64_I[k] - fieldI0) * fieldW
                        + worldJ + RING_64_J[k] - fieldJ0];
                float right = habitat[(worldI + RING_64_I[k + 8] - fieldI0) * fieldW
                        + worldJ + RING_64_J[k + 8] - fieldJ0];
                crossValley = Math.max(crossValley, Math.min(left, right) - centerHeight);
            }
            float habitatSlope = habitatSlope(habitat, fieldW, center, physicalPixelSize);
            valley[out] = WetlandEcology.inlandPotential(habitatSlope, crossValley);
            marine[out] = marinePotential(habitat, fieldW, center, marineDistance,
                    physicalPixelSize);
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
        // A uniform marginal lowland must retain a uniform transition.  Where
        // the connected lowland field already has a contour, displace that
        // contour by at most six blocks with a world-addressed coherent field.
        // The local gradient is zero on a plateau, so this cannot create a
        // detached mangrove island or make an existing barrier/path eligible.
        for (int r = 0; r < height; r++) for (int c = 0; c < width; c++) {
            int out = r * width + c;
            float base = marine[out];
            if (base <= 0 || base >= 1) continue;
            int center = (r + CONTEXT_PAD) * fieldW + c + CONTEXT_PAD;
            float dx = marinePotential(habitat, fieldW, center + 1, marineDistance,
                    physicalPixelSize) - marinePotential(habitat, fieldW, center - 1,
                    marineDistance, physicalPixelSize);
            float dz = marinePotential(habitat, fieldW, center + fieldW, marineDistance,
                    physicalPixelSize) - marinePotential(habitat, fieldW, center - fieldW,
                    marineDistance, physicalPixelSize);
            float gradient = .5f * (float) Math.sqrt(dx * dx + dz * dz);
            marine[out] = displaceMarineTransition(base, gradient, worldSeed, j0 + c, i0 + r);
        }
        // Shore/climate admission uses smooth terrain.  Water containment must
        // instead see the exact post-detail density floor used at scale > 1.
        // The existing 288-block halo leaves one cell for Sobel detail and the
        // full warped wetland envelope plus its five-block dry-bank distance.
        float[] wetlandGround = scale == 1 ? smooth : ElevationDetail.interior(smooth, fieldH, fieldW,
                fieldI0, fieldJ0, physicalPixelSize, physicalPixelSize * scale);
        int wetlandPad = scale == 1 ? CONTEXT_PAD : CONTEXT_PAD - 1;
        int wetlandH = scale == 1 ? fieldH : fieldH - 2;
        int wetlandW = scale == 1 ? fieldW : fieldW - 2;
        return new BiomeClassifier.TerralithContext(e, slope, temp, precip, cv, coast,
                new ShoreContext(distance, open, relief, valley, marine,
                        WetlandTerrain.build(wetlandGround, wetlandH, wetlandW, physicalPixelSize,
                                wetlandPad, height, width, worldSeed, j0, i0)));
    }

    private static float marinePotential(float[] habitat, int width, int center,
                                         int[] marineDistance, float physicalPixelSize) {
        if (marineDistance[center] > WetlandEcology.MARINE_REACH_BLOCKS * 10) return 0;
        return WetlandEcology.marineLowlandPotential(habitat[center] / physicalPixelSize,
                habitatSlope(habitat, width, center, physicalPixelSize), marineDistance[center] / 10f);
    }

    private static float habitatSlope(float[] habitat, int width, int center, float physicalPixelSize) {
        float dx = (habitat[center + HABITAT_RADIUS] - habitat[center - HABITAT_RADIUS])
                / (2 * HABITAT_RADIUS * physicalPixelSize);
        float dz = (habitat[center + HABITAT_RADIUS * width] - habitat[center - HABITAT_RADIUS * width])
                / (2 * HABITAT_RADIUS * physicalPixelSize);
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    static float displaceMarineTransition(float base, float gradient, long seed, int worldJ, int worldI) {
        if (gradient <= 0) return base;
        float envelope = EcologyClassifier.smooth(.05f, .30f, base)
                * (1 - EcologyClassifier.smooth(.70f, .95f, base));
        if (envelope <= 0) return base;
        float contourShift = (coherent(seed ^ 0x4D4152494E455F43L, worldJ, worldI,
                MARINE_TRANSITION_CELL) * 2 - 1) * MARINE_TRANSITION_SHIFT_BLOCKS;
        return Math.max(0, Math.min(1, base + contourShift * gradient * envelope));
    }

    private static float coherent(long seed, int x, int z, int cell) {
        int x0 = Math.floorDiv(x, cell), z0 = Math.floorDiv(z, cell);
        float fx = (x - (long) x0 * cell) / (float) cell;
        float fz = (z - (long) z0 * cell) / (float) cell;
        fx = fx * fx * (3 - 2 * fx); fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz); fz = fz * fz * (3 - 2 * fz);
        float a = unit(seed, x0, z0), b = unit(seed, x0 + 1, z0);
        float c = unit(seed, x0, z0 + 1), d = unit(seed, x0 + 1, z0 + 1);
        return a + (b - a) * fx + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    private static float unit(long seed, int x, int z) {
        int seedBits = (int) (seed ^ seed >>> 32);
        return Integer.toUnsignedLong(hash(x ^ seedBits,
                z + Integer.rotateLeft(seedBits, 13))) * 0x1.0p-32f;
    }

    /** Bounded lowland paths using only this immutable terrain halo, never generated river water.
     * Seed land beside negative-elevation water; keep the sea out of the queue. Diagonal paths
     * cannot cut across raised corners. Exposure is checked at the coast, not again inland:
     * otherwise the exposure window itself truncates an otherwise connected lowland.
     * CONTEXT_PAD covers the whole bounded path and its coastal seed's exposure window.
     */
    private static int[] marineDistances(float[] elevation, int[] waterArea, int h, int w,
                                         float physicalPixelSize) {
        int limit = WetlandEcology.MARINE_REACH_BLOCKS * 10;
        float ceiling = WetlandEcology.MARINE_MAX_ELEVATION_BLOCKS * physicalPixelSize;
        int[] distance = new int[elevation.length];
        java.util.Arrays.fill(distance, limit + 1);
        java.util.PriorityQueue<MarineStep> queue = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingInt(MarineStep::distance));
        for (int r = MARINE_EXPOSURE_RADIUS; r < h - MARINE_EXPOSURE_RADIUS; r++)
            for (int c = MARINE_EXPOSURE_RADIUS; c < w - MARINE_EXPOSURE_RADIUS; c++) {
            int index = r * w + c;
            if (!marineLand(elevation[index], ceiling)) continue;
            int best = limit + 1;
            for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                if (!(elevation[index + dr * w + dc] < 0)) continue;
                if (dr != 0 && dc != 0 && (!marinePassable(elevation[index + dr * w], ceiling)
                        || !marinePassable(elevation[index + dc], ceiling))) continue;
                best = Math.min(best, dr == 0 || dc == 0 ? 10 : 14);
            }
            float exposure = best <= limit ? coastalWaterFraction(waterArea, w, r, c) : 0;
            if (best <= limit && exposure >= .125f && exposure <= .85f) {
                distance[index] = best;
                queue.add(new MarineStep(index, best));
            }
        }
        while (!queue.isEmpty()) {
            MarineStep current = queue.remove();
            if (current.distance != distance[current.index]) continue;
            int r = current.index / w, c = current.index % w;
            for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                if ((dr == 0 && dc == 0) || r + dr < 0 || r + dr >= h || c + dc < 0 || c + dc >= w) continue;
                int neighbor = current.index + dr * w + dc;
                int next = current.distance + (dr == 0 || dc == 0 ? 10 : 14);
                if (next > limit || next >= distance[neighbor] || !marineLand(elevation[neighbor], ceiling)) continue;
                if (dr != 0 && dc != 0 && (!marinePassable(elevation[current.index + dr * w], ceiling)
                        || !marinePassable(elevation[current.index + dc], ceiling))) continue;
                distance[neighbor] = next;
                queue.add(new MarineStep(neighbor, next));
            }
        }
        return distance;
    }

    private record MarineStep(int index, int distance) { }

    private static boolean marinePassable(float elevation, float ceiling) {
        return Float.isFinite(elevation) && elevation <= ceiling;
    }

    private static boolean marineLand(float elevation, float ceiling) {
        return elevation >= 0 && marinePassable(elevation, ceiling);
    }

    private static float coastalWaterFraction(int[] area, int width, int row, int col) {
        int r0 = row - MARINE_EXPOSURE_RADIUS, r1 = row + MARINE_EXPOSURE_RADIUS + 1;
        int c0 = col - MARINE_EXPOSURE_RADIUS, c1 = col + MARINE_EXPOSURE_RADIUS + 1;
        int stride = width + 1;
        int water = area[r1 * stride + c1] - area[r0 * stride + c1]
                - area[r1 * stride + c0] + area[r0 * stride + c0];
        return water / (float) ((r1 - r0) * (c1 - c0));
    }

    /** Area exposure varies gradually as a coastline moves across the window, unlike 16 ray votes. */
    private static int[] waterArea(float[] elevation, int h, int w) {
        int stride = w + 1;
        int[] area = new int[(h + 1) * stride];
        for (int r = 0; r < h; r++) {
            int row = 0;
            for (int c = 0; c < w; c++) {
                if (elevation[r * w + c] < 0) row++;
                area[(r + 1) * stride + c + 1] = area[r * stride + c + 1] + row;
            }
        }
        return area;
    }

    /** Separable box mean; only interior values inside the existing global halo are used. */
    static float[] habitatHeights(float[] source, int height, int width) {
        int radius = HABITAT_RADIUS, diameter = 2 * radius + 1;
        float[] rows = new float[source.length], result = new float[source.length];
        for (int r = 0; r < height; r++) {
            double sum = 0;
            for (int c = -radius; c <= radius; c++) sum += source[r * width + Math.max(0, Math.min(width - 1, c))];
            for (int c = 0; c < width; c++) {
                rows[r * width + c] = (float) (sum / diameter);
                sum += (double) source[r * width + Math.min(width - 1, c + radius + 1)]
                        - source[r * width + Math.max(0, c - radius)];
            }
        }
        for (int c = 0; c < width; c++) {
            double sum = 0;
            for (int r = -radius; r <= radius; r++) sum += rows[Math.max(0, Math.min(height - 1, r)) * width + c];
            for (int r = 0; r < height; r++) {
                result[r * width + c] = (float) (sum / diameter);
                sum += (double) rows[Math.min(height - 1, r + radius + 1) * width + c]
                        - rows[Math.max(0, r - radius) * width + c];
            }
        }
        return result;
    }

    /** Per-build geometry and derived macro samples.  This deliberately has no
     * cache outside build: the smooth field and native halo are build-local. */
    private static final class AnchorTable {
        private final int cellI0, cellJ0, rows, columns;
        private final Anchor[] values;

        AnchorTable(int i0, int j0, int height, int width) {
            cellI0 = Math.floorDiv(i0, REGION_BLOCKS) - 1;
            cellJ0 = Math.floorDiv(j0, REGION_BLOCKS) - 1;
            int cellI1 = Math.floorDiv(i0 + height - 1, REGION_BLOCKS) + 1;
            int cellJ1 = Math.floorDiv(j0 + width - 1, REGION_BLOCKS) + 1;
            rows = cellI1 - cellI0 + 1;
            columns = cellJ1 - cellJ0 + 1;
            values = new Anchor[rows * columns];
            for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
                int ri = cellI0 + r, rj = cellJ0 + c, hash = hash(ri, rj);
                values[r * columns + c] = new Anchor(
                        ri * REGION_BLOCKS + REGION_BLOCKS / 2
                                + Math.floorMod(hash, 2 * ANCHOR_JITTER + 1) - ANCHOR_JITTER,
                        rj * REGION_BLOCKS + REGION_BLOCKS / 2
                                + Math.floorMod(Integer.rotateLeft(hash, 15), 2 * ANCHOR_JITTER + 1) - ANCHOR_JITTER);
            }
        }

        Anchor nearest(int i, int j) {
            int cellI = Math.floorDiv(i, REGION_BLOCKS), cellJ = Math.floorDiv(j, REGION_BLOCKS);
            long best = Long.MAX_VALUE;
            Anchor selected = null;
            // Keep the legacy di/dj traversal and strict tie rule exactly.
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
                Anchor candidate = values[(cellI + di - cellI0) * columns + cellJ + dj - cellJ0];
                long dI = (long) i - candidate.i, dJ = (long) j - candidate.j;
                long squared = dI * dI + dJ * dJ;
                if (squared < best) { best = squared; selected = candidate; }
            }
            return selected;
        }
    }

    private static final class Anchor {
        final int i, j;
        boolean sampled;
        float e, slope, temp, precip, cv;

        Anchor(int i, int j) { this.i = i; this.j = j; }

        void sample(float[] smooth, int fieldW, int fr, int fc, float physicalPixelSize,
                    float[] climate, int nativeI0, int nativeJ0, int nativeH, int nativeW, int scale) {
            if (sampled) return;
            e = smooth[fr * fieldW + fc];
            float dx = smooth[fr * fieldW + fc + 1] - smooth[fr * fieldW + fc - 1];
            float dy = smooth[(fr + 1) * fieldW + fc] - smooth[(fr - 1) * fieldW + fc];
            slope = (float) Math.sqrt(dx * dx + dy * dy) / (2f * physicalPixelSize);
            temp = BiomeClassifier.terralithTemperature(sampleChannel(climate, 0, nativeI0, nativeJ0, nativeH, nativeW, scale, i, j), i, j);
            precip = BiomeClassifier.terralithPrecipitation(sampleChannel(climate, 2, nativeI0, nativeJ0, nativeH, nativeW, scale, i, j), i, j);
            cv = sampleChannel(climate, 3, nativeI0, nativeJ0, nativeH, nativeW, scale, i, j);
            sampled = true;
        }
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
    private static int[] coastDistance(float[] elevation, int h, int w, int outH, int outW) {
        int[] horizontal = new int[h * w], result = new int[outH * outW];
        final int inf = 1_000_000;
        for (int r = 0; r < h; r++) {
            int left = inf; for (int c = 0; c < w; c++) { if (elevation[r*w+c] < 0) left = 0; else if (left < inf) left++; horizontal[r*w+c] = left; }
            int right = inf; for (int c = w - 1; c >= 0; c--) { if (elevation[r*w+c] < 0) right = 0; else if (right < inf) right++; horizontal[r*w+c] = Math.min(horizontal[r*w+c], right); }
        }
        // Only requested cells consume distances; the halo supplies neighbors, not outputs.
        for (int r = 0; r < outH; r++) for (int c = 0; c < outW; c++) {
            int best = inf;
            for (int dy = -COAST_BLOCKS; dy <= COAST_BLOCKS; dy++) {
                int rr = r + CONTEXT_PAD + dy;
                if (rr >= 0 && rr < h) {
                    int dx = horizontal[rr*w+c+CONTEXT_PAD];
                    if (dx < inf) best = Math.min(best, dx * dx + dy * dy);
                }
            }
            result[r*outW+c] = best;
        }
        return result;
    }
}
