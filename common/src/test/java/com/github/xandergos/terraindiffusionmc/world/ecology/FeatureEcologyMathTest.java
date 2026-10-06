package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Standalone checks for coordinate-continuous ecological decoration fields. */
public final class FeatureEcologyMathTest {
    public static void main(String[] args) {
        long seed = 91357L;
        checkSoilMoisture(seed);
        checkLandscape();
        checkConnectedSoils();
        for (int x = -192; x <= 192; x++) {
            float value = FeatureEcologyMath.broadPatch(seed, x, x * 3, 48);
            require(value >= 0.0F && value <= 1.0F, "noise must remain normalized");
            float moisture = FeatureEcologyMath.surfaceMoisture(0.0F, seed, x, x * 3, 2);
            require(moisture >= 0.0F && moisture <= 2.0F, "surface moisture must remain in source range");
        }
        checkSpecies(-5.0F, 0.9F, true, false, false, "cold");
        checkSpecies(15.0F, 0.9F, false, false, false, "temperate");
        checkSpecies(28.0F, 0.4F, false, true, false, "warm dry");
        checkSpecies(28.0F, 1.6F, false, false, true, "warm wet");
        FeatureEcologyMath.SpeciesWeights below = FeatureEcologyMath.speciesWeights(16.9F, 0.7F);
        FeatureEcologyMath.SpeciesWeights above = FeatureEcologyMath.speciesWeights(17.1F, 0.7F);
        require(Math.abs(below.acacia() - above.acacia()) < 0.04F, "acacia eligibility must taper smoothly");
        FeatureEcologyMath.SpeciesWeights cool = FeatureEcologyMath.speciesWeights(21.9F, 0.9F);
        FeatureEcologyMath.SpeciesWeights hot = FeatureEcologyMath.speciesWeights(22.1F, 0.9F);
        require(Math.abs(cool.spruce() - hot.spruce()) < 0.05F && Math.abs(cool.pine() - hot.pine()) < 0.05F,
                "conifer eligibility must taper smoothly");
        FeatureEcologyMath.SpeciesWeights jungleBelow = FeatureEcologyMath.speciesWeights(19.9F, 1.4F);
        FeatureEcologyMath.SpeciesWeights jungleAbove = FeatureEcologyMath.speciesWeights(20.1F, 1.4F);
        require(Math.abs(jungleBelow.jungle() - jungleAbove.jungle()) < 0.04F, "jungle eligibility must taper smoothly");
        System.out.println("FeatureEcologyMathTest: PASS");
    }

