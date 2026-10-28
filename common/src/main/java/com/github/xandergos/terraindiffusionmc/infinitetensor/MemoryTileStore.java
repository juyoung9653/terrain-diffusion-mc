package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory factory and LRU cache for {@link InfiniteTensor} window outputs.
 *
 * <p>Each tensor registered with this store maintains its own ordered map of
 * window index → FloatTensor, with LRU eviction governed by the per-tensor
 * cache limit supplied at creation time.
 */
public class MemoryTileStore {

    /**
     * Opt-in, bounded evidence for a window that was actually recomputed after
     * its earlier result left the internal tensor cache.  This deliberately
     * observes only successful cache publications, never ordinary cache hits.
     */
    private static final class WindowTelemetry {
        private static final int MAX_KEYS_PER_STAGE = 8192;
        private final Map<String, Set<List<Integer>>> history = new HashMap<>();
        private final Map<String, long[]> totals = new HashMap<>();
        private final Map<String, Long> historyDrops = new HashMap<>();
        private long historyGeneration;

        synchronized void published(String id, List<Integer> key) {
            long[] counters = totals.computeIfAbsent(id, ignored -> new long[2]);
            counters[0]++;
            Set<List<Integer>> keys = history.computeIfAbsent(id, ignored -> new HashSet<>());
            if (keys.contains(key)) {
                counters[1]++;
                return;
            }
            if (keys.size() < MAX_KEYS_PER_STAGE) keys.add(key);
            else historyDrops.merge(id, 1L, Long::sum);
        }

        synchronized Map<String, Map<String, Long>> snapshot() {
            Map<String, Map<String, Long>> result = new TreeMap<>();
            for (Map.Entry<String, long[]> entry : totals.entrySet()) {
                Map<String, Long> values = new LinkedHashMap<>();
                values.put("computedWindows", entry.getValue()[0]);
                values.put("recomputedWindows", entry.getValue()[1]);
                values.put("trackedKeys", (long) history.get(entry.getKey()).size());
                values.put("historyDrops", historyDrops.getOrDefault(entry.getKey(), 0L));
                values.put("historyGeneration", historyGeneration);
                result.put(entry.getKey(), values);
            }
            return result;
        }

        synchronized void clear() {
            history.clear();
            totals.clear();
            historyDrops.clear();
            historyGeneration++;
        }

        synchronized void remove(String id) {
            history.remove(id);
            totals.remove(id);
            historyDrops.remove(id);
            historyGeneration++;
        }
    }

    /** Window cache per tensor id: access-order LinkedHashMap for LRU. */
    private final Map<String, LinkedHashMap<List<Integer>, FloatTensor>> windowCaches = new HashMap<>();

    /** Tracked byte count per tensor id. */
    private final Map<String, long[]> cacheSizes = new HashMap<>();

    /** All registered tensor instances, by id. */
    private final Map<String, InfiniteTensor> tensors = new HashMap<>();
    /** Monotonic count of newly computed/cached windows across all tensors. */
    private final AtomicLong totalComputedWindowCount = new AtomicLong(0L);
    private final WindowTelemetry windowTelemetry = Boolean.getBoolean("td.inference.requestTelemetry")
            ? new WindowTelemetry() : null;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a non-batched InfiniteTensor, or returns the existing one if already
     * registered under {@code id}.
     *
     * @param id            unique name for this tensor
     * @param shape         per-dimension size; {@code null} = unbounded
     * @param function      window compute function
     * @param outputWindow  sliding window specification for outputs
     * @param deps          upstream dependency tensors (may be empty)
     * @param depWindows    how to slice each dependency for a window index
     * @param cacheLimitBytes soft LRU limit; {@code Long.MAX_VALUE} = unlimited
     */
    public InfiniteTensor getOrCreate(
            String id,
            Integer[] shape,
            TensorFunction function,
            TensorWindow outputWindow,
            InfiniteTensor[] deps,
            TensorWindow[] depWindows,
            long cacheLimitBytes) {

        if (tensors.containsKey(id)) return tensors.get(id);

        InfiniteTensor tensor = new InfiniteTensor(
                id, shape, outputWindow, function, null, 0,
                deps, depWindows, this, cacheLimitBytes);
        register(id, tensor);
        return tensor;
    }

