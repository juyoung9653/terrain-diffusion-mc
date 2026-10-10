package com.github.xandergos.terraindiffusionmc.diagnostic;

import java.lang.management.ManagementFactory;

/** Process-wide CPU accounting for the diagnostic density probe. */
public final class DensityProbeTelemetry {
    private DensityProbeTelemetry() { }

    public static int prefetch(String value) {
        if (value == null) return 8;
        try {
            int count = Integer.parseInt(value);
            if (count >= 1 && count <= 32) return count;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("td.densityPrefetch must be an integer from 1 to 32: " + value);
    }

    public static Long processCpuNanos() {
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
                long nanos = extended.getProcessCpuTime();
                return nanos >= 0 ? nanos : null;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // An unavailable management implementation must not fail the census.
        }
        return null;
    }

    public record CpuUsage(Double processCpuSeconds, Double averageLogicalCores,
                           Double percentAvailableLogicalCapacity) { }

    /** Cumulative executor counters sampled at a census boundary. */
    public record InferenceCounters(long submitted, long completed, long queueNanos, long executionNanos) { }

    /** Sums across executor tasks; neither duration is observer wait or device utilization. */
    public record InferenceDelta(Long submitted, Long completed, Double queueSeconds, Double executionSeconds) { }

    public static InferenceDelta inferenceDelta(InferenceCounters before, InferenceCounters after) {
        if (before == null || after == null || after.submitted() < before.submitted()
                || after.completed() < before.completed() || after.queueNanos() < before.queueNanos()
                || after.executionNanos() < before.executionNanos())
            return new InferenceDelta(null, null, null, null);
        return new InferenceDelta(after.submitted() - before.submitted(), after.completed() - before.completed(),
                seconds(after.queueNanos() - before.queueNanos()),
                seconds(after.executionNanos() - before.executionNanos()));
    }

    public static CpuUsage usage(Long beforeCpuNanos, Long afterCpuNanos,
                                 long wallNanos, int availableProcessors) {
        if (beforeCpuNanos == null || afterCpuNanos == null || beforeCpuNanos < 0
                || afterCpuNanos < beforeCpuNanos || wallNanos <= 0 || availableProcessors <= 0)
            return new CpuUsage(null, null, null);
        double cpuSeconds = (afterCpuNanos - beforeCpuNanos) / 1_000_000_000.0;
        double cores = (afterCpuNanos - beforeCpuNanos) / (double) wallNanos;
        return new CpuUsage(cpuSeconds, cores, cores * 100.0 / availableProcessors);
    }

    public static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }
}
