package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Standalone checks for coordinate-continuous ecological decoration fields. */
public final class FeatureEcologyMathTest {
    public static void main(String[] args) {
        long seed = 91357L;
        float repeated = FeatureEcologyMath.broadPatch(seed, 101, -73, 48);
        require(repeated == FeatureEcologyMath.broadPatch(seed, 101, -73, 48), "noise must be deterministic");
        float left = FeatureEcologyMath.broadPatch(seed, 47, 22, 48);
        float right = FeatureEcologyMath.broadPatch(seed, 48, 22, 48);
        require(Math.abs(left - right) < 0.08F, "cell boundary must be smooth");
        checkFarSeam(seed, 29_000_000);
        checkFarSeam(seed, -29_000_000);
        for (int x = -192; x <= 192; x++) {
            float value = FeatureEcologyMath.broadPatch(seed, x, x * 3, 48);
            require(value >= 0.0F && value <= 1.0F, "noise must remain normalized");
            float moisture = FeatureEcologyMath.surfaceMoisture(0.0F, seed, x, x * 3);
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

    private static void checkFarSeam(long seed, int coordinate) {
        int seam = Math.floorDiv(coordinate, 48) * 48;
        float left = FeatureEcologyMath.broadPatch(seed, seam - 1, coordinate, 48);
        float right = FeatureEcologyMath.broadPatch(seed, seam, coordinate, 48);
        require(Math.abs(left - right) < 0.08F, "far cell boundary must remain smooth");
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
