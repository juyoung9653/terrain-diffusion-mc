package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath.SurfaceMaterial;

/** V10 exposed river soil follows the original terrain, never the river's replacement biome. */
public final class NaturalRiverBankSurface {
    private static final short GRAVEL_BEACH = (short) (TerralithBiomeClassifier.biomeNames()
            .indexOf("terralith:gravel_beach") + 1);

    private NaturalRiverBankSurface() { }

    /** Null preserves native material where no land or shore surface is classified. */
    public static SurfaceMaterial material(EcologyClassifier.Sample climate, short terrain,
                                           float elevation, float waterDistance, long seed,
                                           int x, int z, int scale) {
        if (terrain == ShoreClassifier.STONY_SHORE) return SurfaceMaterial.STONE;
        if (terrain == GRAVEL_BEACH) return SurfaceMaterial.GRAVEL;
        if (terrain == ShoreClassifier.SNOWY_BEACH) return SurfaceMaterial.SAND;
        if (climate.biomeId() == 0 && terrain != ShoreClassifier.BEACH) return null;
        if (terrain == ShoreClassifier.BEACH
                && FeatureEcologyMath.dryMineralShore(climate.temperature(), climate.moisture()))
            return SurfaceMaterial.SAND;
        SurfaceMaterial riparian = FeatureEcologyMath.bankSurfaceMaterial(climate.temperature(), climate.moisture(),
                climate.slope(), climate.treeCover(), elevation, waterDistance, seed, x, z, scale);
        // The bounded water bonus may support an existing grass bank, but must
        // not turn a locally mineral arid surface green.
        if (riparian != SurfaceMaterial.GRASS) return riparian;
        SurfaceMaterial original = FeatureEcologyMath.surfaceMaterial(climate.temperature(), climate.moisture(),
                climate.slope(), climate.treeCover(), elevation, seed, x, z, scale);
        return original == SurfaceMaterial.SAND ? original : riparian;
    }
}
