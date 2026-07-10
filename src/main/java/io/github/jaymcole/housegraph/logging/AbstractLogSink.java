package io.github.jaymcole.housegraph.logging;

import java.util.Objects;

/**
 * Common base for {@link LogSink}s: holds the sink's name and a mutable, volatile
 * {@linkplain #getLevel() level} so the only thing a concrete sink must implement is
 * {@link #publish}. The level is {@code volatile} because it is read on logging threads
 * and written from the UI thread.
 */
public abstract class AbstractLogSink implements LogSink {

    private final String name;
    private volatile LogLevel level;

    protected AbstractLogSink(String name, LogLevel level) {
        this.name = Objects.requireNonNull(name, "name");
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public final LogLevel getLevel() {
        return level;
    }

    @Override
    public final void setLevel(LogLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public final String name() {
        return name;
    }
}
