package com.terrain.fpsprobe;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Keeps Iris program names separate from the profiler so the instrumentation
 * does not need Iris classes on its compile classpath.
 */
public final class StageLabels {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("fpsprobe.renderStages", "false"));
    private static final Map<Integer, String> PROGRAMS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ArrayDeque<String>> QUADS = ThreadLocal.withInitial(ArrayDeque::new);

    private StageLabels() {}

    public static void program(int id, String name) {
        if (ENABLED && id > 0 && name != null && !name.isBlank()) PROGRAMS.put(id, name);
    }

    public static String quadBegin() {
        if (!ENABLED) return "iris.quad:disabled";
        int id;
        try { id = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM); }
        catch (Throwable ignored) { id = 0; }
        String label = "iris.quad:" + PROGRAMS.getOrDefault(id, "program-" + id);
        QUADS.get().push(label);
        return label;
    }

    public static String quadEnd() {
        if (!ENABLED) return "iris.quad:disabled";
        ArrayDeque<String> stack = QUADS.get();
        return stack.isEmpty() ? "iris.quad:unbalanced" : stack.pop();
    }
}
