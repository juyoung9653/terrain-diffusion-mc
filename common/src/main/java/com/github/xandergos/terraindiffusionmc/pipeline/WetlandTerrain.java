package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure shape policy for shallow wetland water.  It deliberately has no chunk,
 * feature, or later-river dependency: callers may calculate it while preparing
 * density columns and use the returned water level later during surface work.
 */
public final class WetlandTerrain {
    private static final int SEA_LEVEL = 63;
    private static final int INNER_RADIUS = 96;
    private static final int PLANE_RADIUS = 128;
    private static final int OUTER_RADIUS = 160;
    private static final int BANK_RADIUS = 5;
    private static final int BOUNDARY_WARP = 8;
    private static final int BOUNDARY_WARP_CELL = 48;
    private static final int MARGIN_RADIUS_MIN = 8, MARGIN_RADIUS_MAX = 16;
    private static final int MARGIN_RADIUS_CELL = 16;
    private static final int DRAINAGE_RADIUS = 24;
    private static final int[] DRAINAGE_X = {24, 22, 17, 9, 0, -9, -17, -22,
            -24, -22, -17, -9, 0, 9, 17, 22};
    private static final int[] DRAINAGE_Z = {0, 9, 17, 22, 24, 22, 17, 9,
            0, -9, -17, -22, -24, -22, -17, -9};
    // An owner-plane margin is at most sixteen columns.  Higher dry banks
    // retain the separate five-block cap below, never a general flattening rule.
    private static final int MAX_FRESH_BANK_LOWERING = 5;
    private static final int MAX_COASTAL_BANK_LOWERING = 5;
    private static final Set<Long> TRACE_POSITIONS = tracePositions();
    private static final Set<Long> TRACED = ConcurrentHashMap.newKeySet();

    public record Column(int terrainMeters, int waterLevel, boolean dryHummock) {
        public boolean flooded() { return waterLevel != 0; }
    }

    /** Per-output-column basin planes calculated from one padded, globally addressed field. */
    public static final class Context {
        private final int width, height;
        private final int[] plane;
        private final float[] confidence;
        private final float[] drainage;
        private final float[] terrainSlope;
        private final byte[] bankDistance;
        private final short[] bankPlane;

        private Context(int width, int height, int[] plane, float[] confidence, float[] drainage,
                        float[] terrainSlope, byte[] bankDistance, short[] bankPlane) {
            this.width = width;
            this.height = height;
            this.plane = plane;
            this.confidence = confidence;
            this.drainage = drainage;
            this.terrainSlope = terrainSlope;
            this.bankDistance = bankDistance;
            this.bankPlane = bankPlane;
        }

        public int width() { return width; }
        public int height() { return height; }
        public int waterPlane(int x, int z) { return plane[index(x, z)]; }
        public float basinConfidence(int x, int z) { return confidence[index(x, z)]; }
        public float drainage(int x, int z) { return drainage[index(x, z)]; }
        public float terrainSlope(int x, int z) { return terrainSlope[index(x, z)]; }
        public int bankDistance(int x, int z) { return bankDistance[index(x, z)] & 0xff; }
        public int bankPlane(int x, int z) { return bankPlane[index(x, z)]; }
        private int index(int x, int z) {
            if (x < 0 || x >= width || z < 0 || z >= height)
                throw new IndexOutOfBoundsException(x + "," + z);
            return z * width + x;
        }
    }

    private WetlandTerrain() { }

    /**
     * Builds candidate water planes for the inner field at {@code pad}.  Input
     * elevation is TD pipeline metres; each sample is one game block apart.
     * The caller supplies enough halo for the 160-block window.
     */
    public static Context build(float[] paddedElevation, int fieldHeight, int fieldWidth,
                                float metresPerBlock, int pad, int height, int width) {
        return build(paddedElevation, fieldHeight, fieldWidth, metresPerBlock, pad, height, width,
                0L, 0, 0, false);
    }

    /** Uses world-anchored, bounded envelope offsets to avoid axis-aligned basin cuts. */
    public static Context build(float[] paddedElevation, int fieldHeight, int fieldWidth,
                                float metresPerBlock, int pad, int height, int width,
                                long worldSeed, int outputX, int outputZ) {
        return build(paddedElevation, fieldHeight, fieldWidth, metresPerBlock, pad, height, width,
                worldSeed, outputX, outputZ, true);
    }

