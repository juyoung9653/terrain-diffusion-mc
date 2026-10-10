package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;

/** Standalone checks for final-surface biome-boundary feathering. */
public final class SurfaceBoundaryDitherTest {
    public static void main(String[] args) {
        checkOffsets();
        checkReferenceTileGuard();
        checkClimateOwnership();
        checkNonBoundary();
        System.out.println("SurfaceBoundaryDitherTest: PASS");
    }

    private static void checkReferenceTileGuard() {
        int[] calls = {0};
        java.util.function.Supplier<String> donor = () -> {
            calls[0]++;
            return "sampled";
        };
        require(SurfaceBoundaryDither.sampleDonorWithinReferenceTile(255, 8, 256, 8, 256, donor) == null,
                "positive reference seam must reject the donor before sampling");
        require(SurfaceBoundaryDither.sampleDonorWithinReferenceTile(-1, 8, 0, 8, 256, donor) == null,
                "zero reference seam must use floor division and reject the donor before sampling");
        require(SurfaceBoundaryDither.sampleDonorWithinReferenceTile(-256, -1, -256, 0, 256, donor) == null,
                "negative reference seam must reject the donor before sampling");
        require(calls[0] == 0, "reference seams must cause zero provider calls");
        require("sampled".equals(SurfaceBoundaryDither.sampleDonorWithinReferenceTile(
                        15, -33, 16, -32, 256, donor)),
                "ordinary chunk seams inside one reference tile must remain eligible");
        require(calls[0] == 1, "eligible donor must be sampled exactly once");
    }

    private static void checkOffsets() {
        long seed = -1_232_332_549_304_237_634L;
        boolean[] xSeen = new boolean[5], zSeen = new boolean[5];
        for (int z = -96; z <= 96; z++) for (int x = -96; x <= 96; x++) {
            var offset = SurfaceBoundaryDither.offset(seed, x, z);
            require(offset.x() >= -2 && offset.x() <= 2, "horizontal x offset must stay within two blocks");
            require(offset.z() >= -2 && offset.z() <= 2, "horizontal z offset must stay within two blocks");
            require(offset.equals(SurfaceBoundaryDither.offset(seed, x, z)),
                    "offset must be stable across repeated and negative-coordinate evaluation");
            xSeen[offset.x() + 2] = true;
            zSeen[offset.z() + 2] = true;
        }
        for (int i = 0; i < 5; i++) require(xSeen[i] && zSeen[i], "all five offsets must be reachable");
    }

    private static void checkClimateOwnership() {
        EcologyClassifier.Sample coolWet = new EcologyClassifier.Sample(1, .8F, 2F, 1.5F, .65F);
        EcologyClassifier.Sample hotDry = new EcologyClassifier.Sample(11, .1F, 28F, .10F, .05F);
        EcologyClassifier.Sample selected = SurfaceBoundaryDither.selectClimate(true,
                coolWet, 600F, hotDry, 100F);
        require(selected.biomeId() == hotDry.biomeId(), "a real boundary must use donor climate ownership");
        require(selected.treeCover() == hotDry.treeCover() && selected.moisture() == hotDry.moisture(),
                "donor cover and moisture must cross the boundary");
        require(Math.abs(selected.temperature() - 25F) < .0001F,
                "donor temperature must be lapse-corrected to target elevation");
        require(selected.slope() == coolWet.slope(), "target physical slope must remain local");

        var targetMaterial = FeatureEcologyMath.surfaceMaterialAt(coolWet.temperature(), coolWet.moisture(),
                coolWet.slope(), coolWet.treeCover(), 600F, 91357L, 19, -31, 2);
        var donorMaterial = FeatureEcologyMath.surfaceMaterialAt(selected.temperature(), selected.moisture(),
                selected.slope(), selected.treeCover(), 600F, 91357L, 19, -31, 2);
        require(targetMaterial == FeatureEcologyMath.SurfaceMaterial.GRASS,
                "fixture target must retain its cool wet material");
        require(donorMaterial == FeatureEcologyMath.SurfaceMaterial.SAND,
                "boundary result must change through donor climate, not a shifted material-noise coordinate");
    }

    private static void checkNonBoundary() {
        EcologyClassifier.Sample target = new EcologyClassifier.Sample(2, .6F, 12F, .8F, .2F);
        EcologyClassifier.Sample donor = new EcologyClassifier.Sample(8, .1F, 26F, .12F, .9F);
        require(SurfaceBoundaryDither.selectClimate(false, target, 200F, donor, 900F) == target,
                "same-biome and ineligible edges must retain target climate");
        require(SurfaceBoundaryDither.selectClimate(true, target, 200F, null, 900F) == target,
                "missing donor data must retain target climate");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
