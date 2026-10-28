package com.github.xandergos.terraindiffusionmc.pipeline;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Model-free harness stub. */
public final class ModelAssetManager {
    private static final AtomicInteger ENSURE_CALLS = new AtomicInteger();
    private static volatile Throwable failure;

    private ModelAssetManager() {
    }

    public static void ensureAssetsReady() {
        ENSURE_CALLS.incrementAndGet();
        Throwable currentFailure = failure;
        if (currentFailure instanceof Error error) {
            throw error;
        }
        if (currentFailure instanceof RuntimeException exception) {
            throw exception;
        }
        if (currentFailure != null) {
            throw new RuntimeException(currentFailure);
        }
    }

    public static Path resolveAssetPath(String fileName) {
        return Path.of(fileName);
    }

    public static Path resolveDecoderAssetPath() {
        return resolveAssetPath("decoder_model.onnx");
    }
    public static boolean decoderUsesPreoptimizedGpuGraph() { return false; }

    static void failWith(Throwable value) {
        failure = value;
    }

    static int ensureCalls() {
        return ENSURE_CALLS.get();
    }
}
