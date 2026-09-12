package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Feature-facing read-only lookup; uses the same cached fields as biome assignment. */
public final class NaturalEcology {
    private NaturalEcology() { }
    public static EcologyClassifier.Sample sample(ChunkGenerator generator, int x, int z) {
        if (!(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource source) || !source.naturalBiomes())
            return null;
        int size = TerrainDiffusionConfig.tileSize();
        int x0 = Math.floorDiv(x, size) * size, z0 = Math.floorDiv(z, size) * size;
        var data = LocalTerrainProvider.getInstance().fetchHeightmap(z0, x0, z0 + size, x0 + size);
        if (data == null || data.ecology == null) throw new IllegalStateException("Natural ecology tile unavailable");
        var result = data.ecology.sample(x - x0, z - z0);
        if (!source.ecologyShowcase().isEmpty()) {
            if (EcologyShowcaseProfile.retainsTerrainBiome(data.ecology.terrainBiomeId(x - x0, z - z0))
                    || EcologyShowcaseProfile.isVanillaOcean(data.biomeIds[z - z0][x - x0]))
                return null;
            return EcologyShowcaseProfile.sample(source.ecologyShowcase(), x, z, result.slope());
        }
        return result.biomeId() == 0 ? null : result;
    }
}
