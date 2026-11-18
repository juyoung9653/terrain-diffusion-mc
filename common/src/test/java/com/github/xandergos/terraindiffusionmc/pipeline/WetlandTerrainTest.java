package com.github.xandergos.terraindiffusionmc.pipeline;

/** Pure regression checks for seam-free, bounded wetland terrain shaping. */
public final class WetlandTerrainTest {
    private static final int PAD = 160, SIZE = 64;

    public static void main(String[] args) {
        checkOverlapAndScaleInvariant();
        checkDetailLegacyParity();
        checkFinalDetailPlane();
        checkInlandWaterAndHummocks();
        checkBankTaperPreservesCore();
        checkSpatialDryBankShoulder();
        checkQuantizedConfidenceBankShoulder();
        checkDryCoreIslandSlopeContinuity();
        checkWarpedStableBoundary();
        checkContinuousOwnerMargin();
        checkOwnerMarginWetnessContinuity();
        checkCoastalAdmissionContinuity();
        checkStrictWaterAdmissionAndCoastalShoulder();
        checkVariableCoreBeds();
        checkDrainageLandform();
        checkExclusionsAndCoast();
        checkFreshwaterFloodplain();
        System.out.println("WetlandTerrainTest: PASS");
    }

    private static void checkOverlapAndScaleInvariant() {
        for (int scale = 1; scale <= 6; scale++) {
            float metres = 16f / scale;
            WetlandTerrain.Context left = terrainAt(-32, -32, metres);
            WetlandTerrain.Context right = terrainAt(0, 0, metres);
            for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                int worldX = x, worldZ = z;
                int boundary = fieldBoundary(worldX, worldZ);
                var a = WetlandTerrain.shape(metres(boundary, metres), boundary, wet(), (short) 0,
                        worldX, worldZ, 42L, metres, left, x + 32, z + 32);
                var b = WetlandTerrain.shape(metres(boundary, metres), boundary, wet(), (short) 0,
                        worldX, worldZ, 42L, metres, right, x, z);
                require(a.equals(b), "same absolute column changed across shifted tiles scale=" + scale);
                require(left.waterPlane(x + 32, z + 32) == right.waterPlane(x, z),
                        "shifted tile changed water plane scale=" + scale);
            }
        }
    }

    private static void checkFreshwaterFloodplain() {
        WetlandTerrain.Context context = flat(63, 16);
        int water = 0;
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            var column = WetlandTerrain.shape((short) 16, 64, wet(), (short) 0,
                    17248 + x, 3120 + z, -2049264310295890081L, 16, context, x, z);
            if (column.flooded()) {
                water++;
                int bed = profileOneBoundary(column.terrainMeters());
                require(column.waterLevel() == 63 && bed >= 60 && bed <= 62,
                        "freshwater sea-level floodplain escaped the native 1..3-block shallow bed");
            }
        }
        require(water > SIZE * SIZE / 4, "sea-level freshwater habitat stayed dry");
    }

    private static void checkInlandWaterAndHummocks() {
        WetlandTerrain.Context context = flat(88, 16);
        int water = 0, hummocks = 0;
        int[] levels = new int[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            var column = WetlandTerrain.shape((short) 400, 88, wet(), (short) 0,
                    x, z, 991L, 16, context, x, z);
            levels[z * SIZE + x] = column.waterLevel();
            if (column.flooded()) water++;
            if (column.dryHummock()) hummocks++;
            require(column.terrainMeters() <= 400, "inland column was raised");
        }
        require(water > SIZE * SIZE / 3, "wet basin did not make broad shallow water");
        require(hummocks > SIZE * SIZE / 10 && hummocks < SIZE * SIZE / 2,
                "dry hummocks are not a bounded share of the wet basin: " + hummocks);
        for (int z = 0; z < SIZE - 1; z++) for (int x = 0; x < SIZE - 1; x++) {
            int here = levels[z * SIZE + x];
            if (here != 0 && levels[z * SIZE + x + 1] != 0)
                require(here == levels[z * SIZE + x + 1], "adjacent flooded columns disagree on level");
            if (here != 0 && levels[(z + 1) * SIZE + x] != 0)
                require(here == levels[(z + 1) * SIZE + x], "adjacent flooded rows disagree on level");
        }
        // A four-block basin cut must taper through one-block neighbours, not
        // make the rectangular shelves that the old pool implementation showed.
        for (int z = 0; z < SIZE - 1; z++) for (int x = 0; x < SIZE - 1; x++) {
            int here = boundary(WetlandTerrain.shape((short) 448, 91, wet(), (short) 0,
                    x, z, 991L, 16, context, x, z).terrainMeters(), 16);
            int east = boundary(WetlandTerrain.shape((short) 448, 91, wet(), (short) 0,
                    x + 1, z, 991L, 16, context, x + 1, z).terrainMeters(), 16);
            int south = boundary(WetlandTerrain.shape((short) 448, 91, wet(), (short) 0,
                    x, z + 1, 991L, 16, context, x, z + 1).terrainMeters(), 16);
            require(Math.abs(here - east) <= 1 && Math.abs(here - south) <= 1,
                    "wetland slope made a cliff");
        }
    }

    private static void checkFinalDetailPlane() {
        int pad = 161, size = 32, field = size + pad * 2;
        float[] smooth = new float[field * field];
        // Sloped terrain activates the same detail noise as scale>1 generation.
        for (int z = 0; z < field; z++) for (int x = 0; x < field; x++)
            smooth[z * field + x] = 320 + x * 22f + (float) Math.sin(z * .7) * 17f;
        float[] finalGround = ElevationDetail.interior(smooth, field, field, -pad - 1, -pad - 1,
                8, 16);
        WetlandTerrain.Context before = WetlandTerrain.build(smooth, field, field, 8, pad, size, size);
        WetlandTerrain.Context after = WetlandTerrain.build(finalGround, field - 2, field - 2, 8,
                pad - 1, size, size);
        boolean lowered = false;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            if (after.waterPlane(x, z) < before.waterPlane(x, z)) lowered = true;
            int source = (z + pad - 1) * (field - 2) + x + pad - 1;
            int actual = 63 + (int) Math.floor(finalGround[source] / 8);
            require(after.waterPlane(x, z) <= actual,
                    "final-noise water plane rose above a final terrain neighbour");
        }
        require(lowered, "detail noise did not affect the candidate water plane");
    }

    private static void checkDetailLegacyParity() {
        for (int scale = 2; scale <= 6; scale++) {
            int h = 19, w = 23;
            float[] padded = new float[(h + 2) * (w + 2)];
            for (int z = 0; z < h + 2; z++) for (int x = 0; x < w + 2; x++)
                padded[z * (w + 2) + x] = 220 + x * 13.7f - z * 9.3f
                        + (float) Math.sin((x - z) * .37) * 31f;
            float[] smooth = new float[h * w];
            for (int z = 0; z < h; z++) System.arraycopy(padded, (z + 1) * (w + 2) + 1,
                    smooth, z * w, w);
            float metres = 16f / scale;
            float[] expected = legacyDetail(smooth, padded, -41, 73, h, w, metres);
            float[] actual = ElevationDetail.apply(smooth, padded, -41, 73, h, w, metres, 16);
            for (int i = 0; i < actual.length; i++)
                require(Float.floatToRawIntBits(actual[i]) == Float.floatToRawIntBits(expected[i]),
                        "detail differs from legacy arithmetic scale=" + scale + " index=" + i);
        }
    }

    /** Literal pre-extraction LocalTerrainProvider implementation. */
    private static float[] legacyDetail(float[] smooth, float[] padded, int i0, int j0,
                                        int h, int w, float metres) {
        FastNoiseLite coarse = noise(99999, 1f / 24f, 3, 2f, .5f);
        FastNoiseLite fine = noise(88888, 1f / 6f, 2, 2f, .6f);
        int[] sx = {-1,0,1, -2,0,2, -1,0,1}, sz = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = smooth.clone();
        for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
            int at = z * w + x;
            if (smooth[at] < 0) continue;
            float dx = 0, dz = 0;
            for (int k = 0; k < 9; k++) {
                float value = padded[(z + k / 3) * (w + 2) + x + k % 3];
                dx += value * sx[k]; dz += value * sz[k];
            }
            dx /= 8f; dz /= 8f;
            float slope = Math.min(1f, (float) Math.sqrt(dx * dx + dz * dz) / (40f * metres / 16f));
            slope = slope * slope * (float) Math.sqrt(slope);
            result[at] = smooth[at]
                    + coarse.GetNoise(j0 + x, i0 + z) * (100f * metres / 16f) * slope
                    + fine.GetNoise(j0 + x, i0 + z) * (70f * metres / 16f) * slope;
        }
        return result;
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

    private static void checkExclusionsAndCoast() {
        WetlandTerrain.Context context = flat(88, 16);
        require(!WetlandTerrain.shape((short) 400, 88, cold(), (short) 0, 0, 0, 1, 16,
                context, 0, 0).flooded(), "cold terrain flooded");
        require(!WetlandTerrain.shape((short) 400, 88, dry(), (short) 0, 0, 0, 1, 16,
                context, 0, 0).flooded(), "dry terrain flooded");
        require(!WetlandTerrain.shape((short) 400, 88, wet(), (short) 1, 0, 0, 1, 16,
                context, 0, 0).flooded(), "shore terrain flooded as inland wetland");
        require(!WetlandTerrain.shape((short) -1, 62, mangrove(), (short) 0, 0, 0, 1, 16,
                context, 0, 0).flooded(), "existing ocean terrain was changed");
        int field = SIZE + PAD * 2;
        float[] changing = new float[field * field];
        java.util.Arrays.fill(changing, metres(88, 16));
        changing[(PAD + SIZE / 2) * field + PAD + SIZE / 2 + 120] = metres(80, 16);
        WetlandTerrain.Context unstable = WetlandTerrain.build(changing, field, field, 16, PAD, SIZE, SIZE);
        require(unstable.basinConfidence(SIZE / 2, SIZE / 2) == 0,
                "changing outer basin did not lower confidence");
        require(!WetlandTerrain.shape((short) 400, 88, wet(), (short) 0, SIZE / 2, SIZE / 2, 1,
                16, unstable, SIZE / 2, SIZE / 2).flooded(),
                "changing local plane produced an adjacent conflicting water level");
        boolean coastal = false;
        for (int z = 0; z < 128 && !coastal; z++) for (int x = 0; x < 128; x++) {
            var result = WetlandTerrain.shape((short) 32, 65, mangrove(), (short) 0,
                    x, z, 22, 16, context, 0, 0);
            if (result.flooded()) {
                coastal = true;
                require(result.waterLevel() == 63 && result.terrainMeters() < 32,
                        "coastal lowland did not join sea level");
            }
        }
        require(coastal, "coastal mangrove never reached sea water");
    }

    private static void checkBankTaperPreservesCore() {
        WetlandTerrain.Context context = flat(88, 16);
        int pointX = -1, pointZ = -1;
        for (int z = 0; z < SIZE && pointX < 0; z++) for (int x = 0; x < SIZE; x++) {
            var core = WetlandTerrain.shape(metres(91, 16), 91, wet(), (short) 0,
                    x, z, 314159L, 16, context, x, z);
            if (core.flooded() && core.terrainMeters() == metres(87, 16)) {
                pointX = x; pointZ = z; break;
            }
        }
        require(pointX >= 0, "test field has no full-strength wetland core");
        int[] expected = {87, 87, 87, 87, 92, 93, 94, 95, 96, 97};
        for (int d = 0; d <= 9; d++) {
            int original = 88 + d;
            var shaped = WetlandTerrain.shape(metres(original, 16), original, wet(), (short) 0,
                    pointX, pointZ, 314159L, 16, context, pointX, pointZ);
            int boundary = boundary(shaped.terrainMeters(), 16);
            require(boundary == expected[d], "unexpected taper at d=" + d);
            if (d <= 3) {
                require(shaped.flooded() && shaped.waterLevel() == 88,
                        "old flooded core changed at d=" + d);
            } else require(!shaped.flooded(), "raised strict core created water at d=" + d);
        }
    }

    /** A real neighbouring wall, not repeated scalar calls at the same column. */
    private static void checkSpatialDryBankShoulder() {
        for (int scale = 1; scale <= 6; scale++) {
            float metres = 16f / scale;
            WetlandTerrain.Context left = basinAt(-32, -32, metres);
            WetlandTerrain.Context right = basinAt(0, 0, metres);
            var core = WetlandTerrain.shape(metres(64, metres), 64, wet(), (short) 0,
                    0, 0, 17L, metres, right, 0, 0);
            require(core.flooded(), "synthetic basin core did not retain water scale=" + scale);
            var shoulder = WetlandTerrain.shape(metres(70, metres), 70, softWet(), (short) 0,
                    1, 0, 17L, metres, right, 1, 0);
            require(!shoulder.flooded(), "dry bank shoulder created water scale=" + scale);
            require(boundary(shoulder.terrainMeters(), metres) <= 66,
                    "near bank was not lowered beside flooded core scale=" + scale);
            // Fractional metres-per-block can quantize the two density floors a
            // block apart; it must still remove the former 6--8 block cut wall.
            require(Math.abs(boundary(core.terrainMeters(), metres)
                    - boundary(shoulder.terrainMeters(), metres)) <= 5,
                    "wet core exceeded the bounded five-block bank cut scale=" + scale);
            // The screenshot wall can occur inside one fully eligible swamp,
            // not only where the ecology blend is marginal.  A high dry column
            // beside the flooded core must receive the same dry-only shoulder.
            var strictShoulder = WetlandTerrain.shape(metres(70, metres), 70, wet(), (short) 0,
                    1, 0, 17L, metres, right, 1, 0);
            require(!strictShoulder.flooded(), "strict dry bank created water scale=" + scale);
            require(boundary(strictShoulder.terrainMeters(), metres) <= 66,
                    "strict high bank retained the cut wall scale=" + scale);
            var dry = WetlandTerrain.shape(metres(70, metres), 70, dry(), (short) 0,
                    1, 0, 17L, metres, right, 1, 0);
            require(dry.terrainMeters() == metres(70, metres) && !dry.flooded(),
                    "dry land was flattened by basin proxy scale=" + scale);
            var steep = WetlandTerrain.shape(metres(70, metres), 70, steepWet(), (short) 0,
                    1, 0, 17L, metres, right, 1, 0);
            require(!steep.flooded() && boundary(steep.terrainMeters(), metres) >= 65,
                    "steep owner-bank margin exceeded its dry lowering cap scale=" + scale);
            for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                int original = x == 0 && z == 0 ? 64 : 70;
                EcologyClassifier.Sample habitat = x == 0 && z == 0 ? wet() : softWet();
                var a = WetlandTerrain.shape(metres(original, metres), original, habitat, (short) 0,
                        x, z, 17L, metres, left, x + 32, z + 32);
                var b = WetlandTerrain.shape(metres(original, metres), original, habitat, (short) 0,
                        x, z, 17L, metres, right, x, z);
                require(a.equals(b), "bank shoulder differs across overlapping tiles scale=" + scale);
            }
        }
    }

    /**
     * A one-block movement across an inner/outer-minimum edge changes confidence
     * from 1 to .5.  The .5 column cannot receive water, but it must be one dry
     * shoulder step from the stable core instead of self-seeding at distance 0.
     */
    private static void checkQuantizedConfidenceBankShoulder() {
        for (int scale = 1; scale <= 6; scale++) {
            float metres = 16f / scale;
            WetlandTerrain.Context context = quantizedConfidenceStep(metres);
            int coreX = SIZE / 2, z = SIZE / 2;
            require(context.waterPlane(coreX, z) == 65 && context.waterPlane(coreX + 1, z) == 65,
                    "traced plane65 fixture missing scale=" + scale);
            require(context.basinConfidence(coreX, z) == 1f,
                    "stable side lost confidence scale=" + scale);
            require(context.basinConfidence(coreX + 1, z) == .5f,
                    "quantized confidence step missing scale=" + scale);
            require(context.bankDistance(coreX, z) == 0,
                    "stable basin core is not a distance seed scale=" + scale);
            require(context.bankDistance(coreX + 1, z) == 1
                            && context.bankDistance(coreX + 2, z) == 2,
                    "unstable edge self-seeded instead of measuring stable core scale=" + scale);
            long seed = 1;
            WetlandTerrain.Column core = null;
            for (; seed < 512; seed++) {
                core = WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                        coreX, z, seed, metres, context, coreX, z);
                if (core.flooded()) break;
            }
            require(core != null && core.flooded(), "stable strict core did not flood scale=" + scale);
            var edge = WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                    coreX + 1, z, seed, metres, context, coreX + 1, z);
            var next = WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                    coreX + 2, z, seed, metres, context, coreX + 2, z);
            int edgeGround = boundary(edge.terrainMeters(), metres);
            int nextGround = boundary(next.terrainMeters(), metres);
            require(edge.flooded() && edge.waterLevel() == 65 && edgeGround < 65,
                    "confidence edge did not join the owner water scale=" + scale);
            if (next.flooded()) require(next.waterLevel() == 65 && nextGround < 65,
                    "second margin column changed its owner level scale=" + scale);
            require(nextGround >= edgeGround && nextGround <= 68,
                    "confidence edge did not form a bounded continuous margin scale=" + scale);
        }
    }

    /** Stable shallow cores do not fragment at the per-column slope gate. */
    private static void checkDryCoreIslandSlopeContinuity() {
        for (int scale = 1; scale <= 6; scale++) {
            float metres = 16f / scale;
            WetlandTerrain.Context core = flatExact(67, metres);
            long seed = 1;
            WetlandTerrain.Column water = null;
            for (; seed < 512; seed++) {
                water = WetlandTerrain.shape(encodedMeters(69, metres), 69, wetSlope(.18f), (short) 0,
                        7, 11, seed, metres, core, 7, 11);
                if (water.flooded()) break;
            }
            require(water != null && water.flooded(), "strict core water changed scale=" + scale);
            for (float slope : new float[] {.193f, .251f, .312f, .35f, .5f}) {
                var island = WetlandTerrain.shape(encodedMeters(69, metres), 69, wetSlope(slope), (short) 0,
                        7, 11, seed, metres, core, 7, 11);
                require(island.flooded() && island.waterLevel() == 67,
                        "stable shallow core fragmented at slope=" + slope + " scale=" + scale);
            }
            var raised = WetlandTerrain.shape(encodedMeters(71, metres), 71, wetSlope(.5f), (short) 0,
                    7, 11, seed, metres, core, 7, 11);
            require(!raised.flooded() && boundary(raised.terrainMeters(), metres) == 71,
                    "raised steep terrain outside shallow core was flattened scale=" + scale);
            var dry = WetlandTerrain.shape(encodedMeters(69, metres), 69, dry(), (short) 0,
                    7, 11, seed, metres, core, 7, 11);
            require(boundary(dry.terrainMeters(), metres) == 69,
                    "dry terrain became a wetland island scale=" + scale);
        }
    }

    /** The production stability envelope is world-anchored and may move a bank
     * by at most eight blocks, while its unwarped water plane remains exact. */
    private static void checkWarpedStableBoundary() {
        long seed = 6101722349327285760L;
        for (float metres : new float[] {30f, 16f}) {
            WetlandTerrain.Context plain = warpedLineContext(0, -64, metres, 0L, false);
            WetlandTerrain.Context warped = warpedLineContext(0, -64, metres, seed, true);
            WetlandTerrain.Context overlap = warpedLineContext(-32, -64, metres, seed, true);
            boolean varied = false;
            for (int z = 0; z < SIZE; z++) {
                int plainFront = stableFront(plain, z), warpedFront = stableFront(warped, z);
                require(plainFront == 32, "unwarped fixture front changed metres=" + metres);
                require(Math.abs(warpedFront - plainFront) <= 8,
                        "warped frontier exceeded bound metres=" + metres + " z=" + z);
                if (z > 0 && warpedFront != stableFront(warped, z - 1)) varied = true;
                for (int x = 0; x < SIZE; x++)
                    require(warped.waterPlane(x, z) == plain.waterPlane(x, z),
                            "warp changed middle water plane metres=" + metres);
            }
            require(varied, "world-anchored warp left a grid-straight boundary metres=" + metres);
            for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                require(warped.waterPlane(x, z) == overlap.waterPlane(x + 32, z),
                        "warped overlap changed water plane metres=" + metres);
                require(warped.basinConfidence(x, z) == overlap.basinConfidence(x + 32, z),
                        "warped overlap changed confidence metres=" + metres);
                require(warped.bankDistance(x, z) == overlap.bankDistance(x + 32, z),
                        "warped overlap changed bank distance metres=" + metres);
                require(warped.bankPlane(x, z) == overlap.bankPlane(x + 32, z),
                        "warped overlap changed owner plane metres=" + metres);
            }
        }
    }

    /** The owner-plane shore is gradual, bounded, dry at its waterline, and seam-safe. */
    private static void checkContinuousOwnerMargin() {
        for (int scale = 1; scale <= 6; scale++) {
            float metres = 16f / scale;
            WetlandTerrain.Context context = quantizedConfidenceStep(metres);
            int coreX = SIZE / 2, z = SIZE / 2;
            require(context.bankPlane(coreX + 1, z) == 65,
                    "margin lost its stable owner plane scale=" + scale);
            var submerged = WetlandTerrain.shape(encodedMeters(64, metres), 64, wetSlope(.5f), (short) 0,
                    coreX + 1, z, 6101722349327285760L, metres, context, coreX + 1, z);
            require(submerged.flooded() && submerged.waterLevel() == 65,
                    "submerged margin did not keep owner water scale=" + scale);
            var shallowExpansion = WetlandTerrain.shape(encodedMeters(68, metres), 68, wetSlope(.5f), (short) 0,
                    coreX + 1, z, 6101722349327285760L, metres, context, coreX + 1, z);
            int expansionGround = boundary(shallowExpansion.terrainMeters(), metres);
            require(expansionGround >= 64 && expansionGround <= 68,
                    "inner margin escaped its bounded profile scale=" + scale);
            if (shallowExpansion.flooded())
                require(shallowExpansion.waterLevel() == 65 && expansionGround < 65,
                        "inner margin water disagreed with owner plane scale=" + scale);
            int prior = Integer.MIN_VALUE;
            for (int d = 1; d <= 12; d++) {
                var shore = WetlandTerrain.shape(encodedMeters(68, metres), 68, wetSlope(.5f), (short) 0,
                        coreX + d, z, 6101722349327285760L, metres, context, coreX + d, z);
                int ground = boundary(shore.terrainMeters(), metres);
                require(ground >= 63 && ground <= 68,
                        "continuous shore left its physical range d=" + d + " scale=" + scale);
                if (shore.flooded())
                    require(shore.waterLevel() == 65 && ground < 65,
                            "margin water disagreed with owner plane d=" + d + " scale=" + scale);
                if (prior != Integer.MIN_VALUE)
                    require(ground >= prior, "continuous shore stepped downward d=" + d + " scale=" + scale);
                prior = ground;
            }
            java.util.Set<Integer> twoDimensionalGround = new java.util.HashSet<>();
            for (int row = 0; row < SIZE; row++) {
                var farShore = WetlandTerrain.shape(encodedMeters(68, metres), 68, wetSlope(.5f), (short) 0,
                        coreX + 6, row, 6101722349327285760L, metres, context, coreX + 6, row);
                int ground = boundary(farShore.terrainMeters(), metres);
                require(ground >= 63 && ground <= 68,
                        "2D shore escaped physical bounds scale=" + scale);
                twoDimensionalGround.add(ground);
            }
            require(twoDimensionalGround.size() > 1,
                    "world-anchored shore width did not vary in 2D scale=" + scale);
            var highDry = WetlandTerrain.shape(encodedMeters(69, metres), 69, wetSlope(.5f), (short) 0,
                    coreX + 1, z, 6101722349327285760L, metres, context, coreX + 1, z);
            int highDryGround = boundary(highDry.terrainMeters(), metres);
            require(!highDry.flooded() && highDryGround >= 65 && highDryGround < 69
                            && highDryGround >= 64,
                    "raw plane+4 bank was not a bounded dry margin scale=" + scale);
            require(highDryGround - boundary(shallowExpansion.terrainMeters(), metres) <= 1,
                    "raw plane+3 to plane+4 bank retained a 3+ block handoff scale=" + scale);
            var outside = WetlandTerrain.shape(encodedMeters(74, metres), 74, wet(), (short) 0,
                    coreX + 1, z, 6101722349327285760L, metres, context, coreX + 1, z);
            require(!outside.flooded() && boundary(outside.terrainMeters(), metres) == 74,
                    "raised terrain beyond owner margin was shaped scale=" + scale);
            var beyondRadius = WetlandTerrain.shape(encodedMeters(69, metres), 69, wetSlope(.5f), (short) 0,
                    coreX + 17, z, 6101722349327285760L, metres, context, coreX + 17, z);
            require(!beyondRadius.flooded() && boundary(beyondRadius.terrainMeters(), metres) == 69,
                    "dry bank beyond owner radius was shaped scale=" + scale);
        }
    }

    /** Near the wetness admission edge, a d=1 owner margin follows its d=0 core. */
    private static void checkOwnerMarginWetnessContinuity() {
        for (float metres : new float[] {30f, 16f}) {
            WetlandTerrain.Context edge = quantizedConfidenceStep(metres);
            WetlandTerrain.Context core = flatExact(65, metres);
            int x = SIZE / 2, z = SIZE / 2;
            int previous = Integer.MIN_VALUE;
            for (float wetness : new float[] {.549f, .555f, .561f, .608f}) {
                EcologyClassifier.Sample sample = wetMargin(wetness);
                var margin = WetlandTerrain.shape(encodedMeters(67, metres), 67, sample, (short) 0,
                        x + 1, z, 6101722349327285760L, metres, edge, x + 1, z);
                var stable = WetlandTerrain.shape(encodedMeters(67, metres), 67, sample, (short) 0,
                        x, z, 6101722349327285760L, metres, core, x, z);
                int marginGround = boundary(margin.terrainMeters(), metres);
                int stableGround = boundary(stable.terrainMeters(), metres);
                require(!margin.flooded() && !stable.flooded(),
                        "wetness-edge owner margin created full water metres=" + metres + " wetness=" + wetness);
                require(Math.abs(marginGround - stableGround) <= 1,
                        "d=0/d=1 wetness transition made a cliff metres=" + metres + " wetness=" + wetness);
                if (previous != Integer.MIN_VALUE)
                    require(Math.abs(marginGround - previous) <= 1,
                            "wetness epsilon made a full owner cut metres=" + metres + " wetness=" + wetness);
                previous = marginGround;
            }
            var saturatedCore = WetlandTerrain.shape(encodedMeters(67, metres), 67, wet(), (short) 0,
                    x, z, 6101722349327285760L, metres, core, x, z);
            require(boundary(saturatedCore.terrainMeters(), metres) <= 67,
                    "saturated core was raised metres=" + metres);
        }
    }

    /** Marine, moisture, and slope transitions taper the same bounded cut. */
    private static void checkCoastalAdmissionContinuity() {
        for (float metres : new float[] {30f, 16f}) {
            WetlandTerrain.Context sea = flatExact(63, metres);
            int x = 7, z = 11;
            int previous = Integer.MIN_VALUE;
            for (float moisture : new float[] {.54f, .55f, .56f, .60f, .65f, 1.1f}) {
                var shaped = WetlandTerrain.shape(encodedMeters(65, metres), 65,
                        coastalSample(moisture, .17f, .9f), (short) 0,
                        x, z, 22L, metres, sea, x, z);
                int ground = boundary(shaped.terrainMeters(), metres);
                if (previous != Integer.MIN_VALUE)
                    require(Math.abs(ground - previous) <= 1,
                            "coastal moisture edge made a full cut metres=" + metres + " moisture=" + moisture);
                if (shaped.flooded()) require(shaped.waterLevel() == 63 && ground < 63,
                        "coastal moisture transition used a conflicting water plane metres=" + metres);
                previous = ground;
            }
            previous = Integer.MIN_VALUE;
            for (float slope : new float[] {.17f, .18f, .19f, .23f, .28f, .35f}) {
                var shaped = WetlandTerrain.shape(encodedMeters(65, metres), 65,
                        coastalSample(1.1f, slope, .9f), (short) 0,
                        x, z, 22L, metres, sea, x, z);
                int ground = boundary(shaped.terrainMeters(), metres);
                if (previous != Integer.MIN_VALUE)
                    require(Math.abs(ground - previous) <= 1,
                            "coastal slope edge made a full cut metres=" + metres + " slope=" + slope);
                previous = ground;
            }
            // The old .45 marine dispatch had a freshwater cut on one side and
            // a full sea cut on the other. The shared zero-strength hand-off is
            // dry and may differ by at most one quantized block around it.
            previous = Integer.MIN_VALUE;
            for (float influence : new float[] {.34f, .35f, .36f, .40f, .45f, .48f, .51f,
                    .54f, .57f, .60f, .75f, .90f}) {
                var shaped = WetlandTerrain.shape(encodedMeters(65, metres), 65,
                        coastalSample(1.1f, .17f, influence), (short) 0,
                        x, z, 22L, metres, sea, x, z);
                int ground = boundary(shaped.terrainMeters(), metres);
                if (previous != Integer.MIN_VALUE)
                    require(Math.abs(ground - previous) <= 1,
                            "fresh/coastal hand-off made a full cut metres=" + metres + " marine=" + influence);
                previous = ground;
            }
            var stable = WetlandTerrain.shape(encodedMeters(65, metres), 65,
                    coastalSample(1.1f, .02f, .9f), (short) 0,
                    x, z, 22L, metres, sea, x, z);
            require(stable.flooded() && stable.waterLevel() == 63,
                    "stable coastal lowland stopped reaching sea water metres=" + metres);
            var highDry = WetlandTerrain.shape(encodedMeters(72, metres), 72,
                    coastalSample(1.1f, .02f, .9f), (short) 0,
                    x, z, 22L, metres, sea, x, z);
            require(!highDry.flooded(), "high coastal terrain gained sea water metres=" + metres);
        }
    }

    private static int stableFront(WetlandTerrain.Context context, int z) {
        for (int x = 16; x < 49; x++)
            if (context.basinConfidence(x, z) < .999f) return x - 1;
        throw new AssertionError("missing stability transition");
    }

    private static void checkStrictWaterAdmissionAndCoastalShoulder() {
        for (int scale = 1; scale <= 6; scale++) {
            float metres = 16f / scale;
            WetlandTerrain.Context basin = basinAt(0, 0, metres);
            // A stable low core may cross the local slope threshold, but still
            // requires saturated freshwater ecology.
            var freshCore = WetlandTerrain.shape(metres(64, metres), 64, wet(), (short) 0,
                    0, 0, 17L, metres, basin, 0, 0);
            var freshMarginal = WetlandTerrain.shape(metres(64, metres), 64, softWet(), (short) 0,
                    0, 0, 17L, metres, basin, 0, 0);
            require(freshCore.flooded(), "strict freshwater admission regressed scale=" + scale);
            require(freshMarginal.flooded(), "stable wet core fragmented at slope scale=" + scale);

            int pointX = -1, pointZ = -1;
            for (int z = 0; z < 32 && pointX < 0; z++) for (int x = 0; x < 32; x++) {
                var core = WetlandTerrain.shape(metres(63, metres), 63, mangrove(), (short) 0,
                        x, z, 17L, metres, basin, x, z);
                if (core.flooded()) { pointX = x; pointZ = z; break; }
            }
            require(pointX >= 0, "strict coastal admission regressed scale=" + scale);
            var coastalMarginal = WetlandTerrain.shape(metres(63, metres), 63,
                    marginalMangrove(), (short) 0, pointX, pointZ, 22L, metres, basin, pointX, pointZ);
            require(!coastalMarginal.flooded(), "marginal coastal terrain created water scale=" + scale);
            // A separate adjacent high bank exercises the dry coastal policy;
            // it may lower ground but can never acquire sea water.
            var coastalBank = WetlandTerrain.shape(metres(70, metres), 70, mangrove(), (short) 0,
                    1, 0, 17L, metres, basin, 1, 0);
            require(!coastalBank.flooded(), "coastal shoulder created water scale=" + scale);
            require(boundary(coastalBank.terrainMeters(), metres) <= 65,
                    "coastal high bank retained the cut wall scale=" + scale);
        }
    }

    /** Strong wet cores keep a level water table but no longer rasterize as one flat one-block bed. */
    private static void checkVariableCoreBeds() {
        final long seed = 6101722349327285760L;
        final float metres = 30f;
        WetlandTerrain.Context fresh = flatExact(67, metres);
        WetlandTerrain.Context coast = flatExact(63, metres);
        checkVariableCoreBed("fresh", fresh, 67, wet(), seed, metres);
        checkVariableCoreBed("coast", coast, 63, mangrove(), seed, metres);
    }

    private static void checkVariableCoreBed(String name, WetlandTerrain.Context context, int water,
                                             EcologyClassifier.Sample sample, long seed, float metres) {
        int minDepth = Integer.MAX_VALUE, maxDepth = Integer.MIN_VALUE;
        int[][] depths = new int[32][32];
        for (int[] row : depths) java.util.Arrays.fill(row, -1);
        int flooded = 0;
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            var shaped = WetlandTerrain.shape(encodedMeters(water, metres), water, sample, (short) 0,
                    x, z, seed, metres, context, x, z);
            if (!shaped.flooded()) continue; // retained dry hummocks are intentional.
            require(shaped.waterLevel() == water, name + " core changed its water table at " + x + "," + z);
            int profileOne = profileOneBoundary(shaped.terrainMeters());
            int profileTwo = profileTwoBoundary(shaped.terrainMeters());
            require(profileOne == profileTwo, name + " shallow encoding changed with profile at " + x + "," + z);
            int depth = water - profileOne;
            require(depth >= 1 && depth <= 3, name + " core depth escaped 1..3 at " + x + "," + z + ": " + depth);
            depths[z][x] = depth;
            flooded++;
            minDepth = Math.min(minDepth, depth);
            maxDepth = Math.max(maxDepth, depth);
        }
        require(flooded > 32, name + " core did not retain enough flooded columns to exercise its bed");
        require(minDepth == 1 && maxDepth >= 2, name + " core retained a uniform bed");
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            if (x + 1 < 32 && depths[z][x] >= 0 && depths[z][x + 1] >= 0)
                require(Math.abs(depths[z][x] - depths[z][x + 1]) <= 1,
                    name + " coherent bed made an east cliff");
            if (z + 1 < 32 && depths[z][x] >= 0 && depths[z + 1][x] >= 0)
                require(Math.abs(depths[z][x] - depths[z + 1][x]) <= 1,
                    name + " coherent bed made a south cliff");
        }
    }

    /** A real smooth hollow admits one connected water table; a level terrace and slope do not. */
    private static void checkDrainageLandform() {
        final float metres = 16f;
        WetlandTerrain.Context hollow = hollowAt(0, 0, 68, metres);
        WetlandTerrain.Context terrace = levelTerrace(68, metres);
        WetlandTerrain.Context tilted = tiltedTerrace(68, metres);
        int water = 0;
        for (int z = 8; z < 56; z++) for (int x = 8; x < 56; x++) {
            var inHollow = WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                    x, z, 4201L, metres, hollow, x, z);
            var onTerrace = WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                    x, z, 4201L, metres, terrace, x, z);
            require(!onTerrace.flooded(), "level min-filter terrace admitted water at " + x + "," + z);
            if (inHollow.flooded()) {
                water++;
                require(inHollow.waterLevel() == 68, "hollow changed its water table");
            }
        }
        require(water > 48, "smooth hollow did not make a connected wet core");
        int tiltedPlane = tilted.waterPlane(32, 32);
        require(tilted.terrainSlope(32, 32) > .10f,
                "graded terrain fixture did not retain its model slope");
        require(!WetlandTerrain.shape(metres(tiltedPlane, metres), tiltedPlane, wet(), (short) 0,
                32, 32, 4201L, metres, tilted, 32, 32).flooded(),
                "graded terrace admitted a standing-water hollow");
        for (int z = 8; z < 55; z++) for (int x = 8; x < 55; x++) {
            int a = boundary(WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                    x, z, 4201L, metres, hollow, x, z).terrainMeters(), metres);
            int east = boundary(WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                    x + 1, z, 4201L, metres, hollow, x + 1, z).terrainMeters(), metres);
            int south = boundary(WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                    x, z + 1, 4201L, metres, hollow, x, z + 1).terrainMeters(), metres);
            require(Math.abs(a - east) <= 1 && Math.abs(a - south) <= 1,
                    "hollow-bank transect made a cliff");
        }
        WetlandTerrain.Context left = hollowAt(-32, 0, 68, metres);
        WetlandTerrain.Context right = hollowAt(0, 0, 68, metres);
        for (int z = 8; z < 56; z++) for (int x = 0; x < 32; x++) {
            var a = WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                    x, z, 4201L, metres, left, x + 32, z);
            var b = WetlandTerrain.shape(metres(68, metres), 68, wet(), (short) 0,
                    x, z, 4201L, metres, right, x, z);
            require(a.equals(b), "hollow changed across overlapping tiles at " + x + "," + z);
        }
    }

    private static WetlandTerrain.Context flat(int boundary, float metresPerBlock) {
        return hollowAt(0, 0, boundary, metresPerBlock);
    }
    private static WetlandTerrain.Context flatExact(int boundary, float metresPerBlock) {
        return hollowAt(0, 0, boundary, metresPerBlock);
    }
    /** A shallow continuous bowl: it stays in one block-height bin but drains to its centre. */
    private static WetlandTerrain.Context hollowAt(int originX, int originZ, int boundary,
                                                   float metresPerBlock) {
        int field = SIZE + PAD * 2;
        float[] values = new float[field * field];
        for (int z = 0; z < field; z++) for (int x = 0; x < field; x++) {
            int worldX = originX + x - PAD, worldZ = originZ + z - PAD;
            float bowlBlocks = .0001f * (worldX * worldX + worldZ * worldZ);
            values[z * field + x] = encodedBoundary(boundary, metresPerBlock)
                    + bowlBlocks * metresPerBlock;
        }
        return WetlandTerrain.build(values, field, field, metresPerBlock, PAD, SIZE, SIZE);
    }
    private static WetlandTerrain.Context levelTerrace(int boundary, float metresPerBlock) {
        int field = SIZE + PAD * 2;
        float[] values = new float[field * field];
        java.util.Arrays.fill(values, encodedBoundary(boundary, metresPerBlock));
        return WetlandTerrain.build(values, field, field, metresPerBlock, PAD, SIZE, SIZE);
    }
    private static WetlandTerrain.Context tiltedTerrace(int boundary, float metresPerBlock) {
        int field = SIZE + PAD * 2;
        float[] values = new float[field * field];
        for (int z = 0; z < field; z++) for (int x = 0; x < field; x++)
            values[z * field + x] = encodedBoundary(boundary, metresPerBlock)
                    + (x - field / 2f) * .20f * metresPerBlock;
        return WetlandTerrain.build(values, field, field, metresPerBlock, PAD, SIZE, SIZE);
    }
    private static WetlandTerrain.Context terrainAt(int originX, int originZ, float metresPerBlock) {
        return terrainAt(originX, originZ, metresPerBlock, 0);
    }
    private static WetlandTerrain.Context terrainAt(int originX, int originZ, float metresPerBlock, int constant) {
        int field = SIZE + PAD * 2;
        float[] values = new float[field * field];
        for (int z = 0; z < field; z++) for (int x = 0; x < field; x++) {
            int wx = originX + x - PAD, wz = originZ + z - PAD;
            values[z * field + x] = metres(constant == 0 ? fieldBoundary(wx, wz) : constant, metresPerBlock);
        }
        return WetlandTerrain.build(values, field, field, metresPerBlock, PAD, SIZE, SIZE);
    }
    private static WetlandTerrain.Context basinAt(int originX, int originZ, float metresPerBlock) {
        int field = SIZE + PAD * 2;
        float[] values = new float[field * field];
        for (int z = 0; z < field; z++) for (int x = 0; x < field; x++) {
            int worldX = originX + x - PAD, worldZ = originZ + z - PAD;
            values[z * field + x] = metres(worldX == 0 && worldZ == 0 ? 64 : 70, metresPerBlock);
        }
        return WetlandTerrain.build(values, field, field, metresPerBlock, PAD, SIZE, SIZE);
    }

    private static WetlandTerrain.Context quantizedConfidenceStep(float metresPerBlock) {
        int field = SIZE + PAD * 2;
        float[] values = new float[field * field];
        for (int z = 0; z < field; z++) for (int x = 0; x < field; x++) {
            float bowlBlocks = .0001f * ((x - field / 2f) * (x - field / 2f)
                    + (z - field / 2f) * (z - field / 2f));
            values[z * field + x] = encodedBoundary(68, metresPerBlock) + bowlBlocks * metresPerBlock;
        }
        // The line lies exactly on the inner window's left edge for coreX.  It
        // remains in the outer window after moving right one block, producing
        // the observed 1 -> .5 quantized confidence transition.
        int lineX = PAD + SIZE / 2 - 96;
        for (int z = 0; z < field; z++) {
            // The 65 line exits the inner window after moving right one column,
            // while the 66 line remains.  Both stay within the middle/outer
            // windows, yielding the traced plane65/confidence1 -> plane65/.5.
            values[z * field + lineX] = encodedBoundary(65, metresPerBlock);
            values[z * field + PAD + SIZE / 2 + 50] = encodedBoundary(66, metresPerBlock);
        }
        return WetlandTerrain.build(values, field, field, metresPerBlock, PAD, SIZE, SIZE);
    }
    private static WetlandTerrain.Context warpedLineContext(int originX, int originZ, float metresPerBlock,
                                                             long seed, boolean warped) {
        int pad = 184, field = SIZE + pad * 2;
        float[] values = new float[field * field];
        for (int z = 0; z < field; z++) for (int x = 0; x < field; x++) {
            int worldX = originX + x - pad;
            values[z * field + x] = encodedBoundary(worldX == -64 ? 65 : 68, metresPerBlock);
        }
        return warped ? WetlandTerrain.build(values, field, field, metresPerBlock, pad, SIZE, SIZE,
                seed, originX, originZ)
                : WetlandTerrain.build(values, field, field, metresPerBlock, pad, SIZE, SIZE);
    }

    private static int fieldBoundary(int x, int z) { return 88 + Math.floorMod(x * 17 + z * 31, 5); }
    private static int boundary(int metres, float metresPerBlock) {
        return metres >= 0 ? 63 + (int) Math.floor(metres / metresPerBlock) : 62;
    }

    private static short metres(int boundary, float metresPerBlock) {
        return (short) Math.round((boundary - 63) * metresPerBlock);
    }
    private static int profileOneBoundary(int meters) {
        if (meters >= 0) return 63 + (int) (meters / 30f);
        return 62 + (int) (-Math.sqrt(-meters + 10) + Math.sqrt(10));
    }
    private static int profileTwoBoundary(int meters) {
        if (meters >= 0) return 63 + (int) (meters / 30f);
        // OceanDepthProfile preserves this legacy curve through depth 200.
        return 62 + (int) (-Math.sqrt(-meters + 10) + Math.sqrt(10));
    }
    private static short encodedMeters(int boundary, float metresPerBlock) {
        return (short) Math.ceil((boundary - 63) * metresPerBlock);
    }
    private static float encodedBoundary(int boundary, float metresPerBlock) {
        // Leave one density metre above the truncation threshold so binary
        // metres-per-block values (for example 16/5) cannot round below it.
        return (float) Math.ceil((boundary - 63) * metresPerBlock) + 1f;
    }
    private static EcologyClassifier.Sample wet() { return sample(20, 1.3f, .9f, 0); }
    private static EcologyClassifier.Sample wetSlope(float slope) {
        return new EcologyClassifier.Sample(1, .7f, 20, 1.3f, slope, .9f, 0);
    }
    private static EcologyClassifier.Sample wetMargin(float wetness) {
        return new EcologyClassifier.Sample(1, .8f, 18.7f, 1.8f, .02f, wetness, 0);
    }
    private static EcologyClassifier.Sample cold() { return sample(-8, 1.3f, .9f, 0); }
    private static EcologyClassifier.Sample dry() { return sample(20, .2f, .9f, 0); }
    private static EcologyClassifier.Sample mangrove() { return sample(26, 1.3f, .1f, .9f); }
    private static EcologyClassifier.Sample marginalMangrove() { return sample(26, .5f, .1f, .9f); }
    private static EcologyClassifier.Sample coastalSample(float moisture, float slope, float marine) {
        return new EcologyClassifier.Sample(1, .7f, 26, moisture, slope, .1f, marine);
    }
    private static EcologyClassifier.Sample softWet() { return new EcologyClassifier.Sample(1, .7f, 20, 1.3f, .24f, .9f, 0); }
    private static EcologyClassifier.Sample steepWet() { return new EcologyClassifier.Sample(1, .7f, 20, 1.3f, .5f, .9f, 0); }
    private static EcologyClassifier.Sample sample(float temperature, float moisture, float wetness, float marine) {
        return new EcologyClassifier.Sample(1, .7f, temperature, moisture, .02f, wetness, marine);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
