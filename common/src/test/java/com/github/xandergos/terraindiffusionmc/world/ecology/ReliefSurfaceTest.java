package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;

/** Synthetic surfaces: these checks do not claim to be an in-game landscape preview. */
public final class ReliefSurfaceTest {
    public static void main(String[] args) {
        int altitudeChanges = 0, total = 0;
        for (int scale = 1; scale <= 6; scale++) {
            int changes = 0, edges = 0;
            FeatureEcologyMath.SurfaceMaterial[][] whole = new FeatureEcologyMath.SurfaceMaterial[37][256];
            for (int z = -128; z < 128; z += 7) {
                var previous = material(-129, z, scale);
                for (int x = -128; x < 128; x++) {
                    var actual = material(x, z, scale);
                    whole[(z + 128) / 7][x + 128] = actual;
                    if (actual != previous) changes++;
                    previous = actual;
                    edges++;
                    var raised = FeatureEcologyMath.surfaceMaterial(28, .26f, .2f, 0,
                            height(x, z) + 180, 91357L, x, z, scale);
                    if (actual != raised) altitudeChanges++;
                    total++;
                    require(FeatureEcologyMath.surfaceMaterial(28, 1.5f, .7f, .9f,
                            height(x, z), 91357L, x, z, scale) == FeatureEcologyMath.SurfaceMaterial.GRASS,
                            "wet tropical slopes must never become desert soil");
                }
            }
            // Revisit in reverse chunk order, including negative chunk keys, after all first-pass calls.
            for (int chunkX = 7; chunkX >= -8; chunkX--) {
                for (int row = 36; row >= 0; row--) for (int localX = 15; localX >= 0; localX--) {
                    int x = chunkX * 16 + localX, z = -128 + row * 7;
                    require(whole[row][x + 128] == material(x, z, scale),
                            "partitioned reverse traversal must match the complete physical surface");
                }
            }
            require(changes > 0 && changes < edges * .08, "terrain-following surfaces remain coherent");
            System.out.printf("relief scale=%d edge-change=%.4f%n", scale, changes / (float) edges);
        }
        require(altitudeChanges > total * .15, "physical altitude must influence the material patches");
        var valley = EcologyClassifier.select(-17, 16, 50, .1f, false, 20, 500, 300).sample();
        var ridge = EcologyClassifier.select(-17, 16, 650, .8f, false, 20, 500, 300).sample();
        require(valley.biomeId() == ridge.biomeId(), "relief must preserve broad climate identity");
        require(valley.moisture() > ridge.moisture(), "sheltered lowland retains more moisture than drained ridge");
        require(valley.temperature() > ridge.temperature(), "existing physical altitude lapse must remain active");
        for (float t : new float[]{-5, 4.9f, 5.1f, 17.1f, 22.9f, 23.1f, 28}) {
            for (float m : new float[]{.28f, .31f, .50f, .54f, .64f, .66f, 1.1f, 1.5f}) {
                float rain = m * (500 + 20 * Math.max(0, t));
                int baseId = EcologyClassifier.select(-16, 16, 300, .1f, false, t, rain, 300).sample().biomeId();
                for (float elevation : new float[]{100, 300, 1200}) for (float slope : new float[]{.05f, .5f, 1.1f}) {
                    var changed = EcologyClassifier.select(-16, 16, elevation, slope, false, t, rain, 300).sample();
                    require(changed.biomeId() == baseId, "retention must not remap regional ecosystems at thresholds");
                    require(changed.moisture() >= 0 && changed.moisture() <= 2, "local moisture remains bounded");
                }
            }
        }
        require(FeatureEcologyMath.dryMineralShore(28, .3f), "desert shore margin remains mineral");
        require(!FeatureEcologyMath.dryMineralShore(28, 1.5f), "humid coast is not desert");
        require(!FeatureEcologyMath.dryMineralShore(-4, .2f), "frozen coast is not desert");
        checkBanks();
        try {
            FeatureEcologyMath.surfaceMaterial(28, .3f, .1f, 0, 100, 0L, 0, 0, 0);
            throw new AssertionError("invalid scale must fail explicitly");
        } catch (IllegalArgumentException expected) { }
        System.out.println("ReliefSurfaceTest: PASS");
    }

    private static void checkBanks() {
        int nearGrass = 0, farGrass = 0, desertMineral = 0, total = 0;
        for (int x = -512; x <= 512; x += 3) for (int z = -512; z <= 512; z += 11) {
            var far = FeatureEcologyMath.bankSurfaceMaterial(28, .26f, .1f, 0, 100,
                    Float.POSITIVE_INFINITY, 91357L, x, z, 2);
            var land = FeatureEcologyMath.surfaceMaterial(28, .26f, .1f, 0, 100, 91357L, x, z, 2);
            require(far == land, "bank outside observed water influence must join the ordinary land surface");
            var near = FeatureEcologyMath.bankSurfaceMaterial(28, .26f, .1f, 0, 100, 1, 91357L, x, z, 2);
            if (near == FeatureEcologyMath.SurfaceMaterial.GRASS) nearGrass++;
            if (far == FeatureEcologyMath.SurfaceMaterial.GRASS) farGrass++;
            var desert = FeatureEcologyMath.bankSurfaceMaterial(28, .10f, .1f, 0, 100, 1, 91357L, x, z, 2);
            if (desert != FeatureEcologyMath.SurfaceMaterial.GRASS) desertMineral++;
            require(FeatureEcologyMath.bankSurfaceMaterial(28, 1.5f, .1f, .9f, 100, 1,
                    91357L, x, z, 2) == FeatureEcologyMath.SurfaceMaterial.GRASS, "wet forest bank retains grass");
            total++;
        }
        require(nearGrass > farGrass && nearGrass < total, "river moisture encourages broken grass patches, never a solid rim");
        require(desertMineral > total * .95, "deep desert banks remain predominantly mineral");
        System.out.printf("bank grass near=%.3f far=%.3f desert-mineral=%.3f%n",
                nearGrass / (float) total, farGrass / (float) total, desertMineral / (float) total);
    }

    private static float height(int x, int z) {
        return (float) (180 + 70 * Math.sin(x / 110.0) + 45 * Math.sin(z / 83.0));
    }
    private static FeatureEcologyMath.SurfaceMaterial material(int x, int z, int scale) {
        return FeatureEcologyMath.surfaceMaterial(28, .26f, .2f, 0, height(x, z), 91357L, x, z, scale);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
