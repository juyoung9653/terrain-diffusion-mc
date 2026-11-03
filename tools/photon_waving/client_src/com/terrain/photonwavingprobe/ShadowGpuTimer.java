package com.terrain.photonwavingprobe;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Render-thread-only timestamp instrumentation for the clone benchmark.
 *
 * <p>GL timestamps describe elapsed time on the OpenGL timeline. They are not a
 * GPU-busy counter, and work submitted through PureScale's D3D12 bridge is not
 * necessarily covered by these timestamps.</p>
 */
public final class ShadowGpuTimer {
    private static final int RING_SIZE = 64;
    private static final boolean ENABLED = Boolean.getBoolean("photonwaving.benchmark");
    private static final ShadowGpuTimer INSTANCE = new ShadowGpuTimer();

    private final QuerySlot[] intervals = slots();
    private final QuerySlot[] shadows = slots();
    private final List<Sample> intervalSamples = new ArrayList<>();
    private final List<Sample> shadowSamples = new ArrayList<>();
    private final List<WallSample> frameWallSamples = new ArrayList<>();
    private boolean installed;
    private boolean measuring;
    private boolean started;
    private boolean initialized;
    private boolean supported;
    private int intervalCursor;
    private int shadowCursor;
    private int intervalOpen = -1;
    private int shadowOpen = -1;
    private boolean intervalSkippedStart;
    private boolean shadowSkippedStart;
    private long currentFrame;
    private long previousFrameStartNs = -1L;
    private int shadowPassInFrame;
    private long intervalSubmitted, shadowSubmitted;
    private long intervalDropped, shadowDropped;
    private long intervalUnbalanced, shadowUnbalanced;
    private long queryFailures;
    private long glfwFocusedFrameCount, glfwUnfocusedFrameCount;

    private ShadowGpuTimer() {}

