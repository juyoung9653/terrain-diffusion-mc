package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Standalone checks for coordinate-continuous ecological decoration fields. */
public final class FeatureEcologyMathTest {
    public static void main(String[] args) {
        long seed = 91357L;
        checkSoilMoisture(seed);
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