    private static Context build(float[] paddedElevation, int fieldHeight, int fieldWidth,
                                 float metresPerBlock, int pad, int height, int width,
                                 long worldSeed, int outputX, int outputZ, boolean warped) {
        // The warped envelope and its owner-plane shore profile both read beyond
        // the normal outer minimum.  Require this on every output edge: a
        // one-sided halo would otherwise make a tile seam depend on the caller.
        int requiredHalo = OUTER_RADIUS + (warped ? BOUNDARY_WARP + MARGIN_RADIUS_MAX : 0);
        if (fieldHeight <= 0 || fieldWidth <= 0 || paddedElevation == null
                || paddedElevation.length != fieldHeight * fieldWidth || metresPerBlock <= 0
                || pad < requiredHalo || height <= 0 || width <= 0
                || pad + height > fieldHeight - requiredHalo
                || pad + width > fieldWidth - requiredHalo)
            throw new IllegalArgumentException("Invalid wetland terrain field");
        int[] blocks = new int[paddedElevation.length];
        for (int i = 0; i < blocks.length; i++) blocks[i] = boundary(paddedElevation[i], metresPerBlock);
        int[] inner = squareMin(blocks, fieldHeight, fieldWidth, INNER_RADIUS);
        int[] middle = squareMin(blocks, fieldHeight, fieldWidth, PLANE_RADIUS);
        int[] outer = squareMin(blocks, fieldHeight, fieldWidth, OUTER_RADIUS);
        boolean[] stable = new boolean[blocks.length];
        int margin = OUTER_RADIUS + (warped ? BOUNDARY_WARP : 0);
        for (int z = margin; z < fieldHeight - margin; z++) for (int x = margin; x < fieldWidth - margin; x++) {
            int shiftX = warped ? boundaryWarp(worldSeed, outputX + x - pad, outputZ + z - pad, 0) : 0;
            int shiftZ = warped ? boundaryWarp(worldSeed, outputX + x - pad, outputZ + z - pad, 1) : 0;
            stable[z * fieldWidth + x] = inner[(z + shiftZ) * fieldWidth + x + shiftX]
                    == outer[(z + shiftZ) * fieldWidth + x + shiftX];
        }
        int[] planes = new int[width * height];
        float[] confidence = new float[planes.length];
        float[] drainage = new float[planes.length];
        float[] terrainSlope = new float[planes.length];
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            int source = (z + pad) * fieldWidth + x + pad;
            int target = z * width + x;
            planes[target] = middle[source];
            // A changing minimum means the candidate lies on a basin edge.  Do
            // not turn that edge into a ruler-straight water boundary.
            int shiftX = warped ? boundaryWarp(worldSeed, outputX + x, outputZ + z, 0) : 0;
            int shiftZ = warped ? boundaryWarp(worldSeed, outputX + x, outputZ + z, 1) : 0;
            confidence[target] = clamp(1f - Math.abs(inner[(z + pad + shiftZ) * fieldWidth + x + pad + shiftX]
                    - outer[(z + pad + shiftZ) * fieldWidth + x + pad + shiftX]) / 2f);
            drainage[target] = localDrainage(paddedElevation, fieldWidth, x + pad, z + pad,
                    metresPerBlock);
            terrainSlope[target] = localSlope(paddedElevation, fieldWidth, x + pad, z + pad,
                    metresPerBlock);
        }
        // Only a stable local plane is a shore-core seed.  Its owner plane is
        // propagated a bounded distance so the irregular edge can use the same
        // water table, without inventing a separate adjacent basin.
        DistanceField distances = cappedBasinDistance(blocks, middle, stable, fieldHeight, fieldWidth);
        byte[] cropped = new byte[width * height];
        short[] croppedPlanes = new short[width * height];
        for (int z = 0; z < height; z++)
            { System.arraycopy(distances.distance, (z + pad) * fieldWidth + pad, cropped, z * width, width);
              System.arraycopy(distances.ownerPlane, (z + pad) * fieldWidth + pad, croppedPlanes, z * width, width); }
        return new Context(width, height, planes, confidence, drainage, terrainSlope, cropped, croppedPlanes);
    }

    /**
     * Returns a lowered density height plus the surface water level.  The result
     * never raises terrain and uses a bounded 8--16 column owner margin plus
     * a separate five-column dry-bank correction.  {@code originalBoundary}
     * is the density boundary in game Y coordinates.
     */
    public static Column shape(short originalMeters, int originalBoundary,
                               EcologyClassifier.Sample ecology, short terrainBiome,
                               int x, int z, long seed, float metresPerBlock,
                               Context basins, int localX, int localZ) {
        if (ecology == null)
            return trace(x, z, originalBoundary, 0, 0, -1, 0, 0, 0,
                    null, Short.MIN_VALUE, 0, "early-no-ecology", new Column(originalMeters, 0, false));
        if (terrainBiome != 0 || metresPerBlock <= 0)
            return trace(x, z, originalBoundary, 0, 0, -1, 0, 0, 0,
                    ecology, Short.MIN_VALUE, 0, "early-terrain-biome", new Column(originalMeters, 0, false));
        WetlandEcology.Weights weights = WetlandEcology.weights(ecology);
        float fresh = weights.reedMarsh() + weights.freshwaterSwamp();
        float marine = weights.mangrove();
        if (ecology.temperature() < -2 || ecology.moisture() < .45f)
            return trace(x, z, originalBoundary, 0, 0, -1, fresh, marine, 0,
                    ecology, Short.MIN_VALUE, 0, "early-climate", new Column(originalMeters, 0, false));
        // Fade the inland policy out before the coastal policy starts. Both
        // have zero cut strength at their hand-off, so a mixed sample cannot
        // exchange its inland plane for sea level in one column.
        fresh *= 1 - smooth(.20f, .35f, marine);
        if (marine >= .35f) return coastal(originalMeters, originalBoundary, ecology, x, z, seed,
                metresPerBlock, fresh, marine, basins, localX, localZ);
        if (basins == null)
            return trace(x, z, originalBoundary, 0, 0, -1, fresh, marine, 0,
                    ecology, Short.MIN_VALUE, 0, "early-no-basins", new Column(originalMeters, 0, false));
        int plane = basins.waterPlane(localX, localZ);
        float confidence = basins.basinConfidence(localX, localZ);
        int distance = basins.bankDistance(localX, localZ);
        int ownerPlane = basins.bankPlane(localX, localZ);
        int marginRadius = ownerMarginRadius(seed, x, z);
        // Low freshwater floodplains can share sea level without becoming an ocean
        // biome. Higher basins retain their own stable, local water table.
        if (plane < SEA_LEVEL)
            return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, 0,
                    ecology, ownerPlane, marginRadius, "early-low-plane", new Column(originalMeters, 0, false));
        float ecologyStrength = fresh * smooth(.55f, .90f, ecology.wetness())
                * smooth(.45f, .60f, ecology.moisture());
        // A local water table needs a real depression or gently drained hollow.
        // This continuous value comes from the smooth terrain field, so it
        // removes square-min terraces without replacing them with a seeded mask.
        float landform = smooth(.005f, .05f, basins.drainage(localX, localZ))
                * (1 - smooth(.10f, .32f, basins.terrainSlope(localX, localZ)));
        float freshStrength = ecologyStrength * landform;
        float edge = freshStrength * confidence;
        float hummock = smooth(.50f, .80f, coherent(seed ^ 0x48554D4F434B5F4CL, x, z, 24));
        // The owner margin must use the same continuous wetland suitability as
        // the stable core.  Confidence selects an exact local plane for the
        // core; a seam-safe owner distance already supplies that spatial proof
        // at its edge, so it must not turn a wetness epsilon into a full cut.
        float marginStrength = ecologyStrength * (1 - hummock);
        // Saturated freshwater remains the only water admission. A stable low
        // core may cross a block-scale slope threshold: landscape-scale valley
        // admission already happened upstream. The dry bank below handles its
        // outer, non-flooded margin.
        boolean saturatedFresh = freshStrength > 0;
        boolean ownerMargin = fresh > 0 && distance >= 1 && ownerPlane != Short.MIN_VALUE
                && ownerPlane == plane;
        Column margin = continuousMargin(originalMeters, originalBoundary, plane, ecology, fresh,
                distance, ownerPlane, marginRadius, marginStrength, metresPerBlock);
        if (margin != null) return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, hummock,
                ecology, ownerPlane, marginRadius,
                margin.flooded() ? "fresh-owner-water-margin" : "fresh-owner-dry-margin", margin);
        if (ownerMargin && (originalBoundary > ownerPlane + 8 || distance > marginRadius))
            return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, hummock,
                    ecology, ownerPlane, marginRadius,
                    "fresh-owner-margin-outside", new Column(originalMeters, 0, false));
        boolean stableLowCore = confidence >= .999f && originalBoundary >= plane && originalBoundary <= plane + 3;
        if (saturatedFresh && originalBoundary <= plane + 3 && confidence >= .999f
                && (ecology.slope() <= .18f || stableLowCore)) {
            Column strict = blendCut(originalMeters, originalBoundary, plane, metresPerBlock,
                    edge * (1 - hummock), hummock, seed, x, z);
            if (strict.flooded()) return trace(x, z, originalBoundary, plane, confidence, 0, fresh,
                    marine, hummock, ecology, ownerPlane, marginRadius, "fresh-strict-water", strict);
            // Coherent hummocks remain only in the low raised part of a basin.
            // A hummock flag must not retain a 4+ block wall immediately beside
            // the padded low-basin core.
            if (strict.dryHummock() && originalBoundary <= plane + 3)
                return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, hummock,
                        ecology, ownerPlane, marginRadius,
                        "fresh-low-hummock", strict);
            Column shoulder = dryBankShoulder(originalMeters, originalBoundary, plane, ecology, fresh,
                    distance, metresPerBlock);
            return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, hummock,
                    ecology, ownerPlane, marginRadius,
                    shoulder.terrainMeters() == originalMeters ? "fresh-strict-local-taper"
                            : "fresh-strict-bank-shoulder",
                    shoulder.terrainMeters() == originalMeters ? strict : shoulder);
        }
        Column shoulder = dryBankShoulder(originalMeters, originalBoundary, plane, ecology, fresh,
                distance, metresPerBlock);
        return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, hummock,
                ecology, ownerPlane, marginRadius,
                "fresh-soft-bank-shoulder", shoulder);
    }


    private static Column coastal(short originalMeters, int originalBoundary,
                                  EcologyClassifier.Sample ecology, int x, int z, long seed,
                                  float metresPerBlock, float fresh, float marine, Context basins,
                                  int localX, int localZ) {
        float hummock = smooth(.50f, .80f, coherent(seed ^ 0x4D414E47524F5645L, x, z, 24));
        int plane = basins == null ? SEA_LEVEL : basins.waterPlane(localX, localZ);
        float confidence = basins == null ? 0 : basins.basinConfidence(localX, localZ);
        int distance = basins == null ? 0 : basins.bankDistance(localX, localZ);
        int ownerPlane = basins == null ? Short.MIN_VALUE : basins.bankPlane(localX, localZ);
        int radius = ownerMarginRadius(seed, x, z);
        if (originalBoundary < SEA_LEVEL)
            return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, hummock,
                    ecology, ownerPlane, radius, "coastal-below-sea", new Column(originalMeters, 0, false));
        // The former marine/moisture/slope booleans created a full cut on an
        // epsilon.  Use the same bounded profile, but fade its strength to zero
        // at each admission edge.  Water still requires the shaped bed to fall
        // below sea level; a marginal sample therefore remains dry.
        float suitability = smooth(.35f, .65f, marine)
                * smooth(.25f, .55f, ecology.marineInfluence())
                * smooth(.45f, .65f, ecology.moisture())
                * (1 - smooth(.18f, .35f, ecology.slope()))
                * (1 - hummock);
        if (originalBoundary <= SEA_LEVEL + 8) {
            Column strict = blendCut(originalMeters, originalBoundary, SEA_LEVEL,
                    metresPerBlock, suitability, hummock, seed, x, z);
            if (strict.flooded() || strict.dryHummock())
                return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, hummock,
                        ecology, ownerPlane, radius,
                        strict.flooded() ? "coastal-water" : "coastal-hummock", strict);
            Column shoulder = dryCoastalShoulder(originalMeters, originalBoundary, ecology, marine,
                    distance, metresPerBlock);
            Column result = shoulder.terrainMeters() == originalMeters ? strict : shoulder;
            return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, hummock,
                    ecology, ownerPlane, radius,
                    result == strict ? "coastal-strict-taper" : "coastal-bank-shoulder", result);
        }
        Column result = dryCoastalShoulder(originalMeters, originalBoundary, ecology, marine, distance,
                metresPerBlock);
        return trace(x, z, originalBoundary, plane, confidence, distance, fresh, marine, hummock,
                ecology, ownerPlane, radius, "coastal-high-bank", result);
    }

    private static Column dryCoastalShoulder(short originalMeters, int originalBoundary,
                                             EcologyClassifier.Sample ecology, float marine, int distance,
                                             float metresPerBlock) {
        float compatible = smooth(.45f, .65f, marine)
                * smooth(.45f, .60f, ecology.moisture())
                * (1 - smooth(.18f, .35f, ecology.slope()));
        if (distance < 1 || distance > BANK_RADIUS || compatible <= 0)
            return new Column(originalMeters, 0, false);
        // The padded terrain field supplies a seam-safe distance from a low
        // shore basin.  This stays dry: the first ledge is above sea level.
        int target = Math.max(originalBoundary - MAX_COASTAL_BANK_LOWERING, SEA_LEVEL + distance);
        target = Math.round(originalBoundary + (target - originalBoundary) * compatible);
        if (target >= originalBoundary) return new Column(originalMeters, 0, false);
        int encoded = (int) Math.ceil((target - SEA_LEVEL) * metresPerBlock);
        encoded = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, encoded));
        return new Column((short) encoded, 0, false);
    }

    /**
     * A dry shoulder is admitted more cautiously than water.  The Context core
     * is terrain-only so it works for ground requests; this local ecology gate
     * keeps the bounded transition out of ordinary dry and steep land.
     */
    private static Column dryBankShoulder(short originalMeters, int originalBoundary, int water,
                                          EcologyClassifier.Sample ecology, float fresh,
                                          int distance, float metresPerBlock) {
        if (distance < 1 || distance > BANK_RADIUS || originalBoundary < water)
            return new Column(originalMeters, 0, false);
        float compatible = smooth(.25f, .45f, fresh)
                * smooth(.45f, .65f, ecology.wetness())
                * smooth(.45f, .60f, ecology.moisture())
                * (1 - smooth(.18f, .35f, ecology.slope()));
        if (compatible <= 0) return new Column(originalMeters, 0, false);
        // Keep the first dry edge one block above the water and relax outward.
        // The cap prevents a nearby terrain proxy from flattening raised land.
        int desired = water + distance;
        int target = Math.max(originalBoundary - MAX_FRESH_BANK_LOWERING,
                Math.min(originalBoundary, desired));
        target = Math.round(originalBoundary + (target - originalBoundary) * compatible);
        if (target >= originalBoundary) return new Column(originalMeters, 0, false);
        int encoded = target >= SEA_LEVEL ? (int) Math.ceil((target - SEA_LEVEL) * metresPerBlock)
                : -1;
        encoded = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, encoded));
        return new Column((short) encoded, 0, false);
    }

    /**
     * Shapes the whole shallow freshwater margin from the adjacent stable
     * basin's plane.  Unlike a fixed {@code water + distance} stair, the radius
     * and smooth interpolation are world-anchored and bounded.  This admits
     * a shallow, connected inner margin when its shaped bed is below the
     * owner plane.  A shaped bed exactly at the plane is a dry mud flat.
     */
    private static Column continuousMargin(short originalMeters, int originalBoundary, int localPlane,
                                           EcologyClassifier.Sample ecology, float fresh,
                                           int distance, int ownerPlane, int radius,
                                           float strength,
                                           float metresPerBlock) {
        if (distance < 1 || ownerPlane == Short.MIN_VALUE || ownerPlane != localPlane
                || fresh <= 0 || ecology.moisture() < .45f
                || originalBoundary > ownerPlane + 8)
            return null;
        if (distance > radius) return null;
        float t = smooth(0, radius, distance);
        boolean shallow = originalBoundary <= ownerPlane + 3;
        int start = shallow ? ownerPlane - 1 : ownerPlane;
        // Preserve the fractional slope until suitability is applied. Rounding
        // twice gave neighbouring columns of the same raw height a two-block
        // step as both distance and suitability crossed a rounding threshold.
        float profileTarget = start + (originalBoundary - start) * t;
        profileTarget = Math.max(originalBoundary - MAX_FRESH_BANK_LOWERING,
                Math.min(originalBoundary, profileTarget));
        if (!shallow) profileTarget = Math.max(ownerPlane, profileTarget);
        int target = Math.round(originalBoundary + (profileTarget - originalBoundary) * clamp(strength));
        target = Math.max(originalBoundary - MAX_FRESH_BANK_LOWERING,
                Math.min(originalBoundary, target));
        boolean submerged = shallow && target < ownerPlane;
        int encoded = target >= SEA_LEVEL ? (int) Math.ceil((target - SEA_LEVEL) * metresPerBlock) : -1;
        encoded = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, encoded));
        return new Column((short) encoded, submerged ? ownerPlane : 0, false);
    }

    private static int ownerMarginRadius(long seed, int x, int z) {
        return MARGIN_RADIUS_MIN + Math.round(coherent(seed ^ 0x4D415247494E5F52L,
                x, z, MARGIN_RADIUS_CELL) * (MARGIN_RADIUS_MAX - MARGIN_RADIUS_MIN));
    }

    /** Rounded continuous weights form a slope before the final submerged bed. */
    private static Column blendCut(short originalMeters, int originalBoundary, int water,
                                   float metresPerBlock, float suitability, float hummock,
                                   long seed, int x, int z) {
        int aboveWater = originalBoundary - water;
        if (aboveWater < 0 || aboveWater > 8)
            return new Column(originalMeters, 0, false);
        // Preserve the old submerged d=0..3 core (1,2,3,4 blocks).  The dry
        // shoulder then relaxes 4,3,2,1,0 instead of dropping straight from a
        // four-block cut to untouched terrain at d=4.
        int permitted = switch (aboveWater) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 3;
            case 3, 4 -> 4;
            case 5 -> 3;
            case 6 -> 2;
            case 7 -> 1;
            default -> 0;
        };
        int cut = Math.max(0, Math.min(permitted, Math.round(permitted * clamp(suitability))));
        int target = originalBoundary - cut;
        // A water table is flat, but its natural bed is not.  Keep the existing
        // one-block admission at weak edges, then let only a strong core gain a
        // bounded 1..3 block bed whose coherent variation cannot make a cliff.
        // The total lowering remains within the old four-block maximum.
        if (target < water) {
            int maximumDepth = Math.min(3, 4 - aboveWater);
            float core = smooth(.60f, .85f, suitability);
            float relief = .35f + .65f * coherent(seed ^ 0x5745545F4245444CL, x, z, 48);
            int depth = 1 + (int) Math.floor(2 * core * relief);
            depth = Math.max(1, Math.min(maximumDepth, depth));
            target = Math.min(target, water - depth);
        }
        if (cut == 0) return new Column(originalMeters, 0, hummock >= .999f);
        // Positive TD metres map linearly from sea level.  A sea-level wetland
        // bed uses the smallest negative value that remains below the vanilla
        // fluid level without profile dependence.
        int encoded = encodeShallowBoundary(target, metresPerBlock);
        encoded = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, encoded));
        return new Column((short) encoded, target < water ? water : 0, hummock >= .999f);
    }

    /**
     * Profile 1 and profile 2 deliberately share this legacy shallow-ocean
     * curve through the first 200 model metres.  Wetland cuts never ask for a
     * deeper boundary, so this keeps sea-level shelves profile-neutral.
     */
    private static int encodeShallowBoundary(int target, float metresPerBlock) {
        if (target >= SEA_LEVEL) return (int) Math.ceil((target - SEA_LEVEL) * metresPerBlock);
        for (int meters = -1; meters >= -200; meters--) {
            int boundary = 62 + (int) (-Math.sqrt(-meters + 10) + Math.sqrt(10));
            if (boundary <= target) return meters;
        }
        throw new IllegalArgumentException("Wetland shallow target below shared profile range: " + target);
    }

    private static int boundary(float metres, float metresPerBlock) {
        // HeightmapData truncates to integer metres before HeightConverter runs.
        // Matching that order matters at scales whose metres-per-block is fractional.
        return metres >= 0 ? SEA_LEVEL + (int) (Math.floor(metres) / metresPerBlock) : SEA_LEVEL - 1;
    }

    /**
     * Measures a smooth model depression against a circular drainage ring.  It
     * deliberately uses the unquantized elevation field: block-height rounding
     * must not turn a level square-min terrace into a wetland basin.
     */
    private static float localDrainage(float[] elevation, int width, int x, int z,
                                       float metresPerBlock) {
        float ring = 0;
        for (int i = 0; i < DRAINAGE_X.length; i++)
            ring += elevation[(z + DRAINAGE_Z[i]) * width + x + DRAINAGE_X[i]];
        return (ring / DRAINAGE_X.length - elevation[z * width + x]) / metresPerBlock;
    }

    /** A symmetric central difference keeps an inclined terrace out of a pool. */
    private static float localSlope(float[] elevation, int width, int x, int z,
                                    float metresPerBlock) {
        int radius = 6;
        float dx = elevation[z * width + x + radius] - elevation[z * width + x - radius];
        float dz = elevation[(z + radius) * width + x] - elevation[(z - radius) * width + x];
        return (float) Math.sqrt(dx * dx + dz * dz) / (2 * radius * metresPerBlock);
    }

    private static int[] squareMin(int[] values, int h, int w, int radius) {
        int[] rows = new int[values.length];
        int[] queue = new int[w];
        for (int z = 0; z < h; z++) {
            int head = 0, tail = 0;
            for (int x = 0; x < w; x++) {
                while (head < tail && queue[head] < x - 2 * radius) head++;
                while (head < tail && values[z * w + queue[tail - 1]] >= values[z * w + x]) tail--;
                queue[tail++] = x;
                int at = x - radius;
                if (at >= 0) rows[z * w + at] = values[z * w + queue[head]];
            }
            for (int x = w; x < w + radius; x++) {
                while (head < tail && queue[head] < x - 2 * radius) head++;
                int at = x - radius;
                if (at < w) rows[z * w + at] = values[z * w + queue[head]];
            }
        }
        int[] result = new int[values.length];
        queue = new int[h];
        for (int x = 0; x < w; x++) {
            int head = 0, tail = 0;
            for (int z = 0; z < h; z++) {
                while (head < tail && queue[head] < z - 2 * radius) head++;
                while (head < tail && rows[queue[tail - 1] * w + x] >= rows[z * w + x]) tail--;
                queue[tail++] = z;
                int at = z - radius;
                if (at >= 0) result[at * w + x] = rows[queue[head] * w + x];
            }
            for (int z = h; z < h + radius; z++) {
                while (head < tail && queue[head] < z - 2 * radius) head++;
                int at = z - radius;
                if (at < h) result[at * w + x] = rows[queue[head] * w + x];
            }
        }
        return result;
    }

    /** A bounded multi-source distance field with a defensive owner-plane tie. */
    private static final class DistanceField {
        final byte[] distance;
        final short[] ownerPlane;
        DistanceField(byte[] distance, short[] ownerPlane) {
            this.distance = distance;
            this.ownerPlane = ownerPlane;
        }
    }
    private static DistanceField cappedBasinDistance(int[] blocks, int[] plane, boolean[] stable,
                                              int h, int w) {
        byte[] result = new byte[blocks.length];
        short[] owner = new short[blocks.length];
        int far = MARGIN_RADIUS_MAX + 1;
        java.util.Arrays.fill(result, (byte) far);
        int[] queue = new int[blocks.length];
        int head = 0, tail = 0;
        for (int i = 0; i < result.length; i++) {
            if (blocks[i] <= plane[i] + 3 && stable[i]) {
                result[i] = 0;
                owner[i] = (short) plane[i];
                queue[tail++] = i;
            }
        }
        int[] dx = {-1, 1, 0, 0}, dz = {0, 0, -1, 1};
        while (head < tail) {
            int at = queue[head++], distance = result[at] & 255;
            if (distance >= MARGIN_RADIUS_MAX) continue;
            int x = at % w, z = at / w;
            for (int k = 0; k < 4; k++) {
                int nx = x + dx[k], nz = z + dz[k];
                if (nx < 0 || nx >= w || nz < 0 || nz >= h) continue;
                int next = nz * w + nx, nextDistance = distance + 1;
                if ((result[next] & 255) > nextDistance) {
                    result[next] = (byte) nextDistance;
                    owner[next] = owner[at];
                    queue[tail++] = next;
                } else if ((result[next] & 255) == nextDistance && owner[next] != owner[at]) {
                    // Equal paths from distinct planes are intentionally dry.
                    owner[next] = Short.MIN_VALUE;
                }
            }
        }
        return new DistanceField(result, owner);
    }

    /**
     * Opt-in native diagnosis for a handful of disputed shoreline columns.
     * Example JVM property: {@code -Dtd.wetland.shapeTracePositions=-662,-542;-662,-543}.
     * The property is read once at class load and has no allocation or logging cost when absent.
     */
    private static Set<Long> tracePositions() {
        // shapeTracePositions is deliberately separate from the capture
        // harness's terrainTracePositions: it records only actual shaping and
        // must not trigger a provider warm-up query that changes cache order.
        String configured = System.getProperty("td.wetland.shapeTracePositions", "").trim();
        if (configured.isEmpty())
            configured = System.getProperty("td.wetland.tracePositions", "").trim();
        if (configured.isEmpty()) return Set.of();
        Set<Long> positions = new HashSet<>();
        for (String entry : configured.split(";")) {
            String[] pair = entry.trim().split(",");
            if (pair.length != 2 || positions.size() >= 8) continue;
            try {
                positions.add(positionKey(Integer.parseInt(pair[0].trim()), Integer.parseInt(pair[1].trim())));
            } catch (NumberFormatException ignored) { }
        }
        return Set.copyOf(positions);
    }

    private static long positionKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static Column trace(int x, int z, int originalBoundary, int plane, float confidence,
                                int distance, float fresh, float marine, float hummock,
                                EcologyClassifier.Sample ecology, int ownerPlane, int marginRadius,
                                String branch, Column result) {
        long key = positionKey(x, z);
        if (TRACE_POSITIONS.contains(key) && TRACED.add(key)) {
            float wetness = ecology == null ? Float.NaN : ecology.wetness();
            float slope = ecology == null ? Float.NaN : ecology.slope();
            float moisture = ecology == null ? Float.NaN : ecology.moisture();
            float marineInfluence = ecology == null ? Float.NaN : ecology.marineInfluence();
            System.err.printf("TD wetland trace x=%d z=%d branch=%s rawBoundary=%d plane=%d confidence=%s "
                            + "distance=%d ownerPlane=%d marginRadius=%d fresh=%s marine=%s hummock=%s "
                            + "wetness=%s slope=%s moisture=%s marineInfluence=%s resultMeters=%d water=%d dryHummock=%s%n",
                    x, z, branch, originalBoundary, plane, confidence, distance, ownerPlane, marginRadius,
                    fresh, marine, hummock, wetness, slope, moisture, marineInfluence,
                    result.terrainMeters(), result.waterLevel(), result.dryHummock());
        }
        return result;
    }

    private static float coherent(long seed, int x, int z, int cell) {
        int x0 = Math.floorDiv(x, cell), z0 = Math.floorDiv(z, cell);
        float fx = (x - (long) x0 * cell) / (float) cell, fz = (z - (long) z0 * cell) / (float) cell;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        float a = unit(seed, x0, z0), b = unit(seed, x0 + 1, z0);
        float c = unit(seed, x0, z0 + 1), d = unit(seed, x0 + 1, z0 + 1);
        return a + (b - a) * fx + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }
    private static int boundaryWarp(long seed, int x, int z, int axis) {
        long salt = axis == 0 ? 0x5745545F57415250L : 0x42414E4B5F574152L;
        return Math.round((coherent(seed ^ salt, x, z, BOUNDARY_WARP_CELL) * 2f - 1f) * BOUNDARY_WARP);
    }

    private static float unit(long seed, int x, int z) {
        long v = seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL);
        v ^= v >>> 30; v *= 0xBF58476D1CE4E5B9L; v ^= v >>> 27; v *= 0x94D049BB133111EBL;
        return ((v ^ v >>> 31) >>> 11) * 0x1.0p-53f;
    }

    private static float smooth(float low, float high, float value) {
        if (value <= low) return 0; if (value >= high) return 1;
        float t = (value - low) / (high - low); return t * t * (3 - 2 * t);
    }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
