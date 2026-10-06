package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** Native RiverEngine workers can outlive their server; keep sampler calls on their original epoch. */
public final class StreamsInferenceBinding {
    private StreamsInferenceBinding() { }

    public static Object bind(Object sampler, String interfaceName, LocalTerrainProvider provider) {
        try {
            Class<?> contract = Class.forName(interfaceName, false, sampler.getClass().getClassLoader());
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
                        return provider.withBinding(() -> provider.withPriority(
                                com.github.xandergos.terraindiffusionmc.pipeline.InferenceExecutor.Priority.BACKGROUND, () -> {
                        try { return method.invoke(sampler, args); }
                        catch (InvocationTargetException failed) {
                            Throwable cause = failed.getCause();
                            if (cause instanceof Error error) throw error;
                            if (cause instanceof RuntimeException runtime) throw runtime;
                            throw new IllegalStateException("Streams terrain sampler failed", cause);
                        }
                        }));
                    });
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Audited Streams sampler contract is unavailable", failure);
        }
    }
}
