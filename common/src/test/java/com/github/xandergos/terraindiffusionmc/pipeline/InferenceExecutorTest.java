package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Run with plain javac/java: no Minecraft, native runtime, GPU, or timing-dependent inference. */
public final class InferenceExecutorTest {
    public static void main(String[] args) throws Exception {
        cancelledFutureIsNotADrainBarrier();
        previewAndReopenedWorldAreIndependent();
        failureReleasesWorker();
        clientExitDrainsClosedPreview();
        prioritiesRespectBarriersAndPromotion();
        backgroundIsNotStarved();
        System.out.println("PASS InferenceExecutorTest: cancellation, actual drain, queued rejection, interrupted drain, preview isolation, reopen, failure, client exit, priority barriers, promotion, bounded fairness, metrics");
    }

    private static void cancelledFutureIsNotADrainBarrier() throws Exception {
        InferenceExecutor lane = new InferenceExecutor("inference-test-cancel");
        var world = lane.openScope();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean nativeInterrupted = new AtomicBoolean();
        FutureTask<Integer> active = world.submit(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException failed) { nativeInterrupted.set(true); throw failed; }
            return 1;
        });
        await(entered);
        AtomicInteger queuedCalls = new AtomicInteger();
        FutureTask<Integer> queued = world.submit(queuedCalls::incrementAndGet);
        check(lane.snapshot().active() == 1 && lane.snapshot().queued() == 1, "active/queued evidence");
        world.close();
        check(active.isDone() && active.isCancelled(), "cancelled active future reports done");
        check(queued.isCancelled() && queuedCalls.get() == 0, "queued task cancelled without invocation");
        check(lane.snapshot().active() == 1 && lane.snapshot().queued() == 0, "native wrapper still active after cancellation");
        reject(() -> world.submit(() -> 9));
        CountDownLatch draining = new CountDownLatch(1), drained = new CountDownLatch(1);
        AtomicBoolean restored = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            Thread.currentThread().interrupt();
            draining.countDown();
            world.awaitDrained();
            restored.set(Thread.currentThread().isInterrupted());
            drained.countDown();
        });
        closer.start();
        await(draining);
        check(!drained.await(100, TimeUnit.MILLISECONDS), "interrupted drain must wait for actual native return");
        release.countDown();
        await(drained);
        closer.join(5000);
        check(restored.get() && !nativeInterrupted.get(), "restore closer interruption without interrupting native call");
        check(lane.snapshot().active() == 0, "actual worker drained");
        lane.shutdownAndDrain();
    }

    private static void previewAndReopenedWorldAreIndependent() throws Exception {
        InferenceExecutor lane = new InferenceExecutor("inference-test-epochs");
        var old = lane.openScope();
        var preview = lane.openScope();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        old.submit(() -> { entered.countDown(); release.await(); return "old"; });
        await(entered);
        FutureTask<String> previewTask = preview.submit(() -> "preview");
        old.close();
        var reopened = lane.openScope();
        FutureTask<String> newTask = reopened.submit(() -> "new world");
        reject(() -> old.submit(() -> "stale producer"));
        release.countDown();
        old.awaitDrained();
        check("preview".equals(previewTask.get(5, TimeUnit.SECONDS)), "world stop preserves preview");
        check("new world".equals(newTask.get(5, TimeUnit.SECONDS)), "same-JVM world reopen");
        preview.close();
        preview.awaitDrained();
        check(reopened.submit(() -> 42).get(5, TimeUnit.SECONDS) == 42, "preview stop preserves gameplay");
        lane.shutdownAndDrain();
        reject(() -> reopened.submit(() -> 0));
        reject(lane::openScope);
    }

    private static void failureReleasesWorker() throws Exception {
        InferenceExecutor lane = new InferenceExecutor("inference-test-failure");
        var scope = lane.openScope();
        try {
            scope.submit(() -> { throw new IllegalArgumentException("model input"); }).get(5, TimeUnit.SECONDS);
            throw new AssertionError("missing failure");
        } catch (ExecutionException expected) {
            check(expected.getCause() instanceof IllegalArgumentException, "actionable exception cause preserved");
        }
        check(scope.submit(() -> 7).get(5, TimeUnit.SECONDS) == 7, "worker survives failure");
        lane.shutdownAndDrain();
        check(lane.snapshot().active() == 0 && lane.snapshot().queued() == 0, "failure cleanup drained");
    }

    private static void clientExitDrainsClosedPreview() throws Exception {
        InferenceExecutor lane = new InferenceExecutor("inference-test-preview-close");
        var preview = lane.openScope();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        preview.submit(() -> { entered.countDown(); release.await(); return null; });
        await(entered);
        preview.close(); // The UI may already have released this session when the application exits.
        CountDownLatch stopping = new CountDownLatch(1), stopped = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            stopping.countDown();
            lane.shutdownAndDrain();
            stopped.countDown();
        });
        closer.start();
        await(stopping);
        check(!stopped.await(100, TimeUnit.MILLISECONDS), "client exit tracks already-closed preview native call");
        release.countDown();
        await(stopped);
        closer.join(5000);
        check(!lane.snapshot().accepting(), "global admission remains closed");
        lane.shutdownAndDrain(); // Idempotent normal + finally teardown hooks.
    }

    private static void prioritiesRespectBarriersAndPromotion() throws Exception {
        InferenceExecutor lane = new InferenceExecutor("inference-test-priority");
        var scope = lane.openScope();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        scope.submit(() -> { entered.countDown(); release.await(); return null; });
        await(entered);
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        scope.submit(InferenceExecutor.Priority.BACKGROUND, () -> order.add("background-before-seed"));
        var promoted = scope.submit(InferenceExecutor.Priority.BACKGROUND, () -> order.add("promoted"));
        scope.submit(InferenceExecutor.Priority.FOREGROUND, () -> order.add("foreground"));
        scope.submit(() -> order.add("seed-barrier"));
        var finalTask = scope.submit(InferenceExecutor.Priority.FOREGROUND, () -> order.add("after-seed"));
        lane.promote(promoted, InferenceExecutor.Priority.FOREGROUND);
        release.countDown();
        finalTask.get(5, TimeUnit.SECONDS);
        check(order.equals(java.util.List.of("promoted", "foreground", "background-before-seed", "seed-barrier", "after-seed")),
                "promotion/foreground cannot pass seed mutation: " + order);
        lane.shutdownAndDrain();
        check(lane.snapshot().submitted() == 6 && lane.snapshot().completed() == 6,
                "task telemetry counts wrappers");
        check(lane.snapshot().queueNanos() > 0 && lane.snapshot().executionNanos() > 0, "queue/execution timings recorded");
    }

    private static void backgroundIsNotStarved() throws Exception {
        InferenceExecutor lane = new InferenceExecutor("inference-test-fairness");
        var scope = lane.openScope();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        scope.submit(() -> { entered.countDown(); release.await(); return null; });
        await(entered);
        var order = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        scope.submit(InferenceExecutor.Priority.BACKGROUND, () -> order.add(-1));
        for (int i = 0; i < 20; i++) {
            int id = i;
            scope.submit(InferenceExecutor.Priority.FOREGROUND, () -> order.add(id));
        }
        var end = scope.submit(() -> null);
        release.countDown(); end.get(5, TimeUnit.SECONDS);
        check(order.indexOf(-1) <= 8, "background waits no more than eight urgent eligible tasks: " + order);
        lane.shutdownAndDrain();
    }

    private static void reject(Runnable action) {
        try { action.run(); throw new AssertionError("late work accepted"); }
        catch (CancellationException expected) { }
    }

    private static void await(CountDownLatch latch) throws Exception {
        check(latch.await(5, TimeUnit.SECONDS), "latch timed out");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
