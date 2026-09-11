package org.slf4j;

import java.util.concurrent.CountDownLatch;

/** Blocks error logging on demand to expose the old catch/finally latch race. */
public final class LoggerFactory {
    private static volatile CountDownLatch errorEntered;
    private static volatile CountDownLatch releaseErrors;
    private static final Logger LOGGER = new Logger() {
        @Override
        public void info(String message, Object... arguments) {
        }

        @Override
        public void error(String message, Throwable error) {
            CountDownLatch entered = errorEntered;
            CountDownLatch release = releaseErrors;
            if (entered == null) {
                return;
            }
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    };

    private LoggerFactory() {
    }

    public static Logger getLogger(Class<?> type) {
        return LOGGER;
    }

    public static void blockErrors(CountDownLatch entered, CountDownLatch release) {
        errorEntered = entered;
        releaseErrors = release;
    }

    public static void stopBlockingErrors() {
        errorEntered = null;
        releaseErrors = null;
    }
}
