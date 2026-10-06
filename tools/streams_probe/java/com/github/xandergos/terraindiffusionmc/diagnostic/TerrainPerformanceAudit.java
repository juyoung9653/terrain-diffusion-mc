package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.google.gson.Gson;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded cold-preview query trace for baseline/candidate comparison. No world writes. */
public final class TerrainPerformanceAudit {
    private static final int[][] POINTS = {
            {-260,-4}, {-256,0}, {-244,12}, {-4,252}, {0,256}, {12,268}, {252,-256}, {256,-260}
    };

    public static String run(ServerLevel level) {
        List<Object> results = new ArrayList<>();
        for (String value : System.getProperty("td.streamsProbe.performanceScales", "1,2,3,6").split(",")) {
            int scale = Integer.parseInt(value.trim());
            try (var session = LocalTerrainProvider.createPreviewSession(level.getSeed(), scale)) {
                session.run(() -> {
                    var before = OnnxModel.runtimeMetrics();
                    long started = System.nanoTime();
                    List<Object> first = sample(level);
                    long coldNanos = System.nanoTime() - started;
                    var afterCold = OnnxModel.runtimeMetrics();
                    started = System.nanoTime();
                    List<Object> repeated = sample(level);
                    long warmNanos = System.nanoTime() - started;
                    if (!first.equals(repeated)) throw new IllegalStateException("Repeated query changed at scale " + scale);
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("scale", scale);
                    row.put("coldMs", coldNanos / 1_000_000.0);
                    row.put("warmMs", warmNanos / 1_000_000.0);
                    row.put("sessionAttempts", afterCold.sessionAttempts() - before.sessionAttempts());
                    row.put("sessionMs", (afterCold.sessionNanos() - before.sessionNanos()) / 1_000_000.0);
                    row.put("runAttempts", afterCold.runAttempts() - before.runAttempts());
                    row.put("runMs", (afterCold.runNanos() - before.runNanos()) / 1_000_000.0);
                    row.put("inputMs", (afterCold.inputNanos() - before.inputNanos()) / 1_000_000.0);
                    row.put("outputMs", (afterCold.outputNanos() - before.outputNanos()) / 1_000_000.0);
                    row.put("samples", first);
                    if (Boolean.getBoolean("td.streamsProbe.performanceParity")) {
                        verifyFullReference(level, first);
                        List<Object> land = sample(level, new int[][] {{1616,-32}, {1628,-20}, {1648,-16}, {-1452,-16}, {-1424,8}});
                        verifyFullReference(level, land);
                        row.put("landSamples", land);
                        row.put("fullTileParity", "passed");
                    }
                    try {
                        row.put("providerMetrics", LocalTerrainProvider.class.getMethod("performanceSnapshot")
                                .invoke(LocalTerrainProvider.getInstance()).toString());
                    } catch (ReflectiveOperationException unavailable) {
                        row.put("providerMetrics", "unavailable");
                    }
                    results.add(row);
                });
            }
        }
        return new Gson().toJson(Map.of("event", "terrain_performance_audit", "status", "passed",
                "legacySampling", LocalTerrainProvider.legacySampling(),
                "results", results, "onnxTotals", OnnxModel.runtimeMetrics()));
    }

    private static List<Object> sample(ServerLevel level) {
        return sample(level, POINTS);
    }

    private static List<Object> sample(ServerLevel level, int[][] points) {
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        var climate = level.getChunkSource().randomState().sampler();
        List<Object> result = new ArrayList<>();
        for (int[] point : points) {
            int x = point[0], z = point[1];
            double density = TerrainDiffusionDensityFunction.INSTANCE.compute(new DensityFunction.SinglePointContext(x, 64, z));
            var biome = source.getNoiseBiome(Math.floorDiv(x, 4), 16, Math.floorDiv(z, 4), climate);
            result.add(List.of(x, z, density, biome.unwrapKey().orElseThrow().location().toString()));
        }
        return result;
    }

    private static void verifyFullReference(ServerLevel level, List<Object> samples) {
        var provider = LocalTerrainProvider.getInstance();
        int tile = com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig.tileSize();
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        try {
            List<LocalTerrainProvider.HeightmapData> groundParts = new ArrayList<>(), biomeParts = new ArrayList<>();
            int side = LocalTerrainProvider.sampleTileSize();
            // Capture every partial result before reference reads can populate the full cache.
            for (Object raw : samples) {
                List<?> point = (List<?>) raw;
                int px = Math.floorDiv((Integer) point.get(0), side) * side;
                int pz = Math.floorDiv((Integer) point.get(1), side) * side;
                groundParts.add(provider.fetchGroundHeightmap(pz, px, pz + side, px + side));
                biomeParts.add(provider.fetchBiomeHeightmap(pz, px, pz + side, px + side));
            }
            var select = source.getClass().getDeclaredMethod("biomeFromTile", int.class, int.class, int.class,
                    int.class, int.class, LocalTerrainProvider.HeightmapData.class);
            select.setAccessible(true);
            int partIndex = 0;
            for (Object raw : samples) {
                List<?> sample = (List<?>) raw;
                int x = (Integer) sample.get(0), z = (Integer) sample.get(1);
                int sx = Math.floorDiv(x, tile) * tile, sz = Math.floorDiv(z, tile) * tile;
                var full = provider.fetchHeightmap(sz, sx, sz + tile, sx + tile);
                int px = Math.floorDiv(x, side) * side, pz = Math.floorDiv(z, side) * side;
                var ground = groundParts.get(partIndex);
                var biomes = biomeParts.get(partIndex++);
                for (int dz = 0; dz < side; dz++) for (int dx = 0; dx < side; dx++) {
                    int fx = px - sx + dx, fz = pz - sz + dz;
                    if (ground.heightmap[dz][dx] != full.heightmap[fz][fx]
                            || biomes.heightmap[dz][dx] != full.heightmap[fz][fx]
                            || biomes.biomeIds[dz][dx] != full.biomeIds[fz][fx]
                            || biomes.terralithBiomeIds[dz][dx] != full.terralithBiomeIds[fz][fx]
                            || biomes.ecology.terrainBiomeId(dx,dz) != full.ecology.terrainBiomeId(fx,fz)
                            || !biomes.ecology.sample(dx,dz).equals(full.ecology.sample(fx,fz))) {
                        throw new IllegalStateException("Partial/full cell mismatch at " + (px+dx) + "," + (pz+dz)
                                + " ground=" + ground.heightmap[dz][dx] + "/" + full.heightmap[fz][fx]
                                + " eco=" + biomes.ecology.sample(dx,dz) + "/" + full.ecology.sample(fx,fz));
                    }
                }
                double referenceDensity = com.github.xandergos.terraindiffusionmc.world.HeightConverter
                        .convertToMinecraftHeight(full.heightmap[z-sz][x-sx]) - 64.0;
                var referenceBiome = (net.minecraft.core.Holder<?>) select.invoke(source, x, 64, z, sx, sz, full);
                String key = referenceBiome.unwrapKey().orElseThrow().location().toString();
                if (!sample.get(2).equals(referenceDensity) || !sample.get(3).equals(key)) {
                    throw new IllegalStateException("Partial/full mismatch at " + x + "," + z
                            + ": actual=" + sample + " expected=" + referenceDensity + "," + key);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Full-tile comparison unavailable", failure);
        }
    }
}
