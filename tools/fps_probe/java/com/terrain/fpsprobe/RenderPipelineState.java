package com.terrain.fpsprobe;

import com.google.gson.GsonBuilder;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Read-only, once-per-second evidence of the effective SR/FG pipeline. */
public final class RenderPipelineState {
    private static final boolean ENABLED = Boolean.getBoolean("fpsprobe.renderStages")
        || Boolean.getBoolean("fpsprobe.pipelineState");
    private static final List<Map<String,Object>> ROWS = new ArrayList<>();
    private static long next;
    private static long[] lastDlssgState;
    public static void captureDlssgState(long[] state) {
        if (ENABLED) lastDlssgState = state;
    }
    public static void sample(boolean measuring) {
        if (!ENABLED || System.nanoTime() < next) return;
        next = System.nanoTime() + 1_000_000_000L;
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("epoch_ms", System.currentTimeMillis()); row.put("measuring", measuring);
        fields(row, "native", "com.purescale.client.classic.PurescaleClassicNative", "frame", "iw", "ih", "ow", "oh", "history", "prepared", "geometryReady", "fgOn", "renderedFps", "presentedFps");
        fields(row, "photon", "com.purescale.client.photon.PurescalePhotonBridge", "iw", "ih", "ow", "oh", "actualW", "actualH", "sawMarker", "packedThisFrame", "history", "successfulFrames", "failedFrames", "nextRetryNanos");
        // Observe the game's existing query. Calling getState again would consume
        // the native "presents since last query" counter and perturb its HUD.
        row.put("dlssg.state", lastDlssgState == null ? null : lastDlssgState.clone());
        try {
            Class<?> type = Class.forName("com.purescale.config.PurescaleConfig");
            Object cfg = type.getMethod("get").invoke(null);
            for (String name : new String[]{"enabled","mode","quality","frameGen","fgFactor","fgEngine","fgMinRealFps","targetFPS","neuralRendering"}) {
                try { Object value = type.getField(name).get(cfg); row.put("config."+name, value instanceof Enum<?> ? value.toString() : value); }
                catch (ReflectiveOperationException e) { row.put("config."+name, e.toString()); }
            }
            row.put("config.isFrameGenActive", type.getMethod("isFrameGenActive").invoke(cfg));
            row.put("config.wantsDlssgFrameGen", type.getMethod("wantsDlssgFrameGen").invoke(cfg));
        } catch (Throwable e) { row.put("config.error", e.toString()); }
        if (ROWS.size() < 1000) ROWS.add(row);
    }
    private static void fields(Map<String,Object> row, String prefix, String className, String... names) {
        try {
            Class<?> type = Class.forName(className);
            for (String name : names) {
                try { Field field = type.getDeclaredField(name); field.setAccessible(true); row.put(prefix+"."+name, field.get(null)); }
                catch (ReflectiveOperationException e) { row.put(prefix+"."+name, e.toString()); }
            }
        } catch (Throwable e) { row.put(prefix+".error", e.toString()); }
    }
    public static void finish(Path directory) {
        if (!ENABLED) return;
        try { Files.writeString(directory.resolve("render-pipeline-state.json"), new GsonBuilder().setPrettyPrinting().create().toJson(ROWS), StandardCharsets.UTF_8); }
        catch (Exception e) { System.err.println("Render pipeline state write failed: " + e); }
    }
}
