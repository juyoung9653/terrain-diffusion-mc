package org.slf4j;

public interface Logger {
    void info(String message, Object... arguments);
    void error(String message, Throwable error);
}
