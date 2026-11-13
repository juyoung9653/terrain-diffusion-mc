package com.github.xandergos.terraindiffusionmc.compat.streams;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Applies the persisted TD V14 river policy after Streams 2.17.1 has built a
 * complete native plate network.  Native lake, sink, relief and index state is
 * preserved by the existing six-argument reassembly boundary.
 */
public final class Streams2171NetworkAdapter {
    /** Only fresh 2.17.1 TD worlds select this profile. */
    public static final int PROFILE = 14;
    private static final ThreadLocal<Set<Object>> REFINING = ThreadLocal.withInitial(
            () -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final AtomicLong INVOCATIONS = new AtomicLong();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();

    private Streams2171NetworkAdapter() { }

    /** Actual 2.17.1 normal builder supplies this domain directly. */
    public static Object refine(Object domain, Object terrain, Object config, long seed, Object network) {
        if (!StreamsNaturalRiverAdapter.active(config)
                || StreamsNaturalRiverAdapter.version(config) != PROFILE) return network;
        INVOCATIONS.incrementAndGet();
        Set<Object> inFlight = REFINING.get();
        if (!inFlight.add(network)) return network;
        try {
            Object refined = refineV14(network, domain, terrain, config, seed);
            COMPLETED.incrementAndGet();
            return refined;
        } catch (RuntimeException failure) {
            FAILED.incrementAndGet();
            throw new IllegalStateException("Streams 2.17.1 V14 network adapter changed", failure);
        } finally {
            inFlight.remove(network);
            if (inFlight.isEmpty()) REFINING.remove();
        }
    }

    public record Counts(long invocations, long completed, long failed) { }
    public static Counts counts() {
        return new Counts(INVOCATIONS.get(), COMPLETED.get(), FAILED.get());
    }
    public static void resetCounts() { INVOCATIONS.set(0); COMPLETED.set(0); FAILED.set(0); }

    /**
     * The V14 policy intentionally ends at shallow-bank grading.  Planform
     * rewriting needs an original Minecraft level and is not part of the
     * native 2.17.1 builder contract.
     */
    static Object refineV14(Object network, Object domain, Object terrain, Object config, long seed) {
        Object refined = StreamsNaturalRiverAdapter.selectWatersheds(network, domain, terrain, config);
        refined = StreamsNaturalNetwork.refine(refined, terrain, config);
        refined = StreamsConfluenceNetwork.refine(refined, domain, config);
        return StreamsShallowNetwork.refine(refined, domain, config);
    }

}
