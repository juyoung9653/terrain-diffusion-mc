package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.pipeline.InferenceExecutor;

/** Carries audited Streams build urgency across its sampler worker boundary. */
public final class StreamsSamplerPriority {
    private static final ThreadLocal<InferenceExecutor.Priority> CURRENT = new ThreadLocal<>();

    private StreamsSamplerPriority() { }

    public static InferenceExecutor.Priority current() {
        InferenceExecutor.Priority priority = CURRENT.get();
        return priority == null ? InferenceExecutor.Priority.BACKGROUND : priority;
    }

    public static void run(int streamsPriority, Runnable action) {
        InferenceExecutor.Priority previous = CURRENT.get();
        CURRENT.set(streamsPriority == 0
                ? InferenceExecutor.Priority.FOREGROUND : InferenceExecutor.Priority.BACKGROUND);
        try {
            action.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
