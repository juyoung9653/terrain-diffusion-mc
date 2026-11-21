package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stops a native Streams reach before it edits terrain already claimed by a
 * modelled freshwater wetland. This runs at the shared {@code ColumnPlan}
 * boundary, so the carver, water skin and flow passes all see the same empty
 * plan. It deliberately does not inspect or repair live blocks.
 */
public final class StreamsWetlandHandoff2173 {
    /** One dry edge cell protects the wetland bank from native channel and skin writes. */
    private static final int SHORE_MARGIN = 1;
    private static final int CHUNK_SIZE = 16;
    private static final int CHUNK_MASK_LIMIT = 192;
    private static final Map<Object, WetlandMaskCache> MASKS_BY_ENGINE = new WeakHashMap<>();
    private static final Map<ChunkGenerator, WetlandMaskCache> MASKS_BY_GENERATOR = new WeakHashMap<>();
    private static final ClassValue<Object> NONE_BY_PLAN_CLASS = new ClassValue<>() {
        @Override
        protected Object computeValue(Class<?> planClass) {
            try {
                Field none = planClass.getField("NONE");
                return none.get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Streams 2.17.3 ColumnPlan.NONE is unavailable", e);
            }
        }
    };

    private StreamsWetlandHandoff2173() { }

    /**
     * Binds a small, bounded mask cache to the native engine. A
     * PlannedWaterLevels instance is deliberately never retained: it owns an
     * unbounded mutable tile map and is only safe as a temporary chunk-build
     * helper.
     */
    public static Object registerEngine(Object engine, ChunkGenerator generator) {
        if (engine == null || generator == null) return engine;
        synchronized (MASKS_BY_ENGINE) {
            if (!appliesTo(generator)) {
                MASKS_BY_ENGINE.remove(engine);
                return engine;
            }
            WetlandMaskCache current = MASKS_BY_ENGINE.get(engine);
            if (current == null || current.generator() != generator) {
                WetlandMaskCache replacement;
                synchronized (MASKS_BY_GENERATOR) {
                    replacement = MASKS_BY_GENERATOR.get(generator);
                    if (replacement == null || replacement.generator() != generator) {
                        replacement = new WetlandMaskCache(generator);
                        MASKS_BY_GENERATOR.put(generator, replacement);
                    }
                }
                MASKS_BY_ENGINE.put(engine, replacement);
            }
        }
        return engine;
    }

    /**
     * Replaces only native corridor/channel cells at a modelled wetland floor
     * (or its one-cell shore) with the native singleton NONE plan. The binding
     * is consulted first, so non-TD worlds incur neither native-plan reflection
     * nor a mask lookup.
     */
    public static Object maskPlan(Object engine, Object plan, int x, int z) {
        if (plan == null) return null;
        WetlandMaskCache masks;
        synchronized (MASKS_BY_ENGINE) {
            masks = MASKS_BY_ENGINE.get(engine);
        }
        if (masks == null) return plan;
        if (plan == NONE_BY_PLAN_CLASS.get(plan.getClass())) return plan;
        if (!isNativeReach(plan)) return plan;
        // A graph-grade adapter handles the dry component.  Finish only its
        // small native channel rim here, before carving, so quantisation cannot
        // leave a dry berm between two equal water surfaces.
        Object fitted = masks.fitDryPondApproach(plan, x, z);
        if (fitted != null) return fitted;
        if (!masks.blocks(x, z)) return plan;
        return NONE_BY_PLAN_CLASS.get(plan.getClass());
    }

    /**
     * The native lake branch in RiverCarverFeature clears with AIR before it
     * reaches ColumnPlan. Preserve only an originally modelled wet column;
     * dry one-cell plan-mask fringe remains available to genuine native lakes.
     * {@code wetlandWaterLevels} is exclusive, so y == level is outside water.
     */
    public static boolean preserveWetlandLakeColumn(ChunkGenerator generator, int x, int y, int z) {
        WetlandMaskCache masks;
        synchronized (MASKS_BY_GENERATOR) {
            masks = MASKS_BY_GENERATOR.get(generator);
        }
        int level = masks == null ? 0 : masks.waterLevelAt(x, z);
        return level > 0 && y < level;
    }

