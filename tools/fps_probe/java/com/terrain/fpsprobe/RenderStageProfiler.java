package com.terrain.fpsprobe;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Opt-in, render-thread-only stage timing. GPU timestamps are deliberately
 * polled only when already available; this class never waits for the GPU.
 */
public final class RenderStageProfiler {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("fpsprobe.renderStages", "false"));
    private static final int QUERY_RING = 2048;
    private static final int MAX_RECORDS = 1_000_000;
    private static final ArrayDeque<Active> STACK = new ArrayDeque<>();
    private static final List<Record> RECORDS = new ArrayList<>();
    private static final Slot[] SLOTS = new Slot[QUERY_RING];
    private static boolean initialized, supported, measuring;
    private static long frame;
    private static int cursor, invalid, dropped, recordDropped, gpuErrors;

    private RenderStageProfiler() {}

    /** Call at the first render hook each frame, including warmup frames. */
    public static void frameStart(boolean sampleMeasuring) {
        if (!ENABLED) return;
        initialize();
        poll();
        if (!STACK.isEmpty()) {
            invalid += STACK.size();
            closeOpenStages();
        }
        measuring = sampleMeasuring;
        if (measuring) frame++;
    }

    /** Starts an inclusive stage. Calls must be balanced on the render thread. */
    public static void begin(String label) {
        if (!ENABLED || !measuring) return;
        if (label == null || label.isEmpty()) { invalid++; return; }
        Active active = new Active(label, System.nanoTime());
        active.slot = claimSlot();
        if (active.slot != null) {
            try { GL33.glQueryCounter(active.slot.start, GL33.GL_TIMESTAMP); }
            catch (Throwable error) { gpuErrors++; release(active.slot); active.slot = null; }
        }
        STACK.push(active);
    }

    /** Ends the most recently started stage of the same label. */
    public static void end(String label) {
        if (!ENABLED || !measuring) return;
        if (STACK.isEmpty() || label == null || !label.equals(STACK.peek().label)) { invalid++; return; }
        complete(STACK.pop());
    }

    /** Call at the last render hook each frame. It repairs only diagnostic state. */
    public static void frameEnd() {
        if (!ENABLED) return;
        // Post-render and present hooks can legitimately close stages after this
        // event. Orphans are diagnosed and closed at the next frame start.
        poll();
    }

    /** Writes data under the disposable run directory and releases query objects. */
    public static void finish(Path directory) {
        if (!ENABLED) return;
        measuring = false;
        closeOpenStages();
        poll();
        try {
            Files.createDirectories(directory);
            writeCsv(directory.resolve("render-stages.csv"));
            writeSummary(directory.resolve("render-stages-summary.json"));
        } catch (IOException ignored) {
            // The FPS receipt remains authoritative if this optional diagnostic cannot write.
        } finally { cleanup(); }
    }

    private static void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            if (!GL.getCapabilities().OpenGL33) return;
            for (int i = 0; i < QUERY_RING; i++) SLOTS[i] = new Slot(GL15.glGenQueries(), GL15.glGenQueries());
            supported = true;
        } catch (Throwable error) { gpuErrors++; cleanup(); }
    }

    private static Slot claimSlot() {
        if (!supported) return null;
        for (int i = 0; i < QUERY_RING; i++) {
            Slot slot = SLOTS[(cursor + i) % QUERY_RING];
            if (!slot.pending && !slot.inUse) { slot.inUse = true; cursor = (cursor + i + 1) % QUERY_RING; return slot; }
        }
        dropped++;
        return null;
    }

    private static void complete(Active active) {
        long end = System.nanoTime();
        if (RECORDS.size() >= MAX_RECORDS) { recordDropped++; release(active.slot); return; }
        Record record = new Record(frame, active.label, Math.max(0L, end - active.cpuStart));
        RECORDS.add(record);
        if (active.slot == null) return;
        try {
            GL33.glQueryCounter(active.slot.end, GL33.GL_TIMESTAMP);
            active.slot.record = record;
            active.slot.pending = true;
        } catch (Throwable error) { gpuErrors++; release(active.slot); }
    }

    private static void closeOpenStages() { while (!STACK.isEmpty()) complete(STACK.pop()); }

    private static void poll() {
        if (!supported) return;
        for (Slot slot : SLOTS) if (slot != null && slot.pending) {
            try {
                if (GL15.glGetQueryObjecti(slot.end, GL15.GL_QUERY_RESULT_AVAILABLE) != GL11.GL_TRUE) continue;
                long start = GL33.glGetQueryObjecti64(slot.start, GL15.GL_QUERY_RESULT);
                long end = GL33.glGetQueryObjecti64(slot.end, GL15.GL_QUERY_RESULT);
                if (slot.record != null && end >= start) slot.record.gpuNs = end - start;
                else gpuErrors++;
                release(slot);
            } catch (Throwable error) { gpuErrors++; release(slot); }
        }
    }

    private static void release(Slot slot) {
        if (slot == null) return;
        slot.pending = false; slot.inUse = false; slot.record = null;
    }

    private static void writeCsv(Path path) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write("frame,label,cpu_wall_ns,gpu_elapsed_ns\n");
            for (Record r : RECORDS) out.write(r.frame + ",\"" + csv(r.label) + "\"," + r.cpuNs + "," + r.gpuNs + "\n");
        }
    }

    private static void writeSummary(Path path) throws IOException {
        Map<String, Totals> totals = new LinkedHashMap<>();
        for (Record r : RECORDS) totals.computeIfAbsent(r.label, ignored -> new Totals()).add(r);
        int pending = 0;
        for (Slot slot : SLOTS) if (slot != null && slot.pending) pending++;
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write("{\n  \"enabled\": true,\n  \"gpu_timestamp_supported\": " + supported + ",\n");
            out.write("  \"frames\": " + frame + ",\n  \"records\": " + RECORDS.size() + ",\n");
            out.write("  \"invalid_calls\": " + invalid + ",\n  \"query_dropped\": " + dropped + ",\n  \"record_dropped\": " + recordDropped + ",\n  \"gpu_errors\": " + gpuErrors + ",\n  \"pending_queries_at_finish\": " + pending + ",\n");
            out.write("  \"gpu_caveat\": \"GPU timestamp elapsed is a timeline duration, not a GPU-busy counter; unavailable or unresolved values are -1. No waits were issued.\",\n  \"stages\": {\n");
            boolean first = true;
            for (Map.Entry<String, Totals> entry : totals.entrySet()) {
                if (!first) out.write(",\n"); first = false;
                Totals t = entry.getValue();
                out.write("    \"" + json(entry.getKey()) + "\": {\"calls\": " + t.calls + ", \"cpu_wall_sum_ns\": " + t.cpu + ", \"cpu_wall_mean_ns\": " + (t.calls == 0 ? 0 : t.cpu / t.calls) + ", \"gpu_elapsed_samples\": " + t.gpuCalls + ", \"gpu_elapsed_sum_ns\": " + t.gpu + ", \"gpu_elapsed_mean_ns\": " + (t.gpuCalls == 0 ? -1 : t.gpu / t.gpuCalls) + "}");
            }
            out.write("\n  }\n}\n");
        }
    }

    private static void cleanup() {
        for (int i = 0; i < SLOTS.length; i++) {
            Slot slot = SLOTS[i];
            if (slot == null) continue;
            try { GL15.glDeleteQueries(slot.start); GL15.glDeleteQueries(slot.end); } catch (Throwable ignored) {}
            SLOTS[i] = null;
        }
        supported = false;
    }

    private static String csv(String value) { return value.replace("\"", "\"\""); }
    private static String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }

    private static final class Active { final String label; final long cpuStart; Slot slot; Active(String label, long cpuStart) { this.label = label; this.cpuStart = cpuStart; } }
    private static final class Record { final long frame, cpuNs; final String label; long gpuNs = -1; Record(long frame, String label, long cpuNs) { this.frame = frame; this.label = label; this.cpuNs = cpuNs; } }
    private static final class Slot { final int start, end; boolean pending, inUse; Record record; Slot(int start, int end) { this.start = start; this.end = end; } }
    private static final class Totals { long calls, cpu, gpu, gpuCalls; void add(Record r) { calls++; cpu += r.cpuNs; if (r.gpuNs >= 0) { gpuCalls++; gpu += r.gpuNs; } } }
}
