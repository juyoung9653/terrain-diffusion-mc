package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;

import java.util.function.Supplier;

/** Deterministic, surface-only climate ownership at natural ecology boundaries. */
public final class SurfaceBoundaryDither {
    private static final long X_SALT = 0x535552464458L;
    private static final long Z_SALT = 0x53555246445AL;
    private static final float TEMPERATURE_LAPSE_PER_METRE = 0.006F;

    public record Offset(int x, int z) { }

    private SurfaceBoundaryDither() { }

    /** Matches Pufferfish's five-block-wide offset domain without changing biome IDs. */
    public static Offset offset(long seed, int blockX, int blockZ) {
        int x = (int) Math.floorMod(FeatureEcologyMath.decorationSeed(seed, blockX, blockZ, X_SALT), 5L) - 2;
        int z = (int) Math.floorMod(FeatureEcologyMath.decorationSeed(seed, blockX, blockZ, Z_SALT), 5L) - 2;
        return new Offset(x, z);
    }

    /** Never starts terrain inference in another reference tile for a cosmetic surface blend. */
    public static <T> T sampleDonorWithinReferenceTile(int targetX, int targetZ, int donorX, int donorZ,
                                                        int referenceTileSize, Supplier<T> donor) {
        if (referenceTileSize <= 0) throw new IllegalArgumentException("referenceTileSize must be positive");
        if (Math.floorDiv(targetX, referenceTileSize) != Math.floorDiv(donorX, referenceTileSize)
                || Math.floorDiv(targetZ, referenceTileSize) != Math.floorDiv(donorZ, referenceTileSize))
            return null;
        return donor.get();
    }

    /**
     * Uses the neighbouring land biome's climate ownership while retaining the target column's
     * physical slope and altitude. The donor temperature is moved along the classifier's lapse
     * rate from donor elevation to target elevation, so relief is never sampled from another column.
     */
    public static EcologyClassifier.Sample selectClimate(boolean crossesLandBiomeBoundary,
                                                           EcologyClassifier.Sample target,
                                                           float targetElevation,
                                                           EcologyClassifier.Sample donor,
                                                           float donorElevation) {
        if (!crossesLandBiomeBoundary || donor == null) return target;
        // Cold surfaces already blend continuously with the very same column climate as snow.
        // Borrowing a warm donor would put a green substrate underneath a snowy crown.
        if (EcologyClassifier.coldState(target.temperature()) > 0
                || EcologyClassifier.coldState(donor.temperature()) > 0) return target;
        float elevationDelta = Math.max(0, targetElevation) - Math.max(0, donorElevation);
        float temperature = donor.temperature() - TEMPERATURE_LAPSE_PER_METRE * elevationDelta;
        return new EcologyClassifier.Sample(donor.biomeId(), donor.treeCover(), temperature,
                donor.moisture(), target.slope());
    }
}
