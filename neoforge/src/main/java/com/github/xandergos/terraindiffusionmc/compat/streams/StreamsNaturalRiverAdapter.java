package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.LoggerFactory;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;

/** World-scoped native boundary. All geometry policy belongs to TD, not a patched Streams JAR. */
public final class StreamsNaturalRiverAdapter {
    private static final Map<Object, Integer> CONFIGS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, ServerLevel> TERRAIN_LEVELS = Collections.synchronizedMap(new IdentityHashMap<>());
    private StreamsNaturalRiverAdapter() { }

    public static Object configure(WorldGenLevel level, Object nativeConfig) {
        if (!StreamsScalePolicy.appliesTo(level) || !WorldNaturalRiverState.enabled(level.getLevel())) return nativeConfig;
        int version = WorldNaturalRiverState.version(level.getLevel());
        Object config = StreamsNaturalConfig.apply(nativeConfig, StreamsScalePolicy.activeScale(level), version);
        CONFIGS.put(config, version);
        if (version == 6) TERRAIN_LEVELS.put(config, level.getLevel());
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
    static com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground originalTerrain(Object config) {
        ServerLevel level = TERRAIN_LEVELS.get(config);
        if (level == null) throw new IllegalStateException("Missing v6 terrain binding");
        var generator = level.getChunkSource().getGenerator();
        var randomState = level.getChunkSource().randomState();
        return (x, z) -> generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
    }

    /** Per-build bounded TD ocean query. Heightmap coordinates are Z,X, matching LocalTerrainProvider. */
    static boolean tdMarineWithin16(int x, int z, Map<Long, LocalTerrainProvider.HeightmapData> tiles) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        for (int dz = -16; dz <= 16; dz += 8) for (int dx = -16; dx <= 16; dx += 8) {
            if (dx * dx + dz * dz > 256) continue;
            int sampleX = x + dx, sampleZ = z + dz;
            int tileX = sampleX >> shift, tileZ = sampleZ >> shift;
            long key = ChunkPos.asLong(tileX, tileZ);
            LocalTerrainProvider.HeightmapData data = tiles.get(key);
            if (data == null && !tiles.containsKey(key)) {
                int startX = tileX << shift, startZ = tileZ << shift;
                data = LocalTerrainProvider.getInstance().fetchHeightmap(startZ, startX,
                        startZ + tileSize, startX + tileSize);
                tiles.put(key, data);
            }
            if (data == null || data.heightmap == null || data.biomeIds == null) continue;
            int localX = sampleX - (tileX << shift), localZ = sampleZ - (tileZ << shift);
            if (localX >= 0 && localZ >= 0 && localX < data.width && localZ < data.height
                    && data.heightmap[localZ][localX] < 0
                    && EcologyShowcaseProfile.isVanillaOcean(data.biomeIds[localZ][localX])) return true;
        }
        return false;
    }

    /** Builds the production marine predicate around one bounded TD tile cache. */
    public static Object selectWatersheds(Object network, Object domain, Object terrain, Object config) {
        try {
            Class<?> sampler = Class.forName("dev.streamsreflowing.core.terrain.TerrainSampler", false,
                    network.getClass().getClassLoader());
            java.lang.reflect.Method waterClass = sampler.getMethod("waterBiomeClass", int.class, int.class);
            Map<Long, LocalTerrainProvider.HeightmapData> tiles = new java.util.HashMap<>();
            java.util.function.BiPredicate<Integer, Integer> marine = (x, z) -> {
                try {
                    return ((Number) waterClass.invoke(terrain, x, z)).intValue() == 2
                            || tdMarineWithin16(x, z, tiles);
                } catch (ReflectiveOperationException failure) { return false; }
            };
            Object selected = StreamsWatershedNetwork.select(network, domain, terrain, config, marine);
            return version(config) >= 4 ? StreamsHierarchyNetwork.select(selected, domain, config) : selected;
        } catch (ReflectiveOperationException | RuntimeException failure) {
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
        CONFIGS.clear();
        TERRAIN_LEVELS.clear();
        LocalTerrainProvider.setLargeRiverPlanningCache(false);
    }
}

