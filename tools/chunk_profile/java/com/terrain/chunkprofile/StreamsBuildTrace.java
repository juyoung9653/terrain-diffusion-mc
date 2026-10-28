package com.terrain.chunkprofile;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, opt-in ledger of actual Streams NetworkTracer.build calls.
 *
 * <p>The diagnostic mixin deliberately records the native construction boundary rather than
 * RiverEngine cache lookups: a cache hit and a FileNetworkStore load must not be reported as a
 * new build.  All Streams access is reflective so this probe remains optional.</p>
 */
public final class StreamsBuildTrace {
    private static final int LIMIT = 1024;
    private static final boolean ENABLED = Boolean.getBoolean("td.inference.requestTelemetry");
    private static final Object LOCK = new Object();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final List<Event> COMPLETE = new ArrayList<>();
    private static final LinkedHashMap<Long, Event> INFLIGHT = new LinkedHashMap<>();
    private static final ThreadLocal<ArrayDeque<Event>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static long historyDrops;
    private static long reflectionFailures;

    private StreamsBuildTrace() { }

    /** Called at NetworkTracer.build HEAD by the diagnostic mixin. */
    public static void begin(Object domain) {
        if (!ENABLED) return;
        Event event = new Event(NEXT_ID.incrementAndGet());
        describeDomain(event, domain);
        event.priority = priority(event);
        event.thread = Thread.currentThread().getName();
        event.nanoTime = System.nanoTime();
        event.wallTime = Instant.now().toString();
        STACK.get().push(event);
        synchronized (LOCK) {
            if (INFLIGHT.size() < LIMIT) INFLIGHT.put(event.id, event);
            else {
                event.inflightDropped = true;
                historyDrops++;
            }
        }
    }

    /** Called at NetworkTracer.build RETURN by the diagnostic mixin. */
    public static void finish(Object network) {
        if (!ENABLED) return;
        ArrayDeque<Event> stack = STACK.get();
        Event event = stack.poll();
        if (stack.isEmpty()) STACK.remove();
        if (event == null) {
            synchronized (LOCK) { reflectionFailures++; }
            return;
        }
        synchronized (LOCK) {
            // The event remains visible through INFLIGHT until its native return summary is complete.
            describeNetwork(event, network);
            event.returnNanoTime = System.nanoTime();
            if (!event.inflightDropped) INFLIGHT.remove(event.id);
            if (COMPLETE.size() < LIMIT) COMPLETE.add(event.copy());
            else historyDrops++;
        }
    }

    /** Immutable-by-construction map/list snapshot suitable for Gson serialization. */
    public static Map<String, Object> snapshot() {
        if (!ENABLED) return Map.of("enabled", false);
        synchronized (LOCK) {
            List<Map<String, Object>> complete = new ArrayList<>(COMPLETE.size());
            for (Event event : COMPLETE) complete.add(event.asMap());
            List<Map<String, Object>> inflight = new ArrayList<>(INFLIGHT.size());
            for (Event event : INFLIGHT.values()) inflight.add(event.asMap());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", true);
            out.put("historyLimit", LIMIT);
            out.put("historyDrops", historyDrops);
            out.put("reflectionFailures", reflectionFailures);
            out.put("eventCount", complete.size());
            out.put("inflightCount", inflight.size());
            out.put("events", List.copyOf(complete));
            out.put("inflight", List.copyOf(inflight));
            return Map.copyOf(out);
        }
    }

    private static void describeDomain(Event event, Object domain) {
        if (domain == null) {
            event.error("domain:null");
            return;
        }
        try {
            Class<?> type = domain.getClass();
            Object key = type.getMethod("key").invoke(domain);
            event.key = key == null ? null : String.valueOf(key);
            if (key != null) {
                event.cx = number(key.getClass().getMethod("cx").invoke(key));
                event.cz = number(key.getClass().getMethod("cz").invoke(key));
            }
            event.minX = number(type.getField("minX").get(domain));
            event.minZ = number(type.getField("minZ").get(domain));
            event.maxX = number(type.getField("maxX").get(domain));
            event.maxZ = number(type.getField("maxZ").get(domain));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            recordFailure(event, "domain", failure);
        }
    }

    private static void describeNetwork(Event event, Object network) {
        if (network == null) {
            event.error("network:null");
            return;
        }
        try {
            Class<?> type = network.getClass();
            Object key = type.getMethod("key").invoke(network);
            event.networkKey = key == null ? null : String.valueOf(key);
            event.segmentCount = number(type.getMethod("segmentCount").invoke(network));
            Object lakes = type.getMethod("lakes").invoke(network);
            event.lakeCount = lakes instanceof List<?> list ? list.size() : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            recordFailure(event, "network", failure);
        }
    }

    private static String priority(Event event) {
        try {
            Class<?> type = Class.forName("com.github.xandergos.terraindiffusionmc.compat.streams.StreamsSamplerPriority");
            Method current = type.getMethod("current");
            Object value = current.invoke(null);
            return value == null ? "null" : String.valueOf(value);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            recordFailure(event, "priority", failure);
            return "unavailable";
        }
    }

    private static Integer number(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static void recordFailure(Event event, String where, Throwable failure) {
        event.error(where + ":" + failure.getClass().getSimpleName());
        synchronized (LOCK) { reflectionFailures++; }
    }

    private static final class Event {
        private final long id;
        private String key, networkKey, priority, thread, wallTime, error;
        private Integer cx, cz, minX, minZ, maxX, maxZ, segmentCount, lakeCount;
        private long nanoTime, returnNanoTime;
        private boolean inflightDropped;

        private Event(long id) { this.id = id; }
        private Event(Event source) {
            id = source.id; key = source.key; networkKey = source.networkKey; priority = source.priority;
            thread = source.thread; wallTime = source.wallTime; error = source.error; cx = source.cx; cz = source.cz;
            minX = source.minX; minZ = source.minZ; maxX = source.maxX; maxZ = source.maxZ;
            segmentCount = source.segmentCount; lakeCount = source.lakeCount; nanoTime = source.nanoTime;
            returnNanoTime = source.returnNanoTime; inflightDropped = source.inflightDropped;
        }
        private Event copy() { return new Event(this); }
        private void error(String detail) { error = error == null ? detail : error + ";" + detail; }
        private Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id); out.put("key", key); out.put("cx", cx); out.put("cz", cz);
            List<Integer> bounds = new ArrayList<>(4);
            bounds.add(minX); bounds.add(minZ); bounds.add(maxX); bounds.add(maxZ);
            out.put("bounds", bounds); out.put("priority", priority);
            out.put("thread", thread); out.put("nanoTime", nanoTime); out.put("wallTime", wallTime);
            out.put("returnNanoTime", returnNanoTime); out.put("networkKey", networkKey);
            out.put("segmentCount", segmentCount); out.put("lakeCount", lakeCount);
            out.put("networkSummaryStage", "native_pre_td_refinement");
            out.put("error", error); return out;
        }
    }
}
