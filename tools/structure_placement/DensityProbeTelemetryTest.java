import com.github.xandergos.terraindiffusionmc.diagnostic.DensityProbeTelemetry;

public final class DensityProbeTelemetryTest {
    private static void equal(double expected, Double actual) {
        if (actual == null || Math.abs(expected - actual) > 1e-9)
            throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void unavailable(DensityProbeTelemetry.CpuUsage usage) {
        if (usage.processCpuSeconds() != null || usage.averageLogicalCores() != null
                || usage.percentAvailableLogicalCapacity() != null)
            throw new AssertionError("unsupported CPU time must stay unavailable: " + usage);
    }

    private static void unavailable(DensityProbeTelemetry.InferenceDelta delta) {
        if (delta.submitted() != null || delta.completed() != null
                || delta.queueSeconds() != null || delta.executionSeconds() != null)
            throw new AssertionError("unavailable inference counters must remain null: " + delta);
    }

    private static void invalid(String value) {
        try {
            DensityProbeTelemetry.prefetch(value);
            throw new AssertionError("accepted invalid prefetch: " + value);
        } catch (IllegalArgumentException expected) { }
    }

    public static void main(String[] args) {
        if (DensityProbeTelemetry.prefetch(null) != 8
                || DensityProbeTelemetry.prefetch("1") != 1
                || DensityProbeTelemetry.prefetch("32") != 32)
            throw new AssertionError("prefetch bounds/default changed");
        invalid("0"); invalid("33"); invalid("abc"); invalid("");
        var usage = DensityProbeTelemetry.usage(2_000_000_000L, 4_000_000_000L,
                1_000_000_000L, 8);
        equal(2.0, usage.processCpuSeconds());
        equal(2.0, usage.averageLogicalCores());
        equal(25.0, usage.percentAvailableLogicalCapacity());
        equal(0.5, DensityProbeTelemetry.seconds(500_000_000L));
        unavailable(DensityProbeTelemetry.usage(null, 4L, 1L, 8));
        unavailable(DensityProbeTelemetry.usage(1L, -1L, 1L, 8));
        unavailable(DensityProbeTelemetry.usage(4L, 3L, 1L, 8));
        unavailable(DensityProbeTelemetry.usage(1L, 2L, 0L, 8));
        unavailable(DensityProbeTelemetry.usage(1L, 2L, 1L, 0));
        var before = new DensityProbeTelemetry.InferenceCounters(10, 8, 1_000_000_000L, 2_000_000_000L);
        var after = new DensityProbeTelemetry.InferenceCounters(13, 11, 1_500_000_000L, 4_000_000_000L);
        var inference = DensityProbeTelemetry.inferenceDelta(before, after);
        if (inference.submitted() != 3 || inference.completed() != 3)
            throw new AssertionError("inference task deltas changed: " + inference);
        equal(0.5, inference.queueSeconds());
        equal(2.0, inference.executionSeconds());
        unavailable(DensityProbeTelemetry.inferenceDelta(null, after));
        unavailable(DensityProbeTelemetry.inferenceDelta(before,
                new DensityProbeTelemetry.InferenceCounters(1, 1, 0, 0)));
        Long sampled = DensityProbeTelemetry.processCpuNanos();
        if (sampled != null && sampled < 0) throw new AssertionError("negative CPU sample");
        System.out.println("DensityProbeTelemetryTest passed");
    }
}