    private static boolean appliesTo(ChunkGenerator generator) {
        var source = TerrainDiffusionBiomeSources.find(generator.getBiomeSource());
        return source != null && source.naturalBiomes() && source.ecologyShowcase().isEmpty();
    }

    private static boolean isNativeReach(Object plan) {
        return Boolean.TRUE.equals(StreamsRecordAccess.read(plan, "inChannel"))
                || Boolean.TRUE.equals(StreamsRecordAccess.read(plan, "inCorridor"));
    }

    private static final class WetlandMaskCache {
        private final WeakReference<ChunkGenerator> generator;
        private final Map<Long, ChunkMask> chunks = new LinkedHashMap<>(CHUNK_MASK_LIMIT + 1, .75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ChunkMask> eldest) {
                return size() > CHUNK_MASK_LIMIT;
            }
        };

        private WetlandMaskCache(ChunkGenerator generator) {
            this.generator = new WeakReference<>(generator);
        }

        private ChunkGenerator generator() {
            return generator.get();
        }

        private synchronized boolean blocks(int x, int z) {
            return maskAt(x, z).blocks(x & (CHUNK_SIZE - 1), z & (CHUNK_SIZE - 1));
        }

        private synchronized int waterLevelAt(int x, int z) {
            return maskAt(x, z).waterLevel(x & (CHUNK_SIZE - 1), z & (CHUNK_SIZE - 1));
        }

        private synchronized Object fitDryPondApproach(Object plan, int x, int z) {
            ChunkMask centre = maskAt(x, z);
            int localX = x & (CHUNK_SIZE - 1), localZ = z & (CHUNK_SIZE - 1);
            if (!Boolean.TRUE.equals(StreamsRecordAccess.read(plan, "inChannel"))
                    || centre.waterLevel(localX, localZ) > 0) return null;
            int water = (int) StreamsRecordAccess.number(plan, "waterSurfaceY");
            int ground = centre.groundTop(localX, localZ);
            PondApproach approach = pondWaterAtApproach(x, z, plan);
            if (approach == null
                    || StreamsRecordAccess.number(plan, "width") > 2.5
                    || water - (int) StreamsRecordAccess.number(plan, "bedY") > 2
                    || Math.abs(water - approach.pondWaterY()) > 3
                    || ground < approach.pondWaterY() || ground > approach.pondWaterY() + 2) return null;
            // A native ColumnPlan surface is inclusive whereas TD wetland level
            // is exclusive.  Keep one planned block below it: the existing
            // shallow-bed hook prevents native's isolated extra cut.
            double grade = StreamsRecordAccess.number(plan, "gradeY");
            // Do not cut steps into an already-level outlet. Only close the
            // part of the mismatch that cannot fit in the remaining distance.
            int tolerance = approach.distance() - 1;
            int target = Math.clamp(water, approach.pondWaterY() - tolerance,
                    approach.pondWaterY() + tolerance);
            if (target == water) return plan;
            return StreamsRecordAccess.copy(plan, Map.of(
                    "waterSurfaceY", target, "bedY", target - 1,
                    "gradeY", grade + target - water));
        }

        private PondApproach pondWaterAtApproach(int x, int z, Object plan) {
            double flowX = StreamsRecordAccess.number(plan, "flowDirX");
            double flowZ = StreamsRecordAccess.number(plan, "flowDirZ");
            double length = Math.hypot(flowX, flowZ);
            if (!(length > 1.0e-6)) return null;
            int stepX = Math.abs(flowX) >= Math.abs(flowZ) ? (flowX >= 0 ? 1 : -1) : 0;
            int stepZ = stepX == 0 ? (flowZ >= 0 ? 1 : -1) : 0;
            // Both directions are intentional: persisted Streams segments can
            // continue out of a pond, so the receiving water need not be
            // downstream.  Three axial cells make the measured 63-to-66 outlet
            // a 64,65,66 run and return to its 63 baseline on the next cell.
            PondApproach nearest = null;
            for (int direction = -1; direction <= 1; direction += 2) {
                for (int distance = 1; distance <= 3; distance++) {
                    int pond = waterLevelAt(x + stepX * direction * distance, z + stepZ * direction * distance);
                    if (pond <= 0) continue;
                    if (nearest != null && nearest.pondWaterY() != pond - 1) return null;
                    if (nearest == null || distance < nearest.distance()) nearest = new PondApproach(pond - 1, distance);
                    break;
                }
            }
            return nearest;
        }

