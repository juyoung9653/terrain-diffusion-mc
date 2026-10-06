package com.github.xandergos.terraindiffusionmc.pipeline;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs production PipelineModels against model-free stubs. */
public final class PipelineModelsLifecycleHarness {
    private static final int WAITER_COUNT = 48;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("shutdown")) {
            checkShutdownWaitsForNativeConstruction();
            System.out.println("PipelineModelsLifecycleHarness: shutdown PASS");
            return;
        }
        checkSuccessfulCloseReload();
        checkFailedLoadIsSharedAndSticky();
        System.out.println("PipelineModelsLifecycleHarness: PASS");
    }

    private static void checkShutdownWaitsForNativeConstruction() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AtomicBoolean restored = new AtomicBoolean();
        OnnxModel.blockConstruction(entered, release);
        PipelineModels.load();
        require(entered.await(5, TimeUnit.SECONDS), "native constructor did not start");
        Thread closer = new Thread(() -> {
            Thread.currentThread().interrupt();
            stopping.countDown();
            PipelineModels.stopLoadingAndAwait();
            restored.set(Thread.currentThread().isInterrupted());
            stopped.countDown();
        }, "pipeline-models-shutdown");
        closer.start();
        require(stopping.await(5, TimeUnit.SECONDS), "shutdown did not start");
        require(!stopped.await(100, TimeUnit.MILLISECONDS), "shutdown returned during native construction");
        release.countDown();
        require(stopped.await(5, TimeUnit.SECONDS), "shutdown did not finish after construction");
        closer.join(5000);
        require(restored.get(), "shutdown lost caller interruption");
        PipelineModels loaded = PipelineModels.getInstance();
        require(loaded != null, "shutdown interrupted native construction");
        PipelineModels.awaitLoad();
        require(PipelineModels.getInstance() == loaded, "loaded models unavailable for same-JVM diagnostic reopen");
        loaded.close();
        PipelineModels.load();
        require(PipelineModels.getInstance() == null && ModelAssetManager.ensureCalls() == 1,
                "shutdown admitted new native construction");
        PipelineModels.stopLoadingAndAwait();
    }

    private static void checkSuccessfulCloseReload() {
        PipelineModels.awaitLoad();
        PipelineModels first = PipelineModels.getInstance();
        require(first != null, "initial model load did not publish an instance");
        first.close();
        require(PipelineModels.getInstance() == null, "close did not release the shared instance");

        PipelineModels.awaitLoad();
        PipelineModels second = PipelineModels.getInstance();
        require(second != null && second != first, "close did not permit a fresh load");
        second.close();
        require(ModelAssetManager.ensureCalls() == 2, "expected exactly two successful loads");
    }

    private static void checkFailedLoadIsSharedAndSticky() throws Exception {
        UnsatisfiedLinkError originalFailure = new UnsatisfiedLinkError("simulated ONNX native load failure");
        CountDownLatch errorEntered = new CountDownLatch(1);
        CountDownLatch releaseErrors = new CountDownLatch(1);
        ModelAssetManager.failWith(originalFailure);
        LoggerFactory.blockErrors(errorEntered, releaseErrors);

        PipelineModels.load();
        require(errorEntered.await(5, TimeUnit.SECONDS), "loader did not reach the simulated native failure");

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger matchingFailures = new AtomicInteger();
        List<Thread> waiters = new ArrayList<>();
        for (int index = 0; index < WAITER_COUNT; index++) {
            Thread waiter = new Thread(() -> {
                try {
                    start.await();
                    PipelineModels.awaitLoad();
                    throw new AssertionError("awaitLoad unexpectedly succeeded");
                } catch (IllegalStateException exception) {
                    if (exception.getCause() == originalFailure) {
                        matchingFailures.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }, "pipeline-models-lifecycle-waiter-" + index);
            waiters.add(waiter);
            waiter.start();
        }
        start.countDown();
        for (Thread waiter : waiters) {
            waiter.join(TimeUnit.SECONDS.toMillis(5));
            require(!waiter.isAlive(), "awaitLoad waiter blocked after native load failure");
        }
        require(matchingFailures.get() == WAITER_COUNT, "all waiters must receive the original native failure");
        require(ModelAssetManager.ensureCalls() == 3, "failed load retried unexpectedly");

        releaseErrors.countDown();
        LoggerFactory.stopBlockingErrors();
        try {
            PipelineModels.awaitLoad();
            throw new AssertionError("failed load unexpectedly recovered");
        } catch (IllegalStateException exception) {
            require(exception.getCause() == originalFailure, "later caller lost the original native failure");
        }
        require(ModelAssetManager.ensureCalls() == 3, "later caller retried failed load");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
