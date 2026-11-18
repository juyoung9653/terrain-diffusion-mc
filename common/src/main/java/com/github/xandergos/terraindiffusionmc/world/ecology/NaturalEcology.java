package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.HashMap;
import java.util.Map;

/** Feature-facing read-only lookup; uses the same cached fields as biome assignment. */
public final class NaturalEcology {
    public record SurfaceSample(EcologyClassifier.Sample climate, short terrainBiomeId, float elevation,
                                float snowRetention) { }

    private NaturalEcology() { }

    /**
     * A per-feature view of the shaped wetland water table. It uses the same
     * canonical heightmap fetch as surfaceSample and caches tiles locally.
     */
    public static PlannedWaterLevels plannedWaterLevels(ChunkGenerator generator) {
        var source = TerrainDiffusionBiomeSources.find(generator.getBiomeSource());
        if (source == null || !source.naturalBiomes() || !source.ecologyShowcase().isEmpty()) return null;
        return new PlannedWaterLevels();
    }

    /** Canonical bounded margin lookup for late decorators outside the wet floor. */
    public static int nearestPlannedWetlandWaterLevel(ChunkGenerator generator, int x, int z, int radius) {
        PlannedWaterLevels levels = plannedWaterLevels(generator);
        return levels == null ? 0 : levels.nearestLevel(x, z, radius);
    }

    public static final class PlannedWaterLevels {
        private final int tileSize = TerrainDiffusionConfig.tileSize();
        private final Map<Long, LocalTerrainProvider.HeightmapData> tiles = new HashMap<>();

        /** Exclusive fluid surface Y, or zero where the shaped terrain has no wetland water. */
        public int levelAt(int x, int z) {
            int x0 = Math.floorDiv(x, tileSize) * tileSize;
            int z0 = Math.floorDiv(z, tileSize) * tileSize;
            long key = ((long) x0 << 32) ^ (z0 & 0xffffffffL);
            var data = tiles.get(key);
            if (data == null) {
                data = LocalTerrainProvider.getInstance().fetchHeightmap(z0, x0, z0 + tileSize, x0 + tileSize);
                if (data == null) throw new IllegalStateException("Natural wetland water tile unavailable");
                tiles.put(key, data);
            }
            if (data.wetlandWaterLevels == null) return 0;
            return data.wetlandWaterLevels[z - z0][x - x0];
        }

        public int nearestLevel(int x, int z, int radius) {
            int local = levelAt(x, z);
            if (local > 0 || radius <= 0) return local;
            for (int distance = 1; distance <= radius; distance++) {
                for (int dx = -distance; dx <= distance; dx++) {
                    int dz = distance - Math.abs(dx);
                    int first = levelAt(x + dx, z + dz);
                    if (first > 0) return first;
                    if (dz != 0) {
                        int second = levelAt(x + dx, z - dz);
                        if (second > 0) return second;
                    }
                }
            }
            return 0;
        }
    }

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

    /**
     * Return the exposure captured with the generated ecology tile. Uses the
     * same canonical tile fetch as surfaceSample so cache eviction or feature
     * order cannot alter placement.
     */
    public static float sampleOpenWaterFraction(ChunkGenerator generator, int x, int z) {
        var source = TerrainDiffusionBiomeSources.find(generator.getBiomeSource());
        if (source == null || !source.naturalBiomes() || !source.ecologyShowcase().isEmpty()) return 1F;
        int size = TerrainDiffusionConfig.tileSize();
        int x0 = Math.floorDiv(x, size) * size, z0 = Math.floorDiv(z, size) * size;
        var data = LocalTerrainProvider.getInstance().fetchHeightmap(z0, x0, z0 + size, x0 + size);
        if (data == null || data.ecology == null)
            throw new IllegalStateException("Natural ecology tile unavailable");
        return data.ecology.openWaterFraction(x - x0, z - z0);
    }

    private static SurfaceSample surfaceSample(ChunkGenerator generator, int x, int z, boolean cachedOnly) {
        var source = TerrainDiffusionBiomeSources.find(generator.getBiomeSource());
        if (source == null || !source.naturalBiomes())
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
