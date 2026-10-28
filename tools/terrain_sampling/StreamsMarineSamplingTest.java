package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsMarineSampling;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import java.util.HashMap;
import java.util.Map;

/** Real classifier and provider; deterministic pipeline inputs, no native runtime. */
public final class StreamsMarineSamplingTest {
    public static void main(String[] args) {
        try {
            classifierInvariant();
            WorldScaleManager.scale = 1;
            WorldScaleManager.fastGroundSampling = true;
            System.setProperty("td.inference.legacySampling", "true");
            System.setProperty("td.streams.groundMarine", "true");
            var provider = LocalTerrainProvider.getInstance();
            LocalTerrainProvider.init(0);
            int[][] points = {{-257,-257},{-256,-256},{-1,-1},{0,0},{255,255},{256,256},{4095,-4096}};
            Map<Long, LocalTerrainProvider.HeightmapData> tiles = new HashMap<>();
            int climateBefore = WorldPipeline.climateCalls.get();
            boolean[] fast = new boolean[points.length];
            for (int i = 0; i < points.length; i++)
                fast[i] = StreamsMarineSampling.within16(points[i][0], points[i][1], tiles, provider);
            check(WorldPipeline.climateCalls.get() == climateBefore, "fast marine path must not compute climate");
            check(WorldPipeline.cropCalls.get() > 0, "actual elevation crop path exercised");
            System.setProperty("td.streams.groundMarine", "false");
            tiles.clear();
            for (int i = 0; i < points.length; i++)
                check(fast[i] == StreamsMarineSampling.within16(points[i][0], points[i][1], tiles, provider),
                        "marine result differs across signed tile boundary " + i);
            check(WorldPipeline.climateCalls.get() > climateBefore, "original full path exercised");

            System.setProperty("td.streams.groundMarine", "true");
            System.setProperty("td.streams.groundMarineAudit", "true");
            LocalTerrainProvider.clearCache();
            tiles.clear();
            for (int[] point : points) StreamsMarineSampling.within16(point[0], point[1], tiles, provider);
            var audit = StreamsMarineSampling.auditSnapshot();
            check(audit.get("samples") > 0 && audit.get("mismatches") == 0, "dual-path audit must run and match");

            for (int scale : new int[]{2, 6}) {
                WorldScaleManager.scale = scale;
                LocalTerrainProvider.clearCache();
                climateBefore = WorldPipeline.climateCalls.get();
                StreamsMarineSampling.within16(-1, -1, new HashMap<>(), provider);
                check(WorldPipeline.climateCalls.get() > climateBefore, "upsampled path must retain full biome lookup");
                check(StreamsMarineSampling.auditSnapshot().equals(audit), "upsampled path must not use fast audit");
            }
            WorldScaleManager.scale = 1;
            System.setProperty("td.inference.legacySampling", "false");
            LocalTerrainProvider.clearCache();
            climateBefore = WorldPipeline.climateCalls.get();
            StreamsMarineSampling.within16(-1, -1, new HashMap<>(), provider);
            check(WorldPipeline.climateCalls.get() > climateBefore, "nonlegacy path must retain full biome lookup");
            check(StreamsMarineSampling.auditSnapshot().equals(audit), "nonlegacy path must not use fast audit");
            System.out.println("PASS Streams marine sampling: classifier sign invariant, signed tile boundaries, "
                    + "no fast-path climate inference, original result parity, scale/nonlegacy fallback, dual-path audit " + audit);
        } finally { LocalTerrainProvider.shutdownAll(); }
    }

    private static void classifierInvariant() {
        float[] elevations = {-10000, -100, -1, -.001f, -Float.MIN_VALUE, -0f, 0f, Float.MIN_VALUE, .1f, 1, 10000};
        for (float temperature : new float[]{-40, -10, 0, 15, 40})
            for (float rain : new float[]{0, 200, 800, 4000})
                for (float elevation : elevations) {
                    float[] padded = new float[9];
                    java.util.Arrays.fill(padded, elevation);
                    short biome = BiomeClassifier.classify(new float[]{elevation},
                            new float[]{temperature, 7, rain, .3f, 0}, -257, 255, padded, 1, 1, 16)[0];
                    check(EcologyShowcaseProfile.isVanillaOcean(biome) == (elevation < 0),
                            "classifier ocean sign invariant: " + elevation + ", " + temperature + ", " + rain);
                }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