    /** Registers only when explicitly requested with -Dphotonwaving.benchmark=true. */
    public static void install() {
        if (!ENABLED || INSTANCE.installed) return;
        INSTANCE.installed = true;
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> INSTANCE.frameStart());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> INSTANCE.frameEnd());
    }

    /** Must be called on the render thread after the static scene has settled. */
    public static void startMeasurement() {
        if (ENABLED) INSTANCE.start();
    }

    /** Called by the Iris mixin at renderShadows HEAD. */
    public static void shadowPassStart() {
        if (ENABLED) INSTANCE.shadowStart();
    }

    /** Called by the Iris mixin at renderShadows RETURN. */
    public static void shadowPassEnd() {
        if (ENABLED) INSTANCE.shadowEnd();
    }

    /**
     * Polls only already-available results, writes CSV/JSON, and never calls
     * glFinish or waits for outstanding GPU work.
     */
    public static void stopAndWrite(Path outputDir) throws IOException {
        if (ENABLED) INSTANCE.stop(outputDir);
    }

    private void start() {
        if (started) throw new IllegalStateException("Shadow GPU timer may be started once per client");
        started = true;
        // The next Pre establishes the origin, so warm-up time is never sampled.
        previousFrameStartNs = -1L;
        measuring = true;
    }

    private void frameStart() {
        if (!measuring) return;
        if (glfwFocused()) glfwFocusedFrameCount++;
        else glfwUnfocusedFrameCount++;
        long now = System.nanoTime();
        currentFrame++;
        if (previousFrameStartNs >= 0L) frameWallSamples.add(new WallSample(currentFrame, now - previousFrameStartNs));
        previousFrameStartNs = now;
        shadowPassInFrame = 0;
        pollAvailable();
        begin(intervals, true, currentFrame, 0);
    }

    private void frameEnd() {
        if (!measuring) return;
        end(intervals, true);
        pollAvailable();
    }

    private void shadowStart() {
        if (!measuring) return;
        begin(shadows, false, currentFrame, ++shadowPassInFrame);
    }

    private void shadowEnd() {
        if (!measuring) return;
        end(shadows, false);
    }

    private void begin(QuerySlot[] slots, boolean interval, long frame, int pass) {
        initialize();
        if (!supported) return;
        int open = interval ? intervalOpen : shadowOpen;
        if (open >= 0) {
            if (interval) intervalUnbalanced++; else shadowUnbalanced++;
            return;
        }
        int cursor = interval ? intervalCursor : shadowCursor;
        for (int offset = 0; offset < RING_SIZE; offset++) {
            int index = (cursor + offset) % RING_SIZE;
            QuerySlot slot = slots[index];
            if (!slot.pending) {
                try {
                    GL33.glQueryCounter(slot.startQuery, GL33.GL_TIMESTAMP);
                    slot.frame = frame;
                    slot.pass = pass;
                    if (interval) intervalOpen = index; else shadowOpen = index;
                } catch (Throwable error) {
                    queryFailures++;
                }
                return;
            }
        }
        if (interval) {
            intervalDropped++;
            intervalSkippedStart = true;
        } else {
            shadowDropped++;
            shadowSkippedStart = true;
        }
    }

    private void end(QuerySlot[] slots, boolean interval) {
        if (!supported) return;
        int open = interval ? intervalOpen : shadowOpen;
        if (open < 0) {
            // A full async ring deliberately omits this matching pair.
            if (interval && intervalSkippedStart) intervalSkippedStart = false;
            else if (!interval && shadowSkippedStart) shadowSkippedStart = false;
            else if (interval) intervalUnbalanced++;
            else shadowUnbalanced++;
            return;
        }
        QuerySlot slot = slots[open];
        try {
            GL33.glQueryCounter(slot.endQuery, GL33.GL_TIMESTAMP);
            slot.pending = true;
            if (interval) {
                intervalSubmitted++;
                intervalCursor = (open + 1) % RING_SIZE;
                intervalOpen = -1;
            } else {
                shadowSubmitted++;
                shadowCursor = (open + 1) % RING_SIZE;
                shadowOpen = -1;
            }
        } catch (Throwable error) {
            queryFailures++;
            if (interval) intervalOpen = -1; else shadowOpen = -1;
        }
    }

    private void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            if (!GL.getCapabilities().OpenGL33) return;
            createQueries(intervals);
            createQueries(shadows);
            supported = true;
        } catch (Throwable error) {
            queryFailures++;
            deleteQueries(intervals);
            deleteQueries(shadows);
            supported = false;
        }
    }

    private static void createQueries(QuerySlot[] slots) {
        for (QuerySlot slot : slots) {
            slot.startQuery = GL15.glGenQueries();
            slot.endQuery = GL15.glGenQueries();
        }
    }

    private void pollAvailable() {
        if (!supported) return;
        poll(intervals, intervalSamples);
        poll(shadows, shadowSamples);
    }

    private void poll(QuerySlot[] slots, List<Sample> output) {
        for (QuerySlot slot : slots) {
            if (!slot.pending) continue;
            try {
                if (GL15.glGetQueryObjecti(slot.endQuery, GL15.GL_QUERY_RESULT_AVAILABLE) != GL11.GL_TRUE) continue;
                long start = GL33.glGetQueryObjecti64(slot.startQuery, GL15.GL_QUERY_RESULT);
                long end = GL33.glGetQueryObjecti64(slot.endQuery, GL15.GL_QUERY_RESULT);
                slot.pending = false;
                if (end >= start) output.add(new Sample(slot.frame, slot.pass, start, end));
                else queryFailures++;
            } catch (Throwable error) {
                slot.pending = false;
                queryFailures++;
            }
        }
    }

    private void stop(Path outputDir) throws IOException {
        if (!started) return;
        measuring = false;
        if (intervalOpen >= 0) { intervalUnbalanced++; intervalOpen = -1; }
        if (shadowOpen >= 0) { shadowUnbalanced++; shadowOpen = -1; }
        pollAvailable();
        Files.createDirectories(outputDir);
        writeWallCsv(outputDir.resolve("frame-wall-times.csv"));
        writeCsv(outputDir.resolve("frame-gpu-timestamps.csv"), intervalSamples, "interval_elapsed_ms");
        writeCsv(outputDir.resolve("shadow-gpu-timestamps.csv"), shadowSamples, "shadow_elapsed_ms");
        writeJson(outputDir.resolve("shadow-gpu-timing.json"));
        deleteQueries(intervals);
        deleteQueries(shadows);
        supported = false;
    }

    private void writeCsv(Path path, List<Sample> samples, String elapsedColumn) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("frame,pass,start_timestamp_ns,end_timestamp_ns," + elapsedColumn + "\n");
            for (Sample sample : samples) writer.write(String.format(Locale.ROOT, "%d,%d,%d,%d,%.6f%n",
                    sample.frame, sample.pass, sample.startNs, sample.endNs, sample.elapsedNs() / 1_000_000.0));
        }
    }

    private void writeWallCsv(Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("frame,frame_wall_elapsed_ms\n");
            for (WallSample sample : frameWallSamples) writer.write(String.format(Locale.ROOT, "%d,%.6f%n",
                    sample.frame, sample.elapsedNs / 1_000_000.0));
        }
    }

    private void writeJson(Path path) throws IOException {
        String json = "{\n"
                + "  \"created_utc\": \"" + Instant.now() + "\",\n"
                + "  \"enabled\": true,\n"
                + "  \"gl_timestamp_supported\": " + supported + ",\n"
                + "  \"glfw_focused_frame_count\": " + glfwFocusedFrameCount + ",\n"
                + "  \"glfw_unfocused_frame_count\": " + glfwUnfocusedFrameCount + ",\n"
                + "  \"window_focus_definition\": \"Counts RenderFrameEvent.Pre frames while timestamp measurement is active, using GLFW.glfwGetWindowAttrib(window, GLFW_FOCUSED). Benchmarks reject any unfocused measurement frame.\",\n"
                + "  \"frame_wall_sample_count\": " + frameWallSamples.size() + ",\n"
                + "  \"frame_interval\": " + stats(intervalSamples, intervals, intervalSubmitted, intervalDropped, intervalUnbalanced) + ",\n"
                + "  \"shadow_pass\": " + stats(shadowSamples, shadows, shadowSubmitted, shadowDropped, shadowUnbalanced) + ",\n"
                + "  \"query_failures\": " + queryFailures + ",\n"
                + "  \"query_failure_definition\": \"Exceptions or invalid end-before-start values from this timer's GL timestamp queries only; GL error state is intentionally not drained so unrelated rendering diagnostics remain observable.\",\n"
                + "  \"definition\": \"OpenGL timestamp elapsed over each measured interval; GPU timeline elapsed, not a GPU-busy counter. PureScale OpenGL-to-D3D12 bridge work is not necessarily included.\"\n"
                + "}\n";
        Files.writeString(path, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String stats(List<Sample> samples, QuerySlot[] slots, long submitted, long dropped, long unbalanced) {
        return "{\"submitted\":" + submitted + ",\"completed\":" + samples.size()
                + ",\"pending\":" + pending(slots) + ",\"dropped\":" + dropped
                + ",\"unbalanced\":" + unbalanced + "}";
    }

    private static int pending(QuerySlot[] slots) {
        int count = 0;
        for (QuerySlot slot : slots) if (slot.pending) count++;
        return count;
    }

    private static boolean glfwFocused() {
        Minecraft mc = Minecraft.getInstance();
        return GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
    }

    private static QuerySlot[] slots() {
        QuerySlot[] values = new QuerySlot[RING_SIZE];
        for (int i = 0; i < values.length; i++) values[i] = new QuerySlot();
        return values;
    }

    private static void deleteQueries(QuerySlot[] slots) {
        for (QuerySlot slot : slots) {
            if (slot.startQuery != 0) GL15.glDeleteQueries(slot.startQuery);
            if (slot.endQuery != 0) GL15.glDeleteQueries(slot.endQuery);
            slot.startQuery = 0;
            slot.endQuery = 0;
            slot.pending = false;
        }
    }

    private static final class QuerySlot {
        int startQuery;
        int endQuery;
        boolean pending;
        long frame;
        int pass;
    }

    private record Sample(long frame, int pass, long startNs, long endNs) {
        long elapsedNs() { return endNs - startNs; }
    }

    private record WallSample(long frame, long elapsedNs) {}
}
