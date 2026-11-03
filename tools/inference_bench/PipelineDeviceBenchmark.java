package com.github.xandergos.terraindiffusionmc.pipeline;

import com.google.gson.GsonBuilder;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;

/** Isolated elevation pipeline benchmark, not Minecraft chunk throughput. */
public final class PipelineDeviceBenchmark {
    private static final OperatingSystemMXBean OS =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final long SEED = 1018612869571620377L;

    private static float[] query(WorldPipeline pipeline, int i, int j) {
        return pipeline.getElevationCrop(i - 218, j - 218, i + 474, j + 474,
                i, j, i + 256, j + 256);
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        if (Files.exists(output.resolve("result.json"))) throw new IllegalStateException("existing result");
        long setupStart = System.nanoTime();
        try (WorldPipeline pipeline = new WorldPipeline(SEED + 1)) {
            long setupNanos = System.nanoTime() - setupStart;
            String expected = System.getProperty("td.bench.device").equals("cpu") ? "CPU" : "CUDA";
            long warmupStart = System.nanoTime();
            query(pipeline, -1024, -240);
            long warmupNanos = System.nanoTime() - warmupStart;
            // Offloaded models select their provider lazily on the first query.
            if (!expected.equals(OnnxModel.getResolvedInferenceProvider()))
                throw new IllegalStateException("unexpected provider " + OnnxModel.getResolvedInferenceProvider());
            pipeline.setSeed(SEED); // Clear all terrain caches; keep warmed native sessions.
            var rows = new ArrayList<Map<String, Object>>();
            int[][] coords = {{-1024, -240}, {-1024, 16}};
            for (int k = 0; k < coords.length; k++) {
                var before = OnnxModel.modelRunMetrics();
                var runtimeBefore = OnnxModel.runtimeMetrics();
                long windowsBefore = pipeline.getTotalComputedWindowCount();
                long cpuBefore = OS.getProcessCpuTime(), start = System.nanoTime();
                float[] elevation = query(pipeline, coords[k][0], coords[k][1]);
                long elapsed = System.nanoTime() - start, cpu = OS.getProcessCpuTime() - cpuBefore;
                if (elevation.length != 256 * 256) throw new IllegalStateException("wrong crop size");
                for (float value : elevation) if (!Float.isFinite(value)) throw new IllegalStateException("nonfinite elevation");
                var stages = new LinkedHashMap<String, Object>();
                OnnxModel.modelRunMetrics().forEach((key, value) -> {
                    var previous = before.getOrDefault(key, new OnnxModel.RunMetrics(0, 0));
                    stages.put(key, Map.of("calls", value.calls() - previous.calls(),
                            "seconds", (value.nanos() - previous.nanos()) / 1e9));
                });
                var row = new LinkedHashMap<String, Object>();
                row.put("id", k == 0 ? "cold-terrain" : "adjacent-terrain");
                row.put("i", coords[k][0]); row.put("j", coords[k][1]);
                row.put("seconds", elapsed / 1e9); row.put("processCpuSeconds", cpu / 1e9);
                row.put("averageUtilizedLogicalCores", (double) cpu / elapsed);
                row.put("computedWindows", pipeline.getTotalComputedWindowCount() - windowsBefore);
                var runtimeAfter = OnnxModel.runtimeMetrics();
                row.put("sessionCreations", runtimeAfter.sessionAttempts() - runtimeBefore.sessionAttempts());
                row.put("sessionCreationSeconds", (runtimeAfter.sessionNanos() - runtimeBefore.sessionNanos()) / 1e9);
                row.put("modelRuns", stages);
                rows.add(row);
                ByteBuffer bytes = ByteBuffer.allocate(elevation.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                bytes.asFloatBuffer().put(elevation);
                Files.write(output.resolve(k + ".elevation.bin"), bytes.array());
                System.out.println(new GsonBuilder().create().toJson(row));
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("success", true); result.put("kind", "isolated_elevation_pipeline_device_benchmark");
            result.put("provider", OnnxModel.getResolvedInferenceProvider());
            result.put("cpuIntraOpThreads", Integer.getInteger("td.bench.threads", 0));
            result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            result.put("seed", SEED); result.put("precision", "fp32");
            result.put("offloadModels", com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig.offloadModels());
            result.put("setupSeconds", setupNanos / 1e9);
            result.put("warmupSeconds", warmupNanos / 1e9); result.put("queries", rows);
            result.put("scope", "256x256 elevation crops with fixed 218-pixel reference halo; excludes Minecraft blocks, biomes, rivers, structures and disk chunks");
            Files.writeString(output.resolve("result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        }
    }
}
