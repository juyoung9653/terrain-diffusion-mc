package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.ArrayList;
import java.util.List;


/** One native inference lane, with independently closable world/preview lifetimes. */
public final class InferenceExecutor {
    private final Object lock = new Object();
    private final Set<Scope> scopes = new HashSet<>();
    public enum Priority { FOREGROUND, NORMAL, BACKGROUND }
    private final List<TrackedTask<?>> queue = new ArrayList<>();
    private final Thread worker;
    private int urgentStreak;
    private long submitted, completed, cancelled, queueNanos, executionNanos, maxQueueNanos;
    private static final int MAX_URGENT_STREAK = 8;
    private boolean accepting = true;
    private int active;

    public InferenceExecutor(String threadName) {
        worker = new Thread(this::work, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    private void work() {
        for (;;) {
            TrackedTask<?> task;
            synchronized (lock) {
                while (queue.isEmpty() && accepting) {
                    try { lock.wait(); } catch (InterruptedException ignored) { }
                }
                if (queue.isEmpty() && !accepting) return;
                task = selectNext();
                queue.remove(task);
                // Mark selection while holding the lock: close cannot mistake an active wrapper for queued work.
                task.started = true;
                task.scope.running++;
                active++;
            }
            task.run();
        }
    }

    private TrackedTask<?> selectNext() {
        TrackedTask<?> best = null, oldest = null;
        for (TrackedTask<?> candidate : queue) {
            boolean eligible = true;
            for (TrackedTask<?> prior : queue) {
                if (prior.scope == candidate.scope && prior.epoch < candidate.epoch) { eligible = false; break; }
            }
            if (!eligible) continue;
            if (oldest == null) oldest = candidate;
            if (best == null || candidate.priority.ordinal() < best.priority.ordinal()) best = candidate;
        }
        if (urgentStreak >= MAX_URGENT_STREAK) { urgentStreak = 0; return oldest; }
        if (best != oldest) urgentStreak++; else urgentStreak = 0;
        return best;
    }

    /** Promotion changes scheduling only; a task can never cross a seed/order barrier. */
    public void promote(FutureTask<?> future, Priority priority) {
        synchronized (lock) {
            if (future instanceof InferenceExecutor.TrackedTask<?> task && !task.started
                    && priority.ordinal() < task.priority.ordinal()) task.priority = priority;
        }
    }

    public Scope openScope() {
        synchronized (lock) {
            if (!accepting) throw new CancellationException("Terrain inference is stopping");
            Scope scope = new Scope();
            scopes.add(scope);
            return scope;
        }
    }

    /** Counts executing wrappers, even when their Future has already been cancelled. */
    public record Snapshot(int active, int queued, boolean accepting, long submitted, long completed,
                           long cancelled, long queueNanos, long executionNanos, long maxQueueNanos) { }

    public void resetMetrics() {
        synchronized (lock) { submitted = completed = cancelled = queueNanos = executionNanos = maxQueueNanos = 0; }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            int queued = scopes.stream().mapToInt(scope -> scope.tasks.size() - scope.running).sum();
            return new Snapshot(active, queued, accepting, submitted, completed, cancelled, queueNanos, executionNanos, maxQueueNanos);
        }
    }

    public void shutdownAndDrain() {
        synchronized (lock) {
            accepting = false;
            for (Scope scope : Set.copyOf(scopes)) scope.close();
            lock.notifyAll(); // Never interrupt an OrtSession.run call.
        }
        boolean interrupted = false;
        for (;;) {
            try {
                worker.join();
                break;
            } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public final class Scope {
        private final Set<TrackedTask<?>> tasks = new HashSet<>();
        private boolean open = true;
        private int running;
        private long epoch;

        private Scope() { }

        public void requireOpen() {
            synchronized (lock) {
                if (!open || !accepting) throw new CancellationException("Terrain inference scope is closed");
            }
        }

        /** Unknown actions are ordering barriers, including seed/store mutations. */
        public <T> FutureTask<T> submit(Callable<T> action) {
            synchronized (lock) {
                epoch++;
                FutureTask<T> result = enqueue(action, Priority.NORMAL, epoch);
                epoch++;
                return result;
            }
        }

        /** Read/inference work may be reordered only between explicit barriers. */
        public <T> FutureTask<T> submit(Priority priority, Callable<T> action) {
            synchronized (lock) { return enqueue(action, priority, epoch); }
        }

        private <T> FutureTask<T> enqueue(Callable<T> action, Priority priority, long epoch) {
            requireOpen();
            TrackedTask<T> task = new TrackedTask<>(this, action, priority, epoch);
            tasks.add(task);
            queue.add(task);
            submitted++;
            lock.notifyAll();
            return task;
        }

        /** Release callers and remove queued work; running native work is allowed to return. */
        public void close() {
            synchronized (lock) {
                open = false;
                for (TrackedTask<?> task : Set.copyOf(tasks)) {
                    task.cancel(false);
                    if (!task.started) {
                        queue.remove(task);
                        cancelled++;
                        tasks.remove(task);
                    }
                }
                if (tasks.isEmpty()) scopes.remove(this);
                lock.notifyAll();
            }
        }

        /** Must be called outside provider/session locks, and never from the inference worker. */
        public void awaitDrained() {
            boolean interrupted = false;
            synchronized (lock) {
                while (!tasks.isEmpty()) {
                    try { lock.wait(); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private final class TrackedTask<T> extends FutureTask<T> {
        private final Scope scope;
        private boolean started;
        private Priority priority;
        private final long epoch;
        private final long enqueued = System.nanoTime();

        TrackedTask(Scope scope, Callable<T> action, Priority priority, long epoch) {
            super(action); this.scope = scope; this.priority = priority; this.epoch = epoch;
        }

        @Override public void run() {
            long start = System.nanoTime();
            synchronized (lock) {
                long waited = start - enqueued;
                queueNanos += waited;
                maxQueueNanos = Math.max(maxQueueNanos, waited);
                if (!scope.open || !accepting) cancel(false);
            }
            try { super.run(); }
            finally {
                synchronized (lock) {
                    executionNanos += System.nanoTime() - start;
                    completed++;
                    if (isCancelled()) cancelled++;
                    active--;
                    scope.running--;
                    scope.tasks.remove(this);
                    if (!scope.open && scope.tasks.isEmpty()) scopes.remove(scope);
                    lock.notifyAll();
                }
            }
        }
    }
}
