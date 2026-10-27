/* Stream a JFR file through jdk.jfr.consumer without serializing recursive metadata.
 *
 * ObjectAllocationSample weight is a sampled estimate, not an exact allocation
 * counter. ExecutionSample is sampled CPU activity. ThreadAllocationStatistics
 * reports cumulative thread counters; this tool reports first-to-last deltas by
 * Java thread id and does not add periodic snapshots together.
 *
 * Usage: java JfrSummary.java recording.jfr [top]
 */
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class JfrSummary {
    record ThreadPoint(Instant when, long bytes) {}
    static String thread(RecordedThread t) {
        return t == null ? "<unknown>#?" : (t.getJavaName() == null ? "<unnamed>" : t.getJavaName()) + "#" + t.getJavaThreadId();
    }
    static String stack(RecordedEvent e) {
        RecordedStackTrace trace = e.getStackTrace();
        if (trace == null) return "<no Java stack>";
        var parts = new ArrayList<String>();
        for (RecordedFrame f : trace.getFrames()) {
            var m = f.getMethod();
            parts.add(m.getType().getName() + "." + m.getName());
            if (parts.size() == 32) break;
        }
        return parts.isEmpty() ? "<no Java stack>" : String.join(" <- ", parts);
    }
    static void add(Map<String, Long> map, String key, long value) { map.merge(key, value, Long::sum); }
    static String esc(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    static void table(StringBuilder out, String name, Map<String, Long> values, int top) {
        out.append("\"").append(name).append("\":[");
        values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(top).forEachOrdered(entry -> {
            if (out.charAt(out.length() - 1) != '[') out.append(',');
            out.append("{\"key\":\"").append(esc(entry.getKey())).append("\",\"value\":").append(entry.getValue()).append('}');
        });
        out.append(']');
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("usage: java JfrSummary.java recording.jfr [top]");
        int top = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        var allocationClass = new HashMap<String, Long>(); var allocationThread = new HashMap<String, Long>();
        var allocationStack = new HashMap<String, Long>(); var cpuStack = new HashMap<String, Long>();
        var counts = new HashMap<String, Long>(); var threadCounters = new HashMap<String, ArrayList<ThreadPoint>>(); long gcPauseNs = 0;
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent(); String kind = event.getEventType().getName(); add(counts, kind, 1);
                switch (kind) {
                    case "jdk.ObjectAllocationSample" -> {
                        long weight = event.getLong("weight");
                        RecordedClass c = event.getClass("objectClass"); String cls = c == null ? "<unknown class>" : c.getName();
                        String t = thread(event.getThread("eventThread")); add(allocationClass, cls, weight); add(allocationThread, t, weight); add(allocationStack, stack(event), weight);
                    }
                    case "jdk.ExecutionSample" -> add(cpuStack, stack(event), 1);
                    case "jdk.ThreadAllocationStatistics" -> threadCounters.computeIfAbsent(thread(event.getThread("thread")), ignored -> new ArrayList<>()).add(new ThreadPoint(event.getStartTime(), event.getLong("allocated")));
                    case "jdk.GCPhasePause" -> gcPauseNs += event.getDuration().toNanos();
                }
            }
        }
        var deltas = new HashMap<String, Long>(); var intervals = new HashMap<String, Long>();
        for (var entry : threadCounters.entrySet()) { var samples = entry.getValue(); samples.sort(Comparator.comparing(ThreadPoint::when));
            ThreadPoint a = samples.getFirst(), b = samples.getLast(); deltas.put(entry.getKey(), Math.max(0, b.bytes() - a.bytes())); intervals.put(entry.getKey(), Math.max(0, Duration.between(a.when(), b.when()).toMillis())); }
        var out = new StringBuilder("{"); out.append("\"recording\":\"").append(esc(args[0])).append("\",");
        out.append("\"method\":{\"allocations\":\"ObjectAllocationSample weight is estimated bytes\",\"cpu\":\"ExecutionSample count\",\"thread_allocation\":\"first-to-last cumulative counter delta by Java thread id\"},");
        table(out, "event_counts", counts, 100); out.append(','); table(out, "allocation_estimated_bytes_by_class", allocationClass, top); out.append(','); table(out, "allocation_estimated_bytes_by_thread", allocationThread, top); out.append(','); table(out, "allocation_estimated_bytes_by_stack", allocationStack, top); out.append(','); table(out, "cpu_sample_count_by_stack", cpuStack, top); out.append(',');
        out.append("\"thread_allocation_delta\":[");
        deltas.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(top).forEachOrdered(entry -> { if (out.charAt(out.length()-1) != '[') out.append(','); long millis=intervals.get(entry.getKey()); out.append("{\"key\":\"").append(esc(entry.getKey())).append("\",\"bytes\":").append(entry.getValue()).append(",\"interval_ms\":").append(millis).append(",\"bytes_per_second\":").append(millis == 0 ? "null" : Long.toString(entry.getValue()*1000/millis)).append('}'); });
        out.append("],\"gc_phase_pause_ns\":").append(gcPauseNs).append('}'); System.out.println(out);
    }
}
