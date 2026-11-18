package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.pipeline.WetlandEcology;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.HashMap;
import java.util.Map;

/** Immutable, tile-stable mangrove coast field shared by sediment and lilies. */
final class CoastalContext {
    record Sample(float weighted, float peak, float concavity) {
        float affinity(int depth) {
            return FeatureEcologyMath.coastalMangroveAffinity(weighted, peak, depth, concavity);
        }
    }

    private final ChunkGenerator generator;
    private final com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource source;
    private final Map<Long, NaturalEcology.SurfaceSample> cache = new HashMap<>();

    CoastalContext(ChunkGenerator generator) {
        this.generator = generator;
        this.source = TerrainDiffusionBiomeSources.find(generator.getBiomeSource());
    }

    Sample sample(int x, int z, long seed) {
        // Sand bars and sheltered mud tongues cross the climate boundary. Sample
        // their supply over 36 blocks, with a gently meandering centre. Keep
        // bathymetry centred on the actual bed rather than the displaced source.
        int dx = Math.round(18 * (FeatureEcologyMath.broadPatch(seed ^ 0x53484f524558L, x, z, 28) - .5f));
        int dz = Math.round(18 * (FeatureEcologyMath.broadPatch(seed ^ 0x53484f52455aL, x, z, 28) - .5f));
        float[] field = new float[49];
        float peak = 0, heights = 0, total = 0, center = 0;
        for (int iz = 0; iz < 7; iz++) for (int ix = 0; ix < 7; ix++) {
            NaturalEcology.SurfaceSample surface = surface(x + (ix - 3) * 6, z + (iz - 3) * 6);
            if (surface == null) continue;
            float weight = (ix == 3 ? 4 : ix < 3 ? ix + 1 : 7 - ix)
                    * (iz == 3 ? 4 : iz < 3 ? iz + 1 : 7 - iz);
            // SurfaceSample elevation is pipeline metres. Convert each sample
            // through the active world scale before comparing local relief.
            short meters = (short) surface.elevation();
            float height = source != null && source.underground()
                    ? HeightConverter.convertToMinecraftHeight(meters, source.boundUndergroundScale(),
                    source.undergroundProfileVersion())
                    : HeightConverter.convertToMinecraftHeight(meters);
            heights += height * weight;
            total += weight;
            if (ix == 3 && iz == 3) center = height;
            NaturalEcology.SurfaceSample supply = surface(x + dx + (ix - 3) * 6, z + dz + (iz - 3) * 6);
            if (supply == null || supply.terrainBiomeId() != 0 || supply.climate().biomeId() == 0) continue;
            float mangrove = WetlandEcology.weights(supply.climate()).mangrove();
            field[iz * 7 + ix] = mangrove;
            peak = Math.max(peak, mangrove);
        }
        return new Sample(FeatureEcologyMath.coastalMangroveSupport(field), peak,
                total == 0 ? 0 : heights / total - center);
    }

    private NaturalEcology.SurfaceSample surface(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        NaturalEcology.SurfaceSample result = cache.get(key);
        if (result != null) return result;
        result = NaturalEcology.surfaceSample(generator, x, z);
        if (result != null) cache.put(key, result);
        return result;
    }
}
