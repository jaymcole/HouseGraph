package io.github.jaymcole.housegraph.logging;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The hub of the logging system: a process-wide singleton that holds the registered
 * {@link LogSink}s and fans each message out to the ones whose {@linkplain
 * LogSink#getLevel() level} it clears. {@link Logger} is a thin front-end that formats a
 * message and calls {@link #publish}; everything about <em>where</em> a message goes lives
 * here.
 *
 * <p><b>Per-output filtering.</b> The level test is applied here, once per sink, so each
 * output filters independently. A record is only <em>materialised</em> (timestamped,
 * boxed into a {@link LogRecord}) when at least one sink would accept it, which keeps
 * silenced {@code trace}/{@code debug} calls cheap.
 *
 * <p><b>Threading.</b> Sinks live in a {@link CopyOnWriteArrayList}, so registration and
 * fan-out are safe under concurrency without locking the logging hot path — messages are
 * emitted from many execution threads at once. A sink that throws is isolated: its failure
 * is reported once to {@code System.err} and never propagates back to the caller (logging
 * must not be able to break the code it observes, nor loop back into itself).
 *
 * <p>The manager starts with a single {@link ConsoleSink} at {@link LogLevel#INFO} so that
 * logging works — and nothing is silently dropped — before {@link Logging#bootstrap()}
 * installs the app's full set of outputs. See {@code docs/architecture/logging.md}.
 */
public final class LogManager {

    private static final LogManager INSTANCE = new LogManager();

    /**
     * The shared instance.
     *
     * @return the process-wide log manager
     */
    public static LogManager get() {
        return INSTANCE;
    }

    private final CopyOnWriteArrayList<LogSink> sinks = new CopyOnWriteArrayList<>();

    private LogManager() {
        // A sensible default so early logging (before bootstrap, or in tests) is never lost.
        sinks.add(new ConsoleSink(LogLevel.INFO));
    }

    /**
     * Registers a sink to receive future records. Idempotent: adding the same instance
     * twice is a no-op.
     *
     * @param sink the output to add
     */
    public void addSink(LogSink sink) {
        Objects.requireNonNull(sink, "sink");
        sinks.addIfAbsent(sink);
    }

    /**
     * Removes a previously-registered sink; it receives no further records.
     *
     * @param sink the output to remove
     */
    public void removeSink(LogSink sink) {
        sinks.remove(sink);
    }

    /**
     * The currently registered sinks, in registration order.
     *
     * @return an immutable snapshot of the sinks
     */
    public List<LogSink> sinks() {
        return List.copyOf(sinks);
    }

    /**
     * Formats nothing and delivers an already-formatted message to every interested sink.
     * Called by {@link Logger}. Skips all work when no sink's level would accept
     * {@code level}.
     *
     * @param level     the message's severity
     * @param source    the emitting logger's name
     * @param message   the fully-formatted message text
     * @param throwable an associated error, or {@code null}
     */
    public void publish(LogLevel level, String source, String message, Throwable throwable) {
        List<LogSink> current = sinks;
        boolean anyInterested = false;
        for (LogSink sink : current) {
            if (level.isAtLeast(sink.getLevel())) {
                anyInterested = true;
                break;
            }
        }
        if (!anyInterested) {
            return;
        }

        LogRecord record = new LogRecord(
                Instant.now(), level, source, Thread.currentThread().getName(), message, throwable);
        for (LogSink sink : current) {
            if (!level.isAtLeast(sink.getLevel())) {
                continue;
            }
            try {
                sink.publish(record);
            } catch (RuntimeException e) {
                // A broken sink must never break logging (or loop back through it).
                System.err.println("Log sink \"" + sink.name() + "\" failed: " + e);
            }
        }
    }
}
