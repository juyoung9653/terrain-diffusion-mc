package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.Map;

/** Pure-JDK regression checks for opt-in tensor-window recompute telemetry. */
public final class WindowTelemetryTest {
    private static final Integer[] SHAPE = new Integer[]{1, null};
    private static final TensorWindow WINDOW = new TensorWindow(new int[]{1, 1});

    public static void main(String[] args) {
        System.clearProperty("td.inference.requestTelemetry");
        MemoryTileStore disabled = new MemoryTileStore();
        tensor(disabled, "off", Long.MAX_VALUE, false).getSlice(new int[]{0, 0}, new int[]{1, 1});
        require(disabled.windowTelemetry().isEmpty(), "telemetry must default off");

        System.setProperty("td.inference.requestTelemetry", "true");
        MemoryTileStore store = new MemoryTileStore();
        InfiniteTensor hit = tensor(store, "hit", Long.MAX_VALUE, false);
        hit.getSlice(new int[]{0, 0}, new int[]{1, 1});
        hit.getSlice(new int[]{0, 0}, new int[]{1, 1});
        require(values(store, "hit")[0] == 1 && values(store, "hit")[1] == 0,
                "cache hit must not publish a window");

        InfiniteTensor evict = tensor(store, "evict", 4L, false);
        slice(evict, 0); slice(evict, 1); slice(evict, 0);
        long[] evictValues = values(store, "evict");
        require(evictValues[0] == 3 && evictValues[1] == 1 && evictValues[2] == 2,
                "evicted same key must be counted as one recompute");

        InfiniteTensor distinct = tensor(store, "distinct", Long.MAX_VALUE, false);
        slice(distinct, 0); slice(distinct, 1);
        long[] distinctValues = values(store, "distinct");
        require(distinctValues[0] == 2 && distinctValues[1] == 0,
                "distinct keys must not be recomputes");

        InfiniteTensor batch = tensor(store, "batch", Long.MAX_VALUE, true);
        batch.getSlice(new int[]{0, 0}, new int[]{1, 3});
        long[] batchValues = values(store, "batch");
        require(batchValues[0] == 3 && batchValues[1] == 0,
                "batched rows must publish individually");

        InfiniteTensor failed = store.getOrCreate("failed", SHAPE,
                (key, ignored) -> { throw new IllegalStateException("expected"); },
                WINDOW, new InfiniteTensor[]{}, new TensorWindow[]{}, Long.MAX_VALUE);
        try {
            slice(failed, 0);
            throw new AssertionError("failing compute unexpectedly succeeded");
        } catch (IllegalStateException expected) {
            // A failed function must never publish a telemetry window.
        }
        require(!store.windowTelemetry().containsKey("failed"), "failed compute must not be counted");

        InfiniteTensor bounded = tensor(store, "bounded", Long.MAX_VALUE, false);
        for (int index = 0; index <= 8192; index++) slice(bounded, index);
        Map<String, Long> boundedValues = store.windowTelemetry().get("bounded");
        require(boundedValues.get("computedWindows") == 8193L
                        && boundedValues.get("trackedKeys") == 8192L
                        && boundedValues.get("historyDrops") == 1L,
                "bounded history must declare incomplete key coverage");

        InfiniteTensor removed = tensor(store, "removed", Long.MAX_VALUE, false);
        slice(removed, 0);
        store.removeTensor("removed");
        slice(tensor(store, "removed", Long.MAX_VALUE, false), 0);
        long[] removedValues = values(store, "removed");
        require(removedValues[0] == 1 && removedValues[1] == 0,
                "removing a tensor must discard its old history");

        long generationBeforeClear = store.windowTelemetry().get("removed").get("historyGeneration");
        store.clearAllCaches();
        slice(evict, 0);
        long[] resetValues = values(store, "evict");
        require(resetValues[0] == 1 && resetValues[1] == 0 && resetValues[2] == 1,
                "cache clear must reset history for a new seed/generation");
        require(store.windowTelemetry().get("evict").get("historyGeneration") == generationBeforeClear + 1L,
                "cache clear must publish a new history generation");
        System.out.println("WindowTelemetryTest PASS");
    }

    private static InfiniteTensor tensor(MemoryTileStore store, String id, long limit, boolean batched) {
        if (!batched) return store.getOrCreate(id, SHAPE,
                (key, ignored) -> value(key[1]), WINDOW, new InfiniteTensor[]{}, new TensorWindow[]{}, limit);
        return store.getOrCreateBatched(id, SHAPE,
                (keys, ignored) -> keys.stream().map(key -> value(key[1])).toList(),
                WINDOW, new InfiniteTensor[]{}, new TensorWindow[]{}, limit, 2);
    }

    private static FloatTensor value(int value) {
        FloatTensor result = new FloatTensor(new int[]{1, 1});
        result.data[0] = value;
        return result;
    }

    private static void slice(InfiniteTensor tensor, int index) {
        tensor.getSlice(new int[]{0, index}, new int[]{1, index + 1});
    }

    private static long[] values(MemoryTileStore store, String id) {
        Map<String, Long> values = store.windowTelemetry().get(id);
        require(values != null, "missing stage " + id);
        require(values.get("historyDrops") == 0L, "test must stay within telemetry bound");
        return new long[]{values.get("computedWindows"), values.get("recomputedWindows"), values.get("trackedKeys")};
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
