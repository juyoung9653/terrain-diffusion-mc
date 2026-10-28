package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Native RiverEngine workers can outlive their server; keep sampler calls on their original epoch. */
public final class StreamsInferenceBinding {
    private static final String TERRAIN_SAMPLER = "dev.streamsreflowing.core.terrain.TerrainSampler";
    private static final String CACHING_TERRAIN_SAMPLER =
            "dev.streamsreflowing.core.terrain.CachingTerrainSampler";

    private StreamsInferenceBinding() { }

    public static Object bind(Object sampler, String interfaceName, LocalTerrainProvider provider) {
        try {
            Class<?> contract = Class.forName(interfaceName, false, sampler.getClass().getClassLoader());
            boolean exactBatchFallback = supportsAuditedBatchFallback(sampler, interfaceName, contract);
            return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "World-bound " + interfaceName;
                                default -> throw new IllegalStateException("Unknown Object method: " + method);
                            };
                        }
                        // In 2.15.2 CachingTerrainSampler forwards this to its engine world
                        // sampler, whose audited non-batch branch loops the same scalar height
                        // method. Advertising outer capability keeps one TD scope around that
                        // native chain; do not synthesize any heights here.
                        if (exactBatchFallback && method.getName().equals("batchCapable")
                                && method.getParameterCount() == 0
                                && method.getReturnType() == boolean.class) return true;
                        var priority = StreamsSamplerPriority.current();
                        var origin = priority == com.github.xandergos.terraindiffusionmc.pipeline.InferenceExecutor.Priority.FOREGROUND
                                ? LocalTerrainProvider.RequestOrigin.STREAMS_DEMAND
                                : LocalTerrainProvider.RequestOrigin.STREAMS_PREFETCH;
                        return provider.withRequestOrigin(origin, () -> provider.withSamplingBinding(() -> provider.withPriority(
                                priority, () -> {
                        try { return method.invoke(sampler, args); }
                        catch (InvocationTargetException failed) {
                            Throwable cause = failed.getCause();
                            if (cause instanceof Error error) throw error;
                            if (cause instanceof RuntimeException runtime) throw runtime;
                            throw new IllegalStateException("Streams terrain sampler failed", cause);
                        }
                        })));
                    });
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Audited Streams sampler contract is unavailable", failure);
        }
    }

    private static boolean supportsAuditedBatchFallback(Object sampler, String interfaceName,
                                                        Class<?> contract) {
        // Experimental only: matched full-pack repetitions did not establish a
        // throughput benefit over native scalar admission.
        if (!Boolean.parseBoolean(System.getProperty("td.streams.batchSampling", "false"))
                || !TERRAIN_SAMPLER.equals(interfaceName)
                || !CACHING_TERRAIN_SAMPLER.equals(sampler.getClass().getName())
                || !isStreams2152()) return false;
        try {
            Method batchCapable = contract.getMethod("batchCapable");
            Method heightsBatch = contract.getMethod("heightsBatch",
                    int[].class, int[].class, double[].class, int.class);
            Method nativeBatchCapable = sampler.getClass().getDeclaredMethod("batchCapable");
            Method nativeHeightsBatch = sampler.getClass().getDeclaredMethod("heightsBatch",
                    int[].class, int[].class, double[].class, int.class);
            return batchCapable.getReturnType() == boolean.class
                    && heightsBatch.getReturnType() == void.class
                    && nativeBatchCapable.getReturnType() == boolean.class
                    && nativeHeightsBatch.getReturnType() == void.class;
        } catch (ReflectiveOperationException incompatible) {
            return false;
        }
    }

    private static boolean isStreams2152() {
        try {
            Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader", false,
                    StreamsInferenceBinding.class.getClassLoader());
            Object loading = loader.getMethod("getLoadingModList").invoke(null);
            if (loading == null) return false;
            Object mods = loading.getClass().getMethod("getMods").invoke(loading);
            if (!(mods instanceof Iterable<?> iterable)) return false;
            for (Object mod : iterable) {
                String id = String.valueOf(mod.getClass().getMethod("getModId").invoke(mod));
                String version = String.valueOf(mod.getClass().getMethod("getVersion").invoke(mod));
                if ("streamsreflowing".equals(id) && "2.15.2".equals(version)) return true;
            }
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
        return false;
    }
}
