package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsInferenceBinding;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsSamplerPriority;
import dev.streamsreflowing.core.terrain.CachingTerrainSampler;
import dev.streamsreflowing.core.terrain.TerrainSampler;
import java.lang.reflect.Field;
import net.neoforged.fml.loading.FMLLoader;

/** Contracts the audited 2.15.2 exact scalar fallback used by LowResGrid batching. */
public final class StreamsBatchBindingTest {
    public static void main(String[] args) throws Exception {
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();
        try {
            System.setProperty("td.inference.legacySampling", "true");
            System.setProperty("td.streams.batchSampling", "true");
            enabledBatchUsesExactDelegate(provider);
            optOutAndVersionGate(provider);
            System.out.println("PASS Streams batch binding: audited wrapper gate, exact scalar fallback, "
                    + "priority/cursor restoration and opt-out");
        } finally {
            System.clearProperty("td.streams.batchSampling");
            LocalTerrainProvider.shutdownAll();
        }
    }

    private static void enabledBatchUsesExactDelegate(LocalTerrainProvider provider) throws Exception {
        Field priorityField = LocalTerrainProvider.class.getDeclaredField("REQUEST_PRIORITY");
        priorityField.setAccessible(true);
        ThreadLocal<?> actualPriority = (ThreadLocal<?>) priorityField.get(null);
        int[] xs = {-513, -1, 0, 127, 255, 256, 777};
        int[] zs = {511, -1, 0, 64, 255, 256, -777};
        TerrainSampler scalar = new TerrainSampler() {
            @Override public double height(int x, int z) {
                check(actualPriority.get() == StreamsSamplerPriority.current(), "priority survives native scalar");
                int height = LocalTerrainProvider.scopedGroundHeight(x, z);
                check(height != Integer.MIN_VALUE, "one binding covers native scalar");
                return height + x * .0001d + z * .00001d;
            }
            @Override public boolean batchCapable() { return false; }
            @Override public void heightsBatch(int[] x, int[] z, double[] out, int count) {
                for (int i = 0; i < count; i++) out[i] = height(x[i], z[i]);
            }
        };
        CachingTerrainSampler nativeWrapper = new CachingTerrainSampler(scalar);
        TerrainSampler bound = (TerrainSampler) StreamsInferenceBinding.bind(nativeWrapper,
                TerrainSampler.class.getName(), provider);
        check(!nativeWrapper.batchCapable(), "native delegate deliberately reports no batch support");
        check(bound.batchCapable(), "only outer audited binding advertises exact fallback");
        double[] expected = new double[xs.length], actual = new double[xs.length];
        StreamsSamplerPriority.run(0, () -> {
            for (int i = 0; i < xs.length; i++) expected[i] = bound.height(xs[i], zs[i]);
            bound.heightsBatch(xs, zs, actual, actual.length);
        });
        check(nativeWrapper.batchCalls == 1, "forwarded native batch fallback exactly once");
        for (int i = 0; i < xs.length; i++)
            check(Double.doubleToLongBits(expected[i]) == Double.doubleToLongBits(actual[i]),
                    "batch retains native scalar result at " + i);
        check(LocalTerrainProvider.scopedGroundHeight(0, 0) == Integer.MIN_VALUE,
                "batch binding restores cursor after completion");
        check(actualPriority.get() == InferenceExecutor.Priority.FOREGROUND,
                "batch binding restores prior request priority");
        TerrainSampler failing = new TerrainSampler() {
            @Override public double height(int x, int z) {
                if (x == 99) throw new IllegalArgumentException("native scalar failure");
                return LocalTerrainProvider.scopedGroundHeight(x, z);
            }
            @Override public boolean batchCapable() { return false; }
            @Override public void heightsBatch(int[] x, int[] z, double[] out, int count) {
                for (int i = 0; i < count; i++) out[i] = height(x[i], z[i]);
            }
        };
        TerrainSampler failingBound = (TerrainSampler) StreamsInferenceBinding.bind(
                new CachingTerrainSampler(failing), TerrainSampler.class.getName(), provider);
        boolean failed = false;
        try { failingBound.heightsBatch(new int[]{0, 99}, new int[]{0, 0}, new double[2], 2); }
        catch (IllegalArgumentException expectedFailure) {
            failed = true;
            check("native scalar failure".equals(expectedFailure.getMessage()), "original batch failure unwrapped");
        }
        check(failed, "native batch failure propagated");
        check(LocalTerrainProvider.scopedGroundHeight(0, 0) == Integer.MIN_VALUE,
                "failed batch restores cursor lifecycle");
    }

    private static void optOutAndVersionGate(LocalTerrainProvider provider) {
        TerrainSampler scalar = new TerrainSampler() {
            @Override public double height(int x, int z) { return x * 31d + z; }
            @Override public boolean batchCapable() { return false; }
            @Override public void heightsBatch(int[] x, int[] z, double[] out, int count) {
                for (int i = 0; i < count; i++) out[i] = height(x[i], z[i]);
            }
        };
        System.setProperty("td.streams.batchSampling", "false");
        TerrainSampler optedOut = (TerrainSampler) StreamsInferenceBinding.bind(new CachingTerrainSampler(scalar),
                TerrainSampler.class.getName(), provider);
        check(!optedOut.batchCapable(), "JVM opt-out preserves native capability");
        System.clearProperty("td.streams.batchSampling");
        TerrainSampler defaultBinding = (TerrainSampler) StreamsInferenceBinding.bind(new CachingTerrainSampler(scalar),
                TerrainSampler.class.getName(), provider);
        check(!defaultBinding.batchCapable(), "unproven batch optimization is opt-in by default");
        System.setProperty("td.streams.batchSampling", "true");
        FMLLoader.setMods(java.util.List.of(new FMLLoader.ModInfo("streamsreflowing", "2.13.8")));
        TerrainSampler oldVersion = (TerrainSampler) StreamsInferenceBinding.bind(new CachingTerrainSampler(scalar),
                TerrainSampler.class.getName(), provider);
        check(!oldVersion.batchCapable(), "only audited 2.15.2 exposes batch fallback");
        FMLLoader.setMods(java.util.List.of(new FMLLoader.ModInfo("streamsreflowing", "2.15.2")));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
