package com.github.xandergos.terraindiffusionmc.world;

import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.world.level.biome.BiomeSource;

/** Recognizes TD settings through audited wrappers without bypassing their biome selection. */
public final class TerrainDiffusionBiomeSources {
    private static final String INJECTOR =
            "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";
    private static final ClassValue<Optional<Method>> ROOT_ACCESS = new ClassValue<>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            for (Class<?> parent = type; parent != null; parent = parent.getSuperclass()) {
                if (!parent.getName().equals(INJECTOR)) continue;
                try {
                    return Optional.of(parent.getMethod("rootDelegate"));
                } catch (NoSuchMethodException | SecurityException e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }
    };

    private TerrainDiffusionBiomeSources() { }

    /** For world identity/configuration only. Query biomes through the original source. */
    public static TerrainDiffusionBiomeSource find(BiomeSource source) {
        if (source instanceof TerrainDiffusionBiomeSource terrain) return terrain;
        if (source == null) return null;
        Optional<Method> accessor = ROOT_ACCESS.get(source.getClass());
        if (accessor.isEmpty()) return null;
        try {
            Object root = accessor.get().invoke(source);
            return root instanceof TerrainDiffusionBiomeSource terrain ? terrain : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static boolean isTerrainDiffusion(BiomeSource source) {
        return find(source) != null;
    }
}