        private record PondApproach(int pondWaterY, int distance) { }

        private ChunkMask maskAt(int x, int z) {
            int chunkX = Math.floorDiv(x, CHUNK_SIZE);
            int chunkZ = Math.floorDiv(z, CHUNK_SIZE);
            long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
            ChunkMask mask = chunks.get(key);
            if (mask == null) {
                mask = build(chunkX, chunkZ);
                chunks.put(key, mask);
            }
            return mask;
        }

        private ChunkMask build(int chunkX, int chunkZ) {
            ChunkGenerator currentGenerator = generator();
            if (currentGenerator == null) return ChunkMask.EMPTY;
            NaturalEcology.PlannedWaterLevels levels = NaturalEcology.plannedWaterLevels(currentGenerator);
            if (levels == null) return ChunkMask.EMPTY;
            int minX = chunkX * CHUNK_SIZE;
            int minZ = chunkZ * CHUNK_SIZE;
            boolean[] blocked = new boolean[CHUNK_SIZE * CHUNK_SIZE];
            short[] waterLevels = new short[CHUNK_SIZE * CHUNK_SIZE];
            short[] groundTops = new short[CHUNK_SIZE * CHUNK_SIZE];
            Map<Long, LocalTerrainProvider.HeightmapData> groundTiles = new java.util.HashMap<>();
            for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
                for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                    int index = localZ * CHUNK_SIZE + localX;
                    int x = minX + localX, z = minZ + localZ;
                    int level = levels.levelAt(x, z);
                    waterLevels[index] = (short) level;
                    groundTops[index] = (short) originalGroundTop(groundTiles, x, z);
                    blocked[index] = level > 0 || levels.nearestLevel(x, z, SHORE_MARGIN) > 0;
                }
            }
            return new ChunkMask(blocked, waterLevels, groundTops);
        }

        private int originalGroundTop(Map<Long, LocalTerrainProvider.HeightmapData> tiles, int x, int z) {
            int size = TerrainDiffusionConfig.tileSize();
            int x0 = Math.floorDiv(x, size) * size, z0 = Math.floorDiv(z, size) * size;
            long key = ((long) x0 << 32) ^ (z0 & 0xffffffffL);
            LocalTerrainProvider.HeightmapData data = tiles.computeIfAbsent(key,
                    ignored -> LocalTerrainProvider.getInstance().fetchHeightmap(z0, x0, z0 + size, x0 + size));
            if (data == null || data.heightmap == null) return Short.MIN_VALUE;
            return HeightConverter.convertToMinecraftHeight(data.heightmap[z - z0][x - x0]) - 1;
        }
    }

    private record ChunkMask(boolean[] blocked, short[] waterLevels, short[] groundTops) {
        private static final ChunkMask EMPTY = new ChunkMask(new boolean[CHUNK_SIZE * CHUNK_SIZE],
                new short[CHUNK_SIZE * CHUNK_SIZE], new short[CHUNK_SIZE * CHUNK_SIZE]);

        private ChunkMask {
            if (blocked.length != CHUNK_SIZE * CHUNK_SIZE || waterLevels.length != CHUNK_SIZE * CHUNK_SIZE
                    || groundTops.length != CHUNK_SIZE * CHUNK_SIZE)
                throw new IllegalArgumentException("mask dimensions");
            blocked = Arrays.copyOf(blocked, blocked.length);
            waterLevels = Arrays.copyOf(waterLevels, waterLevels.length);
            groundTops = Arrays.copyOf(groundTops, groundTops.length);
        }

        private boolean blocks(int localX, int localZ) {
            return blocked[localZ * CHUNK_SIZE + localX];
        }

        private int waterLevel(int localX, int localZ) {
            return waterLevels[localZ * CHUNK_SIZE + localX];
        }

        private int groundTop(int localX, int localZ) {
            return groundTops[localZ * CHUNK_SIZE + localX];
        }
    }
}

