package com.github.xandergos.terraindiffusionmc.world;

import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.world.level.biome.BiomeSource;

/** Recognition only: never unwrap the source used for actual biome generation. */
public final class RingBiomeSourcePolicy {
    private static final String INJECTOR =
            "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";
    private static final ClassValue<Optional<Method>> ROOT_ACCESS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            for (Class<?> parent = type; parent != null; parent = parent.getSuperclass()) {
                if (!parent.getName().equals(INJECTOR)) continue;
                try {
                    // Public accessor in Lithostitched 1.8.1; no mandatory dependency.
                    return Optional.of(parent.getMethod("rootDelegate"));
                } catch (NoSuchMethodException | SecurityException e) {
                    warn(e);
                    break;
                }
            }
            return Optional.empty();
        }
    };

    private RingBiomeSourcePolicy() {}

    public static boolean skipRelocation(BiomeSource source) {
        if (source instanceof TerrainDiffusionBiomeSource) return true;
        Optional<Method> access = ROOT_ACCESS.get(source.getClass());
        if (access.isEmpty()) return false;
        try {
            return access.get().invoke(source) instanceof TerrainDiffusionBiomeSource;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
            return false; // Unknown wrappers retain their normal placement behavior.
        }
    }

    private static void warn(Exception e) {
        System.getLogger(RingBiomeSourcePolicy.class.getName()).log(System.Logger.Level.WARNING,
                "Cannot inspect Lithostitched biome source; retaining normal ring relocation", e);
    }
}
