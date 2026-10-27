package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.google.gson.Gson;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight, diagnostic-only CPU sampler for a bounded native probe phase.
 *
 * <p>Each 100 ms interval attributes a thread's observed CPU-time delta to its
 * instantaneous RUNNABLE stack. This is sampling evidence, not an exact profiler:
 * a stack can change between samples, native work can have no useful Java frame,
 * and time on non-RUNNABLE threads is deliberately excluded.
 */
public final class TerrainCpuProfile implements AutoCloseable {
    private static final long INTERVAL_MILLIS = 100L;
    private static final int TOP = 40;

    private final ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object lock = new Object();
    private final Map<Long, Long> previousCpuNanos = new HashMap<>();
    private final Map<String, Long> leafNanos = new HashMap<>();
    private final Map<String, Long> inclusiveNanos = new HashMap<>();
    private final Map<String, Long> groupNanos = new HashMap<>();
    private final Map<String, Integer> errors = new HashMap<>();
    private Thread worker;
    private long sampleIntervals;
    private long runnableThreadSamples;
    private long observedCpuNanos;
    private String status = "starting";
    private String finishedJson;

    private TerrainCpuProfile() {}

    /** Starts a daemon sampler. Unsupported CPU time leaves a valid unavailable receipt. */
    public static TerrainCpuProfile start() {
        TerrainCpuProfile profile = new TerrainCpuProfile();
        profile.startWorker();
        return profile;
    }

    private void startWorker() {
        try {
            if (!mxBean.isThreadCpuTimeSupported()) {
                status = "unavailable";
                return;
            }
            if (!mxBean.isThreadCpuTimeEnabled()) mxBean.setThreadCpuTimeEnabled(true);
            running.set(true);
            worker = new Thread(this::sampleLoop, "td-terrain-cpu-profile");
            worker.setDaemon(true);
            worker.start();
            status = "running";
        } catch (Throwable failure) {
            recordError(failure);
            status = "unavailable";
        }
    }

    private void sampleLoop() {
        while (running.get()) {
            try {
                Thread.sleep(INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                // close() uses interruption to make the final receipt prompt.
                Thread.currentThread().interrupt();
                break;
            }
            if (running.get()) sampleOnce();
        }
    }

    private void sampleOnce() {
        try {
            long samplerId = Thread.currentThread().threadId();
            Map<Long, String> groups = threadGroups();
            long[] ids = mxBean.getAllThreadIds();
            Set<Long> live = new HashSet<>();
            synchronized (lock) {
                sampleIntervals++;
                for (long id : ids) {
                    if (id == samplerId) continue;
                    live.add(id);
                    long now = mxBean.getThreadCpuTime(id);
                    if (now < 0L) continue;
                    Long before = previousCpuNanos.put(id, now);
                    if (before == null || now <= before) continue;
                    long delta = now - before;
                    ThreadInfo info = mxBean.getThreadInfo(id, Integer.MAX_VALUE);
                    if (info == null || info.getThreadState() != Thread.State.RUNNABLE) continue;
                    StackTraceElement[] stack = info.getStackTrace();
                    String leaf = stack.length == 0 ? "<empty>" : method(stack[0]);
                    add(leafNanos, leaf, delta);
                    Set<String> seenMethods = new HashSet<>();
                    for (StackTraceElement frame : stack) {
                        String method = method(frame);
                        if (seenMethods.add(method)) add(inclusiveNanos, method, delta);
                    }
                    add(groupNanos, groups.getOrDefault(id, "<unknown>"), delta);
                    runnableThreadSamples++;
                    observedCpuNanos += delta;
                }
                previousCpuNanos.keySet().retainAll(live);
            }
        } catch (Throwable failure) {
            recordError(failure);
        }
    }

    private static Map<Long, String> threadGroups() {
        Map<Long, String> groups = new HashMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            ThreadGroup group = thread.getThreadGroup();
            groups.put(thread.threadId(), group == null ? "<none>" : group.getName());
        }
        return groups;
    }

    private static String method(StackTraceElement frame) {
        return frame.getClassName() + "." + frame.getMethodName();
    }

    private static void add(Map<String, Long> target, String key, long nanos) {
        target.merge(key, nanos, Long::sum);
    }

    private void recordError(Throwable failure) {
        synchronized (lock) {
            errors.merge(failure.getClass().getSimpleName(), 1, Integer::sum);
        }
    }

    /** Stops sampling and returns one JSON receipt. Repeated calls return the same receipt. */
    public String finishJson() {
        synchronized (lock) {
            if (finishedJson != null) return finishedJson;
        }
        running.set(false);
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
            try {
                currentWorker.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recordError(interrupted);
            }
            if (currentWorker.isAlive()) recordError(new IllegalStateException("sampler_join_timeout"));
        }
        synchronized (lock) {
            String finalStatus = "running".equals(status)
                    ? (errors.isEmpty() && runnableThreadSamples > 0 ? "passed" : "incomplete") : status;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event", "terrain_cpu_profile");
            row.put("status", finalStatus);
            row.put("intervalMillis", INTERVAL_MILLIS);
            row.put("sampleIntervals", sampleIntervals);
            row.put("runnableThreadSamples", runnableThreadSamples);
            row.put("observedCpuMs", observedCpuNanos / 1_000_000.0);
            row.put("exclusiveLeafEstimatedCpuMs", top(leafNanos));
            row.put("inclusiveMethodEstimatedCpuMs", top(inclusiveNanos));
            row.put("threadGroupEstimatedCpuMs", top(groupNanos));
            row.put("errors", new LinkedHashMap<>(errors));
            row.put("limitations", "100ms CPU-delta weighted RUNNABLE stack samples; estimates are not exact "
                    + "wall time, do not include blocked/idle CPU, and native work may not expose a useful Java frame");
            finishedJson = new Gson().toJson(row);
            return finishedJson;
        }
    }

    private static List<Map<String, Object>> top(Map<String, Long> source) {
        return source.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("method", entry.getKey());
                    row.put("estimatedCpuMs", entry.getValue() / 1_000_000.0);
                    return row;
                })
                .toList();
    }

    @Override
    public void close() {
        finishJson();
    }
}
