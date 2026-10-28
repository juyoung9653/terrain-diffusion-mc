package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Regression fixture for admission across the full and fast-ground complete-tile caches.
 * It injects only already-computed valid entries; the one requested tile uses the normal
 * deterministic WorldPipeline fixture, so no native model is involved.
 */
public final class GroundCacheAdmissionTest {
    private static final int TILE = 256;
    private static final LocalTerrainProvider.HeightmapData SHARED =
            new LocalTerrainProvider.HeightmapData(new short[TILE][TILE], null, TILE, TILE);

    public static void main(String[] args) throws Exception {
        System.setProperty("td.inference.legacySampling", "true");
        WorldScaleManager.fastGroundSampling = true;
        WorldScaleManager.scale = 1;
        LocalTerrainProvider.init(0xCAFE);
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();
        try {
            for (boolean large : new boolean[] {false, true}) {
                newestGroundAdmissionSurvivesFullHeadroom(provider, large);
                oldGroundYieldsToNewFull(provider, large);
            }
            fullUpgradeRemovesGroundDuplicate(provider);
            clearInvalidatesGroundCursor(provider);
            System.out.println("PASS ground-cache admission: newest ground retention, global victim validity, "
                    + "full upgrade and cursor invalidation");
        } finally {
            WorldScaleManager.fastGroundSampling = false;
            WorldScaleManager.scale = 1;
            System.clearProperty("td.inference.legacySampling");
            LocalTerrainProvider.shutdownAll();
        }
    }

    private static void newestGroundAdmissionSurvivesFullHeadroom(
            LocalTerrainProvider provider, boolean large) throws Exception {
        int target = setTarget(large);
        populateFullHeadroom(provider, target);
        Object freshKey = key(0, 0);
        int before = WorldPipeline.cropCalls.get();
        provider.withSamplingBinding(() -> {
            for (int y = 0; y < 16; y++)
                check(LocalTerrainProvider.scopedGroundHeight(0, 0) != Integer.MIN_VALUE,
                        "ground cursor must return an exact height");
            return null;
        });
        check(WorldPipeline.cropCalls.get() == before + 1,
                "newest ground tile must not be evicted during admission target=" + target);
        Object admitted = ground(provider).get(freshKey);
        check(admitted != null && valid(admitted),
                "newest ground entry retained after full-headroom admission target=" + target);
        check(combined(provider) <= target + 4,
                "combined cache honors target headroom after ground admission target=" + target);
    }

    private static void oldGroundYieldsToNewFull(LocalTerrainProvider provider, boolean large)
            throws Exception {
        int target = setTarget(large);
        populateFullHeadroom(provider, target);
        Object oldGroundKey = key(0, 0);
        Object oldGround = entry(SHARED, 1);
        synchronized (lock(provider)) {
            ground(provider).put(oldGroundKey, oldGround);
            clock(provider).set(target + 5L);
        }
        // This complete tile is distinct from the injected positive-coordinate entries.
        provider.fetchHeightmap(-TILE, -TILE, 0, 0);
        check(!valid(oldGround), "old ground victim marked invalid target=" + target);
        check(!ground(provider).containsKey(oldGroundKey),
                "old ground victim removed before retaining new full target=" + target);
        check(combined(provider) <= target + 4,
                "combined cache bounded after new full target=" + target);
    }

    private static void fullUpgradeRemovesGroundDuplicate(LocalTerrainProvider provider) throws Exception {
        setTarget(false);
        clear(provider);
        Object duplicateKey = key(0, 0);
        Object oldGround = entry(SHARED, 1);
        synchronized (lock(provider)) { ground(provider).put(duplicateKey, oldGround); }
        provider.fetchHeightmap(0, 0, TILE, TILE);
        check(!ground(provider).containsKey(duplicateKey), "full upgrade removes duplicate ground key");
        check(!valid(oldGround), "full upgrade invalidates removed ground entry");
        check(cache(provider).containsKey(duplicateKey), "full upgrade retains complete tile");
    }

    private static void clearInvalidatesGroundCursor(LocalTerrainProvider provider) throws Exception {
        setTarget(false);
        clear(provider);
        provider.withSamplingBinding(() -> {
            LocalTerrainProvider.scopedGroundHeight(0, 0);
            int before = WorldPipeline.cropCalls.get();
            LocalTerrainProvider.clearCache();
            LocalTerrainProvider.scopedGroundHeight(0, 0);
            check(WorldPipeline.cropCalls.get() == before + 1,
                    "clear invalidates the bound ground cursor");
            return null;
        });
    }

    private static int setTarget(boolean large) {
        LocalTerrainProvider.setLargeRiverPlanningCache(large);
        return large ? 512 : 64;
    }

    private static void populateFullHeadroom(LocalTerrainProvider provider, int target) throws Exception {
        clear(provider);
        synchronized (lock(provider)) {
            for (int n = 0; n < target + 4; n++)
                cache(provider).put(key((n + 1) * TILE, (n + 1) * TILE), entry(SHARED, n + 2L));
            clock(provider).set(target + 5L);
        }
    }

    private static void clear(LocalTerrainProvider provider) throws Exception {
        LocalTerrainProvider.clearCache();
        recent("RECENT_TILE").remove();
        recent("RECENT_GROUND_TILE").remove();
        synchronized (lock(provider)) { clock(provider).set(0); }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> cache(LocalTerrainProvider provider) throws Exception {
        return (Map<Object, Object>) field(provider, "CACHE");
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> ground(LocalTerrainProvider provider) throws Exception {
        return (Map<Object, Object>) field(provider, "GROUND_CACHE");
    }

    private static int combined(LocalTerrainProvider provider) throws Exception {
        return cache(provider).size() + ground(provider).size();
    }

    private static Object lock(LocalTerrainProvider provider) throws Exception {
        return field(provider, "cacheLock");
    }

    private static AtomicLong clock(LocalTerrainProvider provider) throws Exception {
        return (AtomicLong) field(provider, "CACHE_CLOCK");
    }

    private static Object field(LocalTerrainProvider provider, String name) throws Exception {
        Field field = LocalTerrainProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(provider);
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> recent(String name) throws Exception {
        Field field = LocalTerrainProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ThreadLocal<Object>) field.get(null);
    }

    private static Object key(int i1, int j1) throws Exception {
        Class<?> type = Class.forName(LocalTerrainProvider.class.getName() + "$CacheKey");
        Constructor<?> ctor = type.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(i1, j1, i1 + TILE, j1 + TILE, 1);
    }

    private static Object entry(LocalTerrainProvider.HeightmapData data, long access) throws Exception {
        Class<?> type = Class.forName(LocalTerrainProvider.class.getName() + "$CacheEntry");
        Constructor<?> ctor = type.getDeclaredConstructor(LocalTerrainProvider.HeightmapData.class, AtomicLong.class);
        ctor.setAccessible(true);
        return ctor.newInstance(data, new AtomicLong(access));
    }

    private static boolean valid(Object entry) throws Exception {
        Field field = entry.getClass().getDeclaredField("valid");
        field.setAccessible(true);
        return field.getBoolean(entry);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