    private static void checkConnectedSoils() {
        for (int scale : new int[]{1, 2, 3, 4, 5, 6}) {
            int mixed = 0, windows = 0, same = 0, pairs = 0;
            for (int z0 = -256; z0 < 256; z0 += 16) for (int x0 = -256; x0 < 256; x0 += 16) {
                boolean sand = false, grass = false;
                for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                    int x = x0 + dx, z = z0 + dz;
                    var material = FeatureEcologyMath.surfaceMaterial(28, .26f, .1f, 0, 91357, x, z, scale);
                    sand |= material == FeatureEcologyMath.SurfaceMaterial.SAND;
                    grass |= material == FeatureEcologyMath.SurfaceMaterial.GRASS;
                    if (material == FeatureEcologyMath.surfaceMaterial(28, .26f, .1f, 0, 91357, x + 1, z, scale)) same++;
                    pairs++;
                }
                if (sand && grass) mixed++;
                windows++;
            }
            float fraction = mixed / (float) windows;
            System.out.printf("soil mosaic scale=%d mixed-16-block-windows=%.3f adjacent-agreement=%.3f%n",
                    scale, fraction, same / (float) pairs);
            // The previous >30% mixed-window requirement rewarded painted islands on flat soil.
            // Relief coverage and contour response are tested separately in ReliefSurfaceTest.
            require(fraction < .30f, "flat terrain must not produce a carpet of small disconnected soil islands");
            require(same / (float) pairs > .95f, "soil lenses must remain connected across adjacent columns");
        }
    }

    private static void checkLandscape() {
        checkSurfaceTransition();
        checkSurfaceCoordinates();
        checkSurfaceExclusions();
        checkTreeLandscape();
    }

    private static void checkSurfaceTransition() {
        float[] moistureSweep = {.12f, .18f, .22f, .26f, .30f, .33f, .38f};
        for (long seed : new long[]{91357L, 0L, -71421L}) {
            MaterialFractions previous = null;
            MaterialFractions[] sweep = new MaterialFractions[moistureSweep.length];
            for (int i = 0; i < moistureSweep.length; i++) {
                sweep[i] = sampleMaterials(seed, moistureSweep[i]);
                if (previous != null) {
                    require(sweep[i].sand <= previous.sand,
                            "sand fraction must recede monotonically through the desert margin");
                }
                previous = sweep[i];
            }
            require(sweep[0].sand == 1.0f, "deep hot desert must remain entirely sand");
            require(sweep[3].sand > .20f && sweep[3].coarse > .01f && sweep[3].grass > .01f,
                    "desert margin must contain interleaved sand, coarse dirt, and grass");
            require(sweep[5].grass > .80f && sweep[5].coarse > .01f && sweep[5].coarse < .20f,
                    "dry grassland must retain a grass matrix with limited exposed soil");
            MaterialFractions below = sampleMaterials(seed, .299f);
            MaterialFractions above = sampleMaterials(seed, .301f);
            require(below.sand > 0 && above.sand > 0 && below.difference(above) < .15f,
                    "desert margin must not jump at the former 0.30 moisture cutoff");
            System.out.printf("surface transition seed=%d deep=%.3f margin=%.3f/%.3f/%.3f dry=%.3f/%.3f/%.3f cutoff-delta=%.3f%n",
                    seed, sweep[0].sand, sweep[3].sand, sweep[3].coarse, sweep[3].grass,
                    sweep[5].sand, sweep[5].coarse, sweep[5].grass, below.difference(above));
        }
    }

    private static MaterialFractions sampleMaterials(long seed, float moisture) {
        int sand = 0, coarse = 0, grass = 0, total = 0;
        for (int x = -2048; x <= 2048; x += 16) {
            for (int z = -2048; z <= 2048; z += 16) {
                var material = FeatureEcologyMath.surfaceMaterial(28, moisture, .1f, 0, seed, x, z, 2);
                if (material == FeatureEcologyMath.SurfaceMaterial.SAND) sand++;
                if (material == FeatureEcologyMath.SurfaceMaterial.COARSE_DIRT) coarse++;
                if (material == FeatureEcologyMath.SurfaceMaterial.GRASS) grass++;
                total++;
            }
        }
        return new MaterialFractions(sand / (float) total, coarse / (float) total, grass / (float) total);
    }

    private static void checkSurfaceCoordinates() {
        long seed = -71421L;
        int changes = 0, edges = 0;
        for (int z = -257; z <= 257; z += 17) {
            var previous = FeatureEcologyMath.surfaceMaterial(28, .28f, .1f, 0, seed, -513, z, 2);
            for (int x = -512; x <= 512; x++) {
                var material = FeatureEcologyMath.surfaceMaterial(28, .28f, .1f, 0, seed, x, z, 2);
                require(material == FeatureEcologyMath.surfaceMaterial(28, .28f, .1f, 0, seed, x, z, 2),
                        "material selection must not depend on repeated or chunk-split evaluation");
                if (material != previous) changes++;
                previous = material;
                edges++;
            }
        }
        require(changes > 0 && changes < edges * .08f,
                "adjacent columns must form coherent regions without per-column speckle");
        int min = -130, max = 129, width = max - min + 1;
        FeatureEcologyMath.SurfaceMaterial[] whole = new FeatureEcologyMath.SurfaceMaterial[width * width];
        for (int z = min; z <= max; z++) {
            for (int x = min; x <= max; x++) {
                whole[(z - min) * width + x - min] =
                        FeatureEcologyMath.surfaceMaterial(28, .28f, .1f, 0, seed, x, z, 2);
            }
        }
        for (int chunkZ = Math.floorDiv(max, 16); chunkZ >= Math.floorDiv(min, 16); chunkZ--) {
            for (int chunkX = Math.floorDiv(max, 16); chunkX >= Math.floorDiv(min, 16); chunkX--) {
                for (int localZ = 15; localZ >= 0; localZ--) {
                    for (int localX = 15; localX >= 0; localX--) {
                        int x = chunkX * 16 + localX, z = chunkZ * 16 + localZ;
                        if (x < min || x > max || z < min || z > max) continue;
                        require(whole[(z - min) * width + x - min]
                                        == FeatureEcologyMath.surfaceMaterial(28, .28f, .1f, 0, seed, x, z, 2),
                                "chunk partition and traversal order must not change the global material field");
                    }
                }
            }
        }
        for (int modelX = -513; modelX <= 513; modelX += 19) {
            int modelZ = modelX * -3;
            for (float moisture : new float[]{.18f, .28f, .33f}) {
                var scaleTwo = FeatureEcologyMath.surfaceMaterial(28, moisture, .1f, 0,
                        seed, modelX * 2, modelZ * 2, 2);
                var scaleOne = FeatureEcologyMath.surfaceMaterial(28, moisture, .1f, 0,
                        seed, modelX, modelZ, 1);
                require(scaleTwo == scaleOne, "material field must preserve model-space scale below the fine-patch cap");
            }
        }
    }

    private static void checkSurfaceExclusions() {
        for (long seed : new long[]{91357L, 0L, -71421L}) {
            for (int x = -1024; x <= 1024; x += 31) {
                int z = x * -2 + 7;
                require(FeatureEcologyMath.surfaceMaterial(0, .2f, .1f, .05f, seed, x, z, 2)
                                == FeatureEcologyMath.SurfaceMaterial.GRASS,
                        "cold grassland must exclude warm-arid sand and coarse dirt");
                require(FeatureEcologyMath.surfaceMaterial(10, .12f, .1f, 0, seed, x, z, 2)
                                != FeatureEcologyMath.SurfaceMaterial.SAND,
                        "sand eligibility must remain excluded at the warm-climate boundary");
                require(FeatureEcologyMath.surfaceMaterial(14, .12f, .1f, 0, seed, x, z, 2)
                                == FeatureEcologyMath.SurfaceMaterial.SAND,
                        "moderately warm deep desert must retain its sand substrate");
                require(FeatureEcologyMath.surfaceMaterial(28, 1.5f, .1f, .9f, seed, x, z, 2)
                                == FeatureEcologyMath.SurfaceMaterial.GRASS,
                        "humid tropical floor must exclude sand, coarse dirt, and conifer podzol");
            }
        }
    }

    private static void checkTreeLandscape() {
        for (long seed : new long[]{91357L, 0L, -71421L}) {
            float minWoodland = 1, maxWoodland = 0;
            for (int x = -2048; x <= 2048; x += 16) {
                for (int z = -2048; z <= 2048; z += 16) {
                    float chance = FeatureEcologyMath.treePlacementChance(.3f, seed, x, z, 2);
                    minWoodland = Math.min(minWoodland, chance);
                    maxWoodland = Math.max(maxWoodland, chance);
                    require(chance >= 0 && chance <= .5f, "bounded placement probability");
                    require(Math.abs(chance - FeatureEcologyMath.treePlacementChance(.3f, seed, x + 1, z, 2)) < .01f,
                            "groves must not flicker between adjacent columns");
                    require(FeatureEcologyMath.treePlacementChance(0, seed, x, z, 2) == 0,
                            "treeless climate must remain treeless");
                    require(chance == FeatureEcologyMath.treePlacementChance(.3f, seed, x / 2 * 5, z / 2 * 5, 5),
                            "grove field must preserve model-space scale");
                }
            }
            require(maxWoodland > minWoodland * 4, "woodland needs distinguishable groves and openings");
        }
    }

    private record MaterialFractions(float sand, float coarse, float grass) {
        float difference(MaterialFractions other) {
            return Math.abs(sand - other.sand) + Math.abs(coarse - other.coarse) + Math.abs(grass - other.grass);
        }
    }

    private static void checkSoilMoisture(long seed) {
        for (int scale : new int[]{2, 5}) {
            int cell = 64 * scale;
            float repeated = FeatureEcologyMath.surfaceMoisture(.50F, seed, 101, -73, scale);
            require(repeated == FeatureEcologyMath.surfaceMoisture(.50F, seed, 101, -73, scale),
                    "soil moisture must be deterministic");
            for (int x = -2 * cell; x <= 2 * cell; x += 7) {
                for (int z = -2 * cell; z <= 2 * cell; z += 11) {
                    float cold = FeatureEcologyMath.surfaceMoisture(.50F, seed, x, z, scale);
                    float desert = FeatureEcologyMath.surfaceMoisture(.10F, seed, x, z, scale);
                    require(cold > .40F, "cold grassland must not gain coarse-dirt islands");
                    require(desert < .22F, "desert must remain eligible for sand");
                    require(Math.abs(cold - FeatureEcologyMath.surfaceMoisture(.50F, seed, x + 1, z, scale)) < .003F,
                            "adjacent soil moisture must vary gently");
                }
            }
            checkFarSeam(seed, 29_000_000, cell);
            checkFarSeam(seed, -29_000_000, cell);
        }
        for (int modelX = -193; modelX <= 193; modelX += 13) {
            int modelZ = modelX * 3;
            float scaleTwo = FeatureEcologyMath.surfaceMoisture(.50F, seed, modelX * 2, modelZ * 2, 2);
            float scaleFive = FeatureEcologyMath.surfaceMoisture(.50F, seed, modelX * 5, modelZ * 5, 5);
            require(scaleTwo == scaleFive, "matching model positions must retain soil moisture across scales");
        }
        float[] whole = new float[385];
        for (int x = -192; x <= 192; x++) whole[x + 192] = FeatureEcologyMath.surfaceMoisture(.50F, seed, x, -73, 2);
        for (int x = -192; x < 0; x++)
            require(whole[x + 192] == FeatureEcologyMath.surfaceMoisture(.50F, seed, x, -73, 2),
                    "negative-coordinate split must retain soil moisture");
        for (int x = 0; x <= 192; x++)
            require(whole[x + 192] == FeatureEcologyMath.surfaceMoisture(.50F, seed, x, -73, 2),
                    "positive-coordinate split must retain soil moisture");
    }

    private static void checkFarSeam(long seed, int coordinate, int cell) {
        int seam = Math.floorDiv(coordinate, cell) * cell;
        float left = FeatureEcologyMath.surfaceMoisture(.50F, seed, seam - 1, coordinate, cell / 64);
        float right = FeatureEcologyMath.surfaceMoisture(.50F, seed, seam, coordinate, cell / 64);
        require(Math.abs(left - right) < .003F, "far cell boundary must remain smooth");
    }

    private static void checkSpecies(float temperature, float moisture, boolean conifers, boolean acacia, boolean jungle, String label) {
        FeatureEcologyMath.SpeciesWeights weights = FeatureEcologyMath.speciesWeights(temperature, moisture);
        require(weights.total() > 0.0F, label + " must retain an eligible species");
        if (conifers) {
            require(weights.spruce() > 0.0F && weights.pine() > 0.0F, label + " must permit conifers");
            require(weights.oak() == 0.0F && weights.birch() == 0.0F && weights.acacia() == 0.0F && weights.jungle() == 0.0F,
                    label + " must exclude incompatible broadleaf species");
        } else if (label.equals("temperate")) {
            require(weights.oak() > 0.0F && weights.birch() > 0.0F, "temperate must permit broadleaf trees");
            require(weights.acacia() == 0.0F && weights.jungle() == 0.0F, "temperate must exclude tropical trees");
        } else if (acacia) {
            require(weights.acacia() > 0.0F && weights.jungle() == 0.0F && weights.spruce() == 0.0F && weights.pine() == 0.0F,
                    label + " must select only compatible warm dry trees");
        } else if (jungle) {
            require(weights.jungle() > 0.0F && weights.acacia() == 0.0F && weights.spruce() == 0.0F && weights.pine() == 0.0F,
                    label + " must select only compatible warm wet trees");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
