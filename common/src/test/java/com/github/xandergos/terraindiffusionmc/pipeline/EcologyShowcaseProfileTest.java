package com.github.xandergos.terraindiffusionmc.pipeline;

/** Standalone regression checks for deterministic showcase climates. */
public final class EcologyShowcaseProfileTest {
    private EcologyShowcaseProfileTest() {
    }

    public static void main(String[] args) {
        for (int i = 0; i < EcologyClassifier.BIOMES.size(); i++) {
            String name = EcologyClassifier.BIOMES.get(i);
            EcologyClassifier.Sample flat = EcologyShowcaseProfile.sample(name, 1234, -5678, 0.0F);
            EcologyClassifier.Sample sloped = EcologyShowcaseProfile.sample(name, 1234, -5678, .5F);
            require(flat.biomeId() == i + 1, name + " must retain its named ID on flat terrain");
            require(sloped.biomeId() == i + 1, name + " must retain its named ID on a slope");
            require(flat.equals(EcologyShowcaseProfile.sample(name, 1234, -5678, 0.0F)),
                    name + " must be deterministic");
        }

        EcologyClassifier.Sample desert = EcologyShowcaseProfile.sample("desert", 0, 0, 0.0F);
        require(desert.treeCover() == 0.0F, "desert showcase must have no trees");
        EcologyClassifier.Sample flatForest = EcologyShowcaseProfile.sample("temperate_forest", 0, 0, 0.0F);
        EcologyClassifier.Sample steepForest = EcologyShowcaseProfile.sample("temperate_forest", 0, 0, .5F);
        require(steepForest.treeCover() < flatForest.treeCover(), "slope must reduce tree cover");
        expectInvalid("not_an_ecology");
    }

    private static void expectInvalid(String name) {
        try {
            EcologyShowcaseProfile.sample(name, 0, 0, 0.0F);
            throw new AssertionError("invalid profile must throw");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
