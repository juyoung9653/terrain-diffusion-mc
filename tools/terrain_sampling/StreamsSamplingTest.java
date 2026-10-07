package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsInferenceBinding;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsSamplerPriority;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import java.util.concurrent.CancellationException;

/** Production provider and sampler bridge with the deterministic pipeline fixture. */
public final class StreamsSamplingTest {
    public interface Sampler { int height(int x, int z); }

    public static void main(String[] args) throws Exception {
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();
        try {
            priorityScope();
            exactCursor(provider);
            binding(provider);
            closedScope(provider);
            System.out.println("PASS Streams sampling: exact cursor boundaries/scale/clear/eviction, scoped restoration, "
                    + "closed epochs, sampler demand propagation and original exception, no extra pipeline calls");
        } finally { LocalTerrainProvider.shutdownAll(); }
    }

    private static void priorityScope() throws Exception {
        check(StreamsSamplerPriority.current() == InferenceExecutor.Priority.BACKGROUND, "default stays background");
        StreamsSamplerPriority.run(0, () -> {
            check(StreamsSamplerPriority.current() == InferenceExecutor.Priority.FOREGROUND, "demanded priority");
            try {
                StreamsSamplerPriority.run(1, () -> { throw new IllegalArgumentException("expected"); });
                throw new AssertionError("exception swallowed");
            } catch (IllegalArgumentException expected) { }
            check(StreamsSamplerPriority.current() == InferenceExecutor.Priority.FOREGROUND, "nested failure restored");
        });
        check(StreamsSamplerPriority.current() == InferenceExecutor.Priority.BACKGROUND, "worker scope removed");
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> StreamsSamplerPriority.run(0, () -> {
                check(StreamsSamplerPriority.current() == InferenceExecutor.Priority.FOREGROUND, "captured task priority on worker");
            })).get();
            check(worker.submit(StreamsSamplerPriority::current).get() == InferenceExecutor.Priority.BACKGROUND,
                    "reused worker has no leaked urgency");
        } finally { worker.shutdownNow(); }
    }

    private static void exactCursor(LocalTerrainProvider provider) throws Exception {
        System.setProperty("td.inference.legacySampling", "true");
        LocalTerrainProvider.clearCache();
        check(LocalTerrainProvider.scopedGroundHeight(0, 0) == Integer.MIN_VALUE, "unscoped path unchanged");
        provider.withSamplingBinding(() -> {
            int expected = provider.fetchGroundHeightmap(-256, -256, 0, 0).heightmap[255][255];
            check(LocalTerrainProvider.scopedGroundHeight(-1, -1) == expected, "negative coordinate exact");
            provider.resetPerformanceMetrics();
            int before = WorldPipeline.calls.get();
            for (int y = -64; y < 1024; y++)
                check(LocalTerrainProvider.scopedGroundHeight(-1, -1) - y == expected - y, "every density Y exact");
            check(provider.performanceSnapshot().hits() == 0, "vertical scan bypasses repeated provider lookups");
            check(WorldPipeline.calls.get() == before, "cursor issues no extra pipeline queries");
            for (int x : new int[]{-257, -256, -1, 0, 255, 256}) {
                int sx = Math.floorDiv(x, 256) * 256;
                var tile = provider.fetchGroundHeightmap(0, sx, 256, sx + 256);
                check(LocalTerrainProvider.scopedGroundHeight(x, 13) == tile.heightmap[13][x - sx], "tile edge exact");
            }
            for (int scale = 2; scale <= 6; scale++) {
                WorldScaleManager.scale = scale;
                var tile = provider.fetchGroundHeightmap(0, 0, 256, 256);
                check(LocalTerrainProvider.scopedGroundHeight(0, 0) == tile.heightmap[0][0], "scale invalidates cursor");
            }
            WorldScaleManager.scale = 1;
            LocalTerrainProvider.scopedGroundHeight(0, 0);
            before = WorldPipeline.calls.get();
            LocalTerrainProvider.clearCache();
            LocalTerrainProvider.scopedGroundHeight(0, 0);
            check(WorldPipeline.calls.get() == before + 1, "clear invalidates cursor");
            int first = LocalTerrainProvider.scopedGroundHeight(0, 0);
            LocalTerrainProvider.init(71);
            check(LocalTerrainProvider.scopedGroundHeight(0, 0) != first, "seed mutation invalidates cursor");
            for (int i = 1; i < 75; i++) provider.fetchHeightmap(0, i * 256, 256, (i + 1) * 256);
            before = WorldPipeline.calls.get();
            LocalTerrainProvider.scopedGroundHeight(0, 0);
            check(WorldPipeline.calls.get() == before + 1, "evicted entry is not retained by cursor");
            System.setProperty("td.inference.legacySampling", "false");
            check(LocalTerrainProvider.scopedGroundHeight(0, 0) == Integer.MIN_VALUE, "partial path unchanged");
            System.setProperty("td.inference.legacySampling", "true");
            try (var preview = LocalTerrainProvider.createPreviewSession(44, 2)) {
                preview.run(() -> check(LocalTerrainProvider.scopedGroundHeight(0, 0) == Integer.MIN_VALUE,
                        "preview never reuses gameplay cursor"));
            }
            try {
                provider.withSamplingBinding(() -> { throw new IllegalStateException("nested"); });
            } catch (IllegalStateException expectedFailure) { }
            check(LocalTerrainProvider.scopedGroundHeight(0, 0) != Integer.MIN_VALUE, "nested scope restored");
            return null;
        });
        check(LocalTerrainProvider.scopedGroundHeight(0, 0) == Integer.MIN_VALUE, "cursor removed after call");
    }

    private static void binding(LocalTerrainProvider provider) throws Exception {
        var field = LocalTerrainProvider.class.getDeclaredField("REQUEST_PRIORITY");
        field.setAccessible(true);
        ThreadLocal<?> actualPriority = (ThreadLocal<?>) field.get(null);
        RuntimeException failure = new IllegalArgumentException("original sampler failure");
        Sampler delegate = (x, z) -> {
            check(LocalTerrainProvider.getInstance() == provider, "sampler remains world bound");
            check(actualPriority.get() == StreamsSamplerPriority.current(), "binding preserves task priority");
            if (x == 99) throw failure;
            return LocalTerrainProvider.scopedGroundHeight(x, z);
        };
        Sampler bound = (Sampler) StreamsInferenceBinding.bind(delegate, Sampler.class.getName(), provider);
        StreamsSamplerPriority.run(0, () -> check(bound.height(0, 0) != Integer.MIN_VALUE, "demanded sampler cursor installed"));
        StreamsSamplerPriority.run(1, () -> check(bound.height(0, 0) != Integer.MIN_VALUE, "background sampler cursor installed"));
        try { bound.height(99, 0); throw new AssertionError("failure swallowed"); }
        catch (RuntimeException expected) { check(expected == failure, "original exception unwrapped"); }
        check(actualPriority.get() == InferenceExecutor.Priority.FOREGROUND, "request priority restored after failure");
        check(LocalTerrainProvider.scopedGroundHeight(0, 0) == Integer.MIN_VALUE, "failure clears cursor scope");
    }

    private static void closedScope(LocalTerrainProvider provider) throws Exception {
        provider.withSamplingBinding(() -> {
            LocalTerrainProvider.scopedGroundHeight(0, 0);
            LocalTerrainProvider.shutdownGameplay();
            try { LocalTerrainProvider.scopedGroundHeight(0, 0); throw new AssertionError("closed cursor served tile"); }
            catch (CancellationException expected) { }
            return null;
        });
        LocalTerrainProvider.beginGameplay();
        try { provider.withSamplingBinding(() -> 1); throw new AssertionError("old provider rebound"); }
        catch (CancellationException expected) { }
        LocalTerrainProvider.getInstance().withSamplingBinding(() -> {
            check(LocalTerrainProvider.scopedGroundHeight(0, 0) != Integer.MIN_VALUE, "new epoch cursor valid");
            return null;
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
