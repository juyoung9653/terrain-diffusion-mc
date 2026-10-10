package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Feature-facing read-only lookup; uses the same cached fields as biome assignment. */
public final class NaturalEcology {
    public record SurfaceSample(EcologyClassifier.Sample climate, short terrainBiomeId, float elevation,
                                float snowRetention) { }

    private NaturalEcology() { }

    /** Vegetation keeps its existing land-only eligibility. */
    public static EcologyClassifier.Sample sample(ChunkGenerator generator, int x, int z) {
        SurfaceSample surface = surfaceSample(generator, x, z);
        return surface == null || surface.climate().biomeId() == 0 ? null : surface.climate();
    }

    /** Surface-only lookup retains shore climate without enabling land vegetation on shores. */
    public static SurfaceSample surfaceSample(ChunkGenerator generator, int x, int z) {
        return surfaceSample(generator, x, z, false);
    }

    /** Optional weather/native-decoration lookup: no tile inference on a cache miss. */
    public static SurfaceSample cachedSurfaceSample(ChunkGenerator generator, int x, int z) {
        return surfaceSample(generator, x, z, true);
    }

    private static SurfaceSample surfaceSample(ChunkGenerator generator, int x, int z, boolean cachedOnly) {
        if (!(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource source) || !source.naturalBiomes())
            return null;
        int size = TerrainDiffusionConfig.tileSize();
        int x0 = Math.floorDiv(x, size) * size, z0 = Math.floorDiv(z, size) * size;
        var provider = cachedOnly ? LocalTerrainProvider.peekInstance() : LocalTerrainProvider.getInstance();
        if (provider == null) return null;
        var data = cachedOnly ? provider.peekHeightmap(z0, x0, z0 + size, x0 + size)
                : provider.fetchHeightmap(z0, x0, z0 + size, x0 + size);
        if (cachedOnly && (data == null || data.ecology == null)) return null;
        if (data == null || data.ecology == null) throw new IllegalStateException("Natural ecology tile unavailable");
        var result = data.ecology.sample(x - x0, z - z0);
        short terrainId = data.ecology.terrainBiomeId(x - x0, z - z0);
        float elevation = data.heightmap[z - z0][x - x0];
        float snowRetention = data.ecology.snowRetention(x - x0, z - z0);
        if (!source.ecologyShowcase().isEmpty()) {
            if (EcologyShowcaseProfile.retainsTerrainBiome(data.ecology.terrainBiomeId(x - x0, z - z0))
                    || EcologyShowcaseProfile.isVanillaOcean(data.biomeIds[z - z0][x - x0]))
                return null;
            return new SurfaceSample(EcologyShowcaseProfile.sample(source.ecologyShowcase(), x, z, result.slope()),
                    (short) 0, elevation, snowRetention);
        }
        return new SurfaceSample(result, terrainId, elevation, snowRetention);
    }
}
