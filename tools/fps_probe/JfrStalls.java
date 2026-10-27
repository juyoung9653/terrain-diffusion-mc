/*
 * Bounded post-processing for a completed client JFR recording.
 *
 * Usage: java JfrStalls.java recording.jfr [sample_start_iso fps-frames.csv]
 *
 * ExecutionSample and NativeMethodSample are samples, not elapsed CPU time.
 * ThreadAllocationStatistics is intentionally not handled here; use the
 * existing memory_probe JfrSummary for allocation-counter deltas.
 */
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class JfrStalls {
    private static final int TOP = 30;
    private static final int LONG_FRAME_LIMIT = 100;

    private record DurationEvent(Instant when, long durationNs, String type,
                                 String operation, String thread, String stack) { }
    private record SampleEvent(Instant when, String type, String stack) { }
    private record FrameWindow(long frame, double frameMs, Instant start, Instant end) { }
    private record FrameWindows(List<FrameWindow> values, boolean truncated) { }

    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static String thread(RecordedThread value) {
        if (value == null) return "<unknown>";
        String name = value.getJavaName();
        return (name == null ? "<unnamed>" : name) + "#" + value.getJavaThreadId();
    }

    private static RecordedThread fieldThread(RecordedEvent event, String name) {
        try { return event.getThread(name); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static String fieldString(RecordedEvent event, String name) {
        try { return event.getString(name); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static long durationNs(RecordedEvent event) {
        try { return Math.max(0L, event.getDuration().toNanos()); }
        catch (RuntimeException ignored) { return 0L; }
    }

    private static String stack(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace();
        if (trace == null) return "<no Java stack>";
        StringBuilder out = new StringBuilder();
        for (RecordedFrame frame : trace.getFrames()) {
            if (out.length() > 0) out.append(" <- ");
            out.append(frame.getMethod().getType().getName()).append('.')
                .append(frame.getMethod().getName());
            if (out.length() > 4096) break;
        }
        return out.length() == 0 ? "<no Java stack>" : out.toString();
    }

    private static void increment(Map<String, Long> values, String key) {
        values.merge(key, 1L, Long::sum);
    }

    private static Map<String, Long> categories(Map<String, Long> stacks) {
        Map<String, Long> result = new HashMap<>();
        for (var entry : stacks.entrySet()) {
            String s = entry.getKey().toLowerCase();
            String category = s.contains("respawnrecap") ? "Respawn Recap" :
                s.contains("entity_model_features") ? "EMF entity models" :
                s.contains("geckolib") || s.contains("hybridaquatic") || s.contains("hybrid_aquatic") ? "Gecko/Hybrid Aquatic models" :
                s.contains("xaero.") ? "Xaero maps" :
                s.contains("animatetick") ? "client animate tick" :
                s.contains("net.irisshaders") ? "Iris/shader path" :
                s.contains("sodium") ? "Sodium terrain" : "other Java";
            result.merge(category, entry.getValue(), Long::sum);
        }
        return result;
    }

    private static boolean isRenderThread(RecordedEvent event) {
        RecordedThread sampled = fieldThread(event, "sampledThread");
        RecordedThread eventThread = sampled == null ? fieldThread(event, "eventThread") : sampled;
        return eventThread != null && "Render thread".equals(eventThread.getJavaName());
    }

    private static boolean isRenderWaitOrIo(String type, String eventThread) {
        return eventThread.startsWith("Render thread#") && (
            type.equals("jdk.ThreadPark") || type.equals("jdk.JavaMonitorWait") ||
            type.equals("jdk.JavaMonitorEnter") || type.equals("jdk.ThreadSleep") ||
            type.equals("jdk.FileRead") || type.equals("jdk.FileWrite") ||
            type.equals("jdk.SocketRead") || type.equals("jdk.SocketWrite"));
    }

    private static boolean isGlobalStall(String type) {
        return type.equals("jdk.GCPhasePause") || type.equals("jdk.SafepointBegin") || type.equals("jdk.ExecuteVMOperation") ||
            type.equals("jdk.ZAllocationStall");
    }

    private static void writeCounts(StringBuilder out, Map<String, Long> values) {
        out.append('{'); boolean first = true;
        for (var entry : values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            if (!first) out.append(','); first = false;
            out.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        out.append('}');
    }

    private static void writeTopStacks(StringBuilder out, Map<String, Long> values, int limit) {
        out.append('['); boolean first = true;
        for (var entry : values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit).toList()) {
            if (!first) out.append(','); first = false;
            out.append("{\"stack\":").append(quote(entry.getKey()))
                .append(",\"samples\":").append(entry.getValue()).append('}');
        }
        out.append(']');
    }

    private static void writeDurationEvents(StringBuilder out, List<DurationEvent> values) {
        out.append('['); boolean first = true;
        for (DurationEvent event : values.stream()
                .sorted(Comparator.comparingLong(DurationEvent::durationNs).reversed())
                .limit(TOP).toList()) {
            if (!first) out.append(','); first = false;
            out.append("{\"time\":").append(quote(event.when().toString()))
                .append(",\"duration_ns\":").append(event.durationNs())
                .append(",\"type\":").append(quote(event.type()))
                .append(",\"operation\":").append(quote(event.operation()))
                .append(",\"thread\":").append(quote(event.thread()))
                .append(",\"stack\":").append(quote(event.stack())).append('}');
        }
        out.append(']');
    }

    private static FrameWindows readLongFrames(Instant sampleStart, Path csv) throws Exception {
        List<FrameWindow> result = new ArrayList<>();
        Instant cursor = sampleStart;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(csv)) {
            String header = reader.readLine();
            if (header == null) throw new IllegalArgumentException("empty frame CSV");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length < 2) continue;
                long frame = Long.parseLong(parts[0].trim());
                double ms = Double.parseDouble(parts[1].trim());
                long ns = Math.max(0L, Math.round(ms * 1_000_000.0));
                Instant end = cursor.plusNanos(ns);
                if (ms > 50.0) {
                    if (result.size() < LONG_FRAME_LIMIT) result.add(new FrameWindow(frame, ms, cursor, end));
                    else truncated = true;
                }
                cursor = end;
            }
        }
        return new FrameWindows(result, truncated);
    }

    private static boolean overlaps(Instant point, long durationNs, FrameWindow window) {
        Instant end = point.plusNanos(Math.max(0L, durationNs));
        return !end.isBefore(window.start()) && !point.isAfter(window.end());
    }

    private static void writeLongFrames(StringBuilder out, List<FrameWindow> windows,
                                        List<DurationEvent> durations, List<SampleEvent> samples) {
        out.append('['); boolean firstWindow = true;
        for (FrameWindow window : windows) {
            if (!firstWindow) out.append(','); firstWindow = false;
            Map<String, Long> stacks = new HashMap<>(), events = new HashMap<>();
            for (SampleEvent sample : samples)
                if (overlaps(sample.when(), 0L, window)) {
                    increment(stacks, sample.type() + " :: " + sample.stack());
                    increment(events, sample.type());
                }
            for (DurationEvent event : durations)
                if (overlaps(event.when(), event.durationNs(), window))
                    increment(events, event.type() + (event.operation() == null ? "" : ":" + event.operation()));
            out.append("{\"frame\":").append(window.frame())
                .append(",\"frame_ms\":").append(window.frameMs())
                .append(",\"start\":").append(quote(window.start().toString()))
                .append(",\"end\":").append(quote(window.end().toString()))
                .append(",\"overlapping_event_counts\":");
            writeCounts(out, events); out.append(",\"render_sample_stacks\":");
            writeTopStacks(out, stacks, 10); out.append('}');
        }
        out.append(']');
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 3)
            throw new IllegalArgumentException("usage: JfrStalls recording.jfr [sample_start_iso fps-frames.csv]");
        FrameWindows frameWindows = args.length == 3
            ? readLongFrames(Instant.parse(args[1]), Path.of(args[2]))
            : new FrameWindows(List.of(), false);
        List<FrameWindow> longFrames = frameWindows.values();
        Map<String, Long> eventCounts = new HashMap<>(), vmOperations = new HashMap<>();
        Map<String, Long> renderExecution = new HashMap<>(), renderNative = new HashMap<>();
        List<DurationEvent> durationEvents = new ArrayList<>();
        List<SampleEvent> renderSamples = new ArrayList<>();
        long pauseCount = 0L, pauseSum = 0L, pauseMax = 0L;
        try (RecordingFile recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                increment(eventCounts, type);
                String operation = type.equals("jdk.ExecuteVMOperation") ? fieldString(event, "operation") : null;
                if (operation != null) increment(vmOperations, operation);
                long duration = durationNs(event);
                if (type.equals("jdk.GCPhasePause")) {
                    pauseCount++; pauseSum += duration; pauseMax = Math.max(pauseMax, duration);
                }
                String eventThread = thread(fieldThread(event, "eventThread"));
                if (isRenderWaitOrIo(type, eventThread) || isGlobalStall(type))
                    durationEvents.add(new DurationEvent(event.getStartTime(), duration, type,
                                                         operation, eventThread, stack(event)));
                if (type.equals("jdk.ExecutionSample") && isRenderThread(event)) {
                    String trace = stack(event); increment(renderExecution, trace);
                    renderSamples.add(new SampleEvent(event.getStartTime(), type, trace));
                } else if (type.equals("jdk.NativeMethodSample") && isRenderThread(event)) {
                    String trace = stack(event); increment(renderNative, trace);
                    renderSamples.add(new SampleEvent(event.getStartTime(), type, trace));
                }
            }
        }
        StringBuilder out = new StringBuilder("{");
        out.append("\"render_execution_sample_categories\":");
        writeCounts(out, categories(renderExecution)); out.append(',');
        out.append("\"recording\":").append(quote(args[0]))
            .append(",\"method\":{\"render_samples\":\"ExecutionSample and NativeMethodSample are samples, not elapsed CPU time\",\"gc_pause\":\"GCPhasePause only; concurrent GC is not added\",\"long_frame_windows\":\"frame CSV cumulative intervals anchored to supplied sample start\"}")
            .append(",\"event_counts\":"); writeCounts(out, eventCounts);
        out.append(",\"execute_vm_operation_counts\":"); writeCounts(out, vmOperations);
        out.append(",\"gc_phase_pause\":{\"count\":").append(pauseCount)
            .append(",\"sum_ns\":").append(pauseSum).append(",\"max_ns\":").append(pauseMax).append('}');
        out.append(",\"top_duration_events\":"); writeDurationEvents(out, durationEvents);
        out.append(",\"render_execution_samples_by_stack\":"); writeTopStacks(out, renderExecution, TOP);
        out.append(",\"render_native_method_samples_by_stack\":"); writeTopStacks(out, renderNative, TOP);
        out.append(",\"long_frame_window_count\":").append(longFrames.size())
            .append(",\"long_frame_windows_truncated\":").append(frameWindows.truncated())
            .append(",\"long_frame_windows\":"); writeLongFrames(out, longFrames, durationEvents, renderSamples);
        out.append('}');
        System.out.println(out);
    }
}
