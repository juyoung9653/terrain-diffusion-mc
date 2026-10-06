package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.google.gson.Gson;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Opt-in bounded parity probe for the actual /locate biome source method; server thread only. */
public final class LocateBiomeAudit {
    private static final String CACHE_PROPERTY = "td.locate.biomeCache";
    private static final int RADIUS = 128;

    public static String run(ServerLevel level) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("LocateBiomeAudit must run on the server thread");
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        if (source.getClass() != TerrainDiffusionBiomeSource.class)
            throw new IllegalStateException("LocateBiomeAudit requires the exact terrain diffusion biome source");
        BlockPos origin = new BlockPos(-1, 65, -1);
        var sampler = level.getChunkSource().randomState().sampler();
        // Deliberately rounds negative X/Z and non-quart-aligned Y like Minecraft's command.
        Holder<Biome> local = source.getNoiseBiome(origin.getX() >> 2, origin.getY() >> 2,
                origin.getZ() >> 2, sampler);
        Holder<Biome> nonlocal = source.possibleBiomes().stream().filter(b -> !b.equals(local))
                .sorted(Comparator.comparing((Holder<Biome> b) -> !b.getRegisteredName().contains("cave"))
                        .thenComparing(Holder::getRegisteredName))
                .findFirst().orElseThrow();
        List<Object> cases = new ArrayList<>();
        String previous = System.getProperty(CACHE_PROPERTY);
        try {
            cases.add(compare(level, origin, "local", local.getRegisteredName(), local::equals, true));
            cases.add(compare(level, origin, "nonlocal_prefer_cave", nonlocal.getRegisteredName(), nonlocal::equals, false));
            cases.add(compare(level, origin, "no_possible_match", "none", b -> false, false));
        } finally {
            if (previous == null) System.clearProperty(CACHE_PROPERTY);
            else System.setProperty(CACHE_PROPERTY, previous);
        }
        return new Gson().toJson(Map.of("event", "locate_biome_audit", "status", "passed",
                "radius", RADIUS, "horizontalStep", 32, "verticalStep", 64,
                "origin", List.of(origin.getX(), origin.getY(), origin.getZ()),
                "sampleTileSize", LocalTerrainProvider.sampleTileSize(), "cases", cases,
                "measurementNote", "First rollback pass may load uncached tiles; later passes reuse them. "
                        + "Origin was sampled before timing. These are ordered first/repeated measurements, "
                        + "not a fair cold A/B latency comparison. Metrics may include concurrent provider activity."));
    }

    private static Map<String, Object> compare(ServerLevel level, BlockPos origin, String label,
            String target, Predicate<Holder<Biome>> predicate, boolean requireOrigin) {
        List<Object> runs = new ArrayList<>();
        Pair<BlockPos, Holder<Biome>> expected = null;
        for (int pass = 0; pass < 4; pass++) {
            boolean cached = (pass & 1) != 0;
            System.setProperty(CACHE_PROPERTY, Boolean.toString(cached));
            var provider = LocalTerrainProvider.getInstance();
            var before = provider.performanceSnapshot();
            var onnxBefore = OnnxModel.runtimeMetrics();
            long started = System.nanoTime();
            var actual = level.getChunkSource().getGenerator().getBiomeSource().findClosestBiome3d(
                    origin, RADIUS, 32, 64, predicate, level.getChunkSource().randomState().sampler(), level);
            long elapsed = System.nanoTime() - started;
            var after = provider.performanceSnapshot();
            var onnxAfter = OnnxModel.runtimeMetrics();
            if (pass == 0) expected = actual;
            else if (!Objects.equals(expected, actual))
                throw new IllegalStateException("Locate parity failed for " + label + " pass " + pass
                        + ": expected=" + expected + ", actual=" + actual);
            if (requireOrigin && (actual == null || !origin.equals(actual.getFirst())))
                throw new IllegalStateException("Local biome did not resolve to the exact origin: " + actual);
            if (label.equals("no_possible_match") && actual != null)
                throw new IllegalStateException("Empty predicate unexpectedly matched");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phase", pass == 0 ? "first_rollback" : pass == 1 ? "first_cached_after_rollback"
                    : pass == 2 ? "repeated_rollback" : "repeated_cached");
            row.put("cached", cached);
            row.put("elapsedMs", elapsed / 1_000_000.0);
            row.put("providerHits", after.hits() - before.hits());
            row.put("providerMisses", after.misses() - before.misses());
            row.put("providerPendingJoins", after.pendingJoins() - before.pendingJoins());
            row.put("computedWindows", after.computedWindows() - before.computedWindows());
            row.put("onnxRuns", onnxAfter.runAttempts() - onnxBefore.runAttempts());
            row.put("onnxRunMs", (onnxAfter.runNanos() - onnxBefore.runNanos()) / 1_000_000.0);
            row.put("result", actual == null ? null : Map.of("x", actual.getFirst().getX(),
                    "y", actual.getFirst().getY(), "z", actual.getFirst().getZ(),
                    "biome", actual.getSecond().getRegisteredName()));
            runs.add(row);
        }
        return Map.of("case", label, "target", target, "parity", "passed", "found", expected != null, "runs", runs);
    }
}
