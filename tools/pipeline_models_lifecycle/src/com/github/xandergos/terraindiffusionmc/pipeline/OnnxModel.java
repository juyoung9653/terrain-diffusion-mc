package com.github.xandergos.terraindiffusionmc.pipeline;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Model-free harness stub. */
public final class OnnxModel implements AutoCloseable {
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch release;

    static void blockConstruction(CountDownLatch started, CountDownLatch finish) {
        entered = started;
        release = finish;
    }

    OnnxModel(Path path, String name) {
        if (entered != null) {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Native construction was interrupted", interrupted);
            }
        }
    }

    OnnxModel(Path path, String name, boolean preoptimizedGpuGraph) {
        this(path, name);
    }

    @Override
    public void close() {
    }
}
