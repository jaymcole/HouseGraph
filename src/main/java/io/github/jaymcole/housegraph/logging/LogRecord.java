package io.github.jaymcole.housegraph.logging;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable log entry, produced by {@link Logger} and fanned out to every
 * {@link LogSink}. It captures everything a sink needs to render the entry without
 * reaching back into the app: when it happened, how severe it is, who emitted it, the
 * already-formatted message, and an optional {@link Throwable}.
 *
 * @param timestamp when the entry was created
 * @param level     severity of the entry
 * @param source    the emitting logger's name (typically a simple class name)
 * @param thread    name of the thread that emitted the entry (execution fans out across
 *                  threads, so this is worth keeping)
 * @param message   the final, fully-formatted message text
 * @param throwable an associated error, or {@code null} if none
 */
public record LogRecord(Instant timestamp, LogLevel level, String source, String thread,
                        String message, Throwable throwable) {

    public LogRecord {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(message, "message");
    }
}
