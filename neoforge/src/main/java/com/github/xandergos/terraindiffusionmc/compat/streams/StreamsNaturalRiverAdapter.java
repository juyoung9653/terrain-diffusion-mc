package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.LoggerFactory;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;

/** World-scoped native boundary. All geometry policy belongs to TD, not a patched Streams JAR. */
public final class StreamsNaturalRiverAdapter {
    private static final Map<Object, Integer> CONFIGS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, ServerLevel> TERRAIN_LEVELS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, LocalTerrainProvider> PROVIDERS = Collections.synchronizedMap(new IdentityHashMap<>());
    private StreamsNaturalRiverAdapter() { }

    public static Object configure(WorldGenLevel level, Object nativeConfig) {
        if (!StreamsScalePolicy.appliesTo(level) || !WorldNaturalRiverState.enabled(level.getLevel())) return nativeConfig;
        int version = WorldNaturalRiverState.version(level.getLevel());
        Object config = StreamsNaturalConfig.apply(nativeConfig, StreamsScalePolicy.activeScale(level), version);
        CONFIGS.put(config, version);
        PROVIDERS.put(config, LocalTerrainProvider.getInstance());
        if (version >= 6) TERRAIN_LEVELS.put(config, level.getLevel());
        if (version >= 3) LocalTerrainProvider.setLargeRiverPlanningCache(true);
        LoggerFactory.getLogger("TerrainDiffusion/NaturalRivers").info(
                "Natural river profile v{}: accumulation {}, coastal chain {} cells, terrain confinement, asymmetric banks",
                version, StreamsRecordAccess.read(config, "minRiverAccumulation"),
                StreamsRecordAccess.read(config, "minSeaChainNodes"));
        return config;
    }

    public static boolean active(Object config) { return CONFIGS.containsKey(config); }
    public static int version(Object config) { return CONFIGS.getOrDefault(config, 0); }

    /** Uncarved TD density boundary, independent of native grade calibration/filtering. */
    public static com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground originalTerrain(Object config) {
        ServerLevel level = TERRAIN_LEVELS.get(config);
        if (level == null) throw new IllegalStateException("Missing v6 terrain binding");
        var generator = level.getChunkSource().getGenerator();
        var randomState = level.getChunkSource().randomState();
        LocalTerrainProvider provider = provider(config);
        return (x, z) -> {
            try {
                return provider.withBinding(() -> generator.getBaseHeight(x, z,
                        Heightmap.Types.OCEAN_FLOOR_WG, level, randomState));
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Streams terrain sampling failed", failure); }
        };
    }

    /** Per-build bounded TD ocean query. Heightmap coordinates are Z,X, matching LocalTerrainProvider. */
    static boolean tdMarineWithin16(int x, int z, Map<Long, LocalTerrainProvider.HeightmapData> tiles) {
        return tdMarineWithin16(x, z, tiles, LocalTerrainProvider.getInstance());
    }

    private static boolean tdMarineWithin16(int x, int z,
            Map<Long, LocalTerrainProvider.HeightmapData> tiles, LocalTerrainProvider provider) {
        return StreamsMarineSampling.within16(x, z, tiles, provider);
    }

    /** Builds the production marine predicate around one bounded TD tile cache. */
    public static Object selectWatersheds(Object network, Object domain, Object terrain, Object config) {
        try {
            Class<?> sampler = Class.forName("dev.streamsreflowing.core.terrain.TerrainSampler", false,
                    network.getClass().getClassLoader());
            java.lang.reflect.Method waterClass = sampler.getMethod(
                    Streams2162NetworkAccess.applies(network) ? "allRightsReserved1" : "waterBiomeClass",
                    int.class, int.class);
            Map<Long, LocalTerrainProvider.HeightmapData> tiles = new java.util.HashMap<>();
            LocalTerrainProvider provider = provider(config);
            java.util.function.BiPredicate<Integer, Integer> marine = (x, z) -> {
                try {
                    return ((Number) waterClass.invoke(terrain, x, z)).intValue() == 2
                            || tdMarineWithin16(x, z, tiles, provider);
                } catch (ReflectiveOperationException failure) {
                    if (Streams2162NetworkAccess.applies(network)) {
                        throw new IllegalStateException("Streams 2.16.2 marine sampling failed", failure);
                    }
                    return false;
                }
            };
            Object selected = StreamsWatershedNetwork.select(network, domain, terrain, config, marine);
            return version(config) >= 4 ? StreamsHierarchyNetwork.select(selected, domain, config) : selected;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (Streams2162NetworkAccess.applies(network)) {
                throw new IllegalStateException("Streams 2.16.2 natural river profile could not be applied", failure);
            }
            LoggerFactory.getLogger("TerrainDiffusion/NaturalRivers").warn(
                    "Natural river profile v{} network adaptation failed open; preserving native network", version(config), failure);
            return network;
        }
    }
    public static Path storePath(Path nativePath, Object config) {
        Integer version = CONFIGS.get(config);
        return version != null ? nativePath.resolve("td-natural-v" + version) : nativePath;
    }
    public static void clear() {
        Streams2171NetworkAdapter.resetCounts();
        StreamsShortRiverTunnels.clear();
        java.util.Set<LocalTerrainProvider> providers;
        synchronized (PROVIDERS) {
            providers = new java.util.HashSet<>(PROVIDERS.values());
        }
        for (LocalTerrainProvider provider : providers) {
            LoggerFactory.getLogger("TerrainDiffusion/NaturalRivers").info(
                    "Terrain cache before river cleanup (before capacity shrink): {}",
                    provider.performanceSnapshot());
        }
        CONFIGS.clear();
        TERRAIN_LEVELS.clear();
        PROVIDERS.clear();
        LocalTerrainProvider.setLargeRiverPlanningCache(false);
    }

    private static LocalTerrainProvider provider(Object config) {
        LocalTerrainProvider provider = PROVIDERS.get(config);
        if (provider == null) throw new java.util.concurrent.CancellationException("Streams world has stopped");
        return provider;
    }
}

