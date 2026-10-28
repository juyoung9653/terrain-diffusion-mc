package com.github.xandergos.terraindiffusionmc.world.structure;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, thread-confined memoization of policy-independent native height pairs.
 * A cache belongs to one synchronous choice with fixed generator, random state,
 * height limits and world settings; it must never cross worlds or choices.
 */
public final class StructureHeightCache {
    public static final int DEFAULT_CAPACITY = 65_536;
    public record Heights(int oceanFloor, int worldSurface) { }
    public record Snapshot(long hits, long misses, long nativeHeightCalls, long capDrops,
                           int entries, int capacity) { }

    private final int capacity;
    private final Map<Long, Heights> values = new HashMap<>();
    private long hits, misses, nativeHeightCalls, capDrops;

    public StructureHeightCache() { this(DEFAULT_CAPACITY); }

    public StructureHeightCache(int capacity) {
        if (capacity < 0 || capacity > DEFAULT_CAPACITY)
            throw new IllegalArgumentException("Capacity must be 0.." + DEFAULT_CAPACITY);
        this.capacity = capacity;
    }

    /** Records a lookup even when the cache is disabled or the native sampler later throws. */
    public Heights get(long key) {
        Heights value = values.get(key);
        if (value == null) misses++;
        else hits++;
        return value;
    }

    /** Inserts only absent entries; once full, retain existing values and do not evict. */
    public void put(long key, Heights value) {
        Objects.requireNonNull(value, "value");
        if (values.containsKey(key)) return;
        if (values.size() >= capacity) { capDrops++; return; }
        values.put(key, value);
    }

    /** Call immediately before every actual native getFirstOccupiedHeight invocation. */
    public void recordHeightCall() { nativeHeightCalls++; }

    public Snapshot snapshot() {
        return new Snapshot(hits, misses, nativeHeightCalls, capDrops, values.size(), capacity);
    }
}
