package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** The raw TD ocean predicate used only by the watershed boundary adapter. */
public final class StreamsMarineSampling {
    private static final AtomicLong AUDIT_SAMPLES = new AtomicLong();
    private static final AtomicLong AUDIT_MISMATCHES = new AtomicLong();

    private StreamsMarineSampling() { }

    public static Map<String, Long> auditSnapshot() {
        return Map.of("samples", AUDIT_SAMPLES.get(), "mismatches", AUDIT_MISMATCHES.get());
    }

    public static boolean within16(int x, int z,
            Map<Long, LocalTerrainProvider.HeightmapData> tiles, LocalTerrainProvider provider) {
        // At scale 1 the classifier and stored height use the same elevation. Below
        // zero it always selects a vanilla ocean. Upsampling adds height noise AFTER
        // biome classification, so this equivalence must not be used at larger scales.
        boolean groundOnly = WorldScaleManager.getCurrentScale() == 1
                && LocalTerrainProvider.legacySampling()
                && Boolean.parseBoolean(System.getProperty("td.streams.groundMarine", "true"));
        boolean audit = groundOnly && Boolean.getBoolean("td.streams.groundMarineAudit");
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        for (int dz = -16; dz <= 16; dz += 8) for (int dx = -16; dx <= 16; dx += 8) {
            if (dx * dx + dz * dz > 256) continue;
            int sampleX = x + dx, sampleZ = z + dz;
            int tileX = sampleX >> shift, tileZ = sampleZ >> shift;
            long key = (tileX & 0xffffffffL) | ((tileZ & 0xffffffffL) << 32);
            int startX = tileX << shift, startZ = tileZ << shift;
            LocalTerrainProvider.HeightmapData data = tiles.get(key);
            if (data == null && !tiles.containsKey(key)) {
                data = groundOnly
                        ? provider.fetchGroundHeightmap(startZ, startX, startZ + tileSize, startX + tileSize)
                        : provider.fetchHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
                tiles.put(key, data);
            }
            int localX = sampleX - startX, localZ = sampleZ - startZ;
            boolean marine = belowSeaLevel(data, localX, localZ)
                    && (groundOnly || vanillaOcean(data, localX, localZ));
            if (audit) {
                var full = provider.fetchHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
                boolean original = belowSeaLevel(full, localX, localZ) && vanillaOcean(full, localX, localZ);
                AUDIT_SAMPLES.incrementAndGet();
                if (marine != original) {
                    AUDIT_MISMATCHES.incrementAndGet();
                    throw new IllegalStateException("TD marine sampling mismatch at " + sampleX + "," + sampleZ);
                }
            }
            if (marine) return true;
        }
        return false;
    }

    private static boolean belowSeaLevel(LocalTerrainProvider.HeightmapData data, int x, int z) {
        return data != null && data.heightmap != null && x >= 0 && z >= 0
                && x < data.width && z < data.height && data.heightmap[z][x] < 0;
    }

    private static boolean vanillaOcean(LocalTerrainProvider.HeightmapData data, int x, int z) {
        return data.biomeIds != null && EcologyShowcaseProfile.isVanillaOcean(data.biomeIds[z][x]);
    }
}