    /**
     * Creates a batched InfiniteTensor, or returns the existing one.
     *
     * @param batchSize maximum windows per {@link BatchTensorFunction} call
     */
    public InfiniteTensor getOrCreateBatched(
            String id,
            Integer[] shape,
            BatchTensorFunction batchFunction,
            TensorWindow outputWindow,
            InfiniteTensor[] deps,
            TensorWindow[] depWindows,
            long cacheLimitBytes,
            int batchSize) {

        if (tensors.containsKey(id)) return tensors.get(id);

        InfiniteTensor tensor = new InfiniteTensor(
                id, shape, outputWindow, null, batchFunction, batchSize,
                deps, depWindows, this, cacheLimitBytes);
        register(id, tensor);
        return tensor;
    }

    private void register(String id, InfiniteTensor tensor) {
        tensors.put(id, tensor);
        // access-order LinkedHashMap for LRU eviction
        windowCaches.put(id, new LinkedHashMap<>(16, 0.75f, true));
        cacheSizes.put(id, new long[]{0L});
    }

    // -------------------------------------------------------------------------
    // Cache operations (called from InfiniteTensor)
    // -------------------------------------------------------------------------

    void cacheWindow(String id, int[] windowIndex, FloatTensor output) {
        List<Integer> key = toKey(windowIndex);
        LinkedHashMap<List<Integer>, FloatTensor> cache = windowCaches.get(id);
        long[] size = cacheSizes.get(id);

        if (cache.containsKey(key)) {
            // Already present; move to end (most-recent).
            cache.get(key); // triggers access-order promotion
            return;
        }

        cache.put(key, output);
        size[0] += output.byteSize();
        totalComputedWindowCount.incrementAndGet();
        if (windowTelemetry != null) windowTelemetry.published(id, key);
    }

    /** Returns how many windows have been newly computed and cached. */
    public long getTotalComputedWindowCount() {
        return totalComputedWindowCount.get();
    }

    /**
     * Returns opt-in per-tensor publication counters suitable for diagnostic
     * JSON.  Each stage reports {@code computedWindows}, {@code recomputedWindows},
     * {@code trackedKeys}, per-stage {@code historyDrops}, and {@code historyGeneration}.
     * A changed generation means a cache/seed clear occurred between snapshots.
     * An empty map means request telemetry was disabled at store creation.
     */
    public Map<String, Map<String, Long>> windowTelemetry() {
        return windowTelemetry == null ? Collections.emptyMap() : windowTelemetry.snapshot();
    }

    void evictIfNeeded(String id, long limitBytes) {
        if (limitBytes == Long.MAX_VALUE) return;
        LinkedHashMap<List<Integer>, FloatTensor> cache = windowCaches.get(id);
        long[] size = cacheSizes.get(id);
        if (cache == null) return;

        // Keep at least one entry even if it exceeds the limit.
        Iterator<Map.Entry<List<Integer>, FloatTensor>> it = cache.entrySet().iterator();
        while (size[0] > limitBytes && cache.size() > 1 && it.hasNext()) {
            Map.Entry<List<Integer>, FloatTensor> entry = it.next();
            size[0] -= entry.getValue().byteSize();
            it.remove();
        }
    }

    FloatTensor getCachedWindow(String id, int[] windowIndex) {
        LinkedHashMap<List<Integer>, FloatTensor> cache = windowCaches.get(id);
        if (cache == null) return null;
        return cache.get(toKey(windowIndex));
    }

    boolean isWindowCached(String id, int[] windowIndex) {
        LinkedHashMap<List<Integer>, FloatTensor> cache = windowCaches.get(id);
        return cache != null && cache.containsKey(toKey(windowIndex));
    }

    /** Remove all cached window outputs for every registered tensor. */
    public void clearAllCaches() {
        for (Map.Entry<String, LinkedHashMap<List<Integer>, FloatTensor>> e : windowCaches.entrySet()) {
            e.getValue().clear();
            cacheSizes.get(e.getKey())[0] = 0L;
        }
        if (windowTelemetry != null) windowTelemetry.clear();
    }

    /** Remove a single tensor and all its cached state. */
    public void removeTensor(String id) {
        tensors.remove(id);
        windowCaches.remove(id);
        cacheSizes.remove(id);
        if (windowTelemetry != null) windowTelemetry.remove(id);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<Integer> toKey(int[] windowIndex) {
        List<Integer> key = new ArrayList<>(windowIndex.length);
        for (int v : windowIndex) key.add(v);
        return key;
    }
}
