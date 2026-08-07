package io.github.jaymcole.housegraph.logging;

/**
 * A destination for log records — a console, a file, an in-memory buffer feeding a
 * window. Each sink carries its own {@linkplain #getLevel() level}, which is the essence
 * of <em>per-output filtering</em>: the same {@link Logger} call can land in a verbose
 * on-screen buffer while a quieter console ignores it.
 *
 * <p><b>Threading.</b> {@link LogManager} may call {@link #publish} from any thread
 * (execution fans out across virtual threads), possibly concurrently. Implementations
 * must be thread-safe and must not block for long — a sink that touches a UI toolkit
 * should hand off to that toolkit's thread rather than doing work inline.
 *
 * <p>{@link LogManager} applies the level check itself before calling {@link #publish},
 * so a sink can assume every record it receives has already cleared {@link #getLevel()}.
 */
public interface LogSink {

    /**
     * Delivers a record that has already passed this sink's level filter.
     *
     * @param record the entry to output (never {@code null})
     */
    void publish(LogRecord record);

    /**
     * This sink's minimum level; records below it are dropped by {@link LogManager}.
     *
     * @return the current threshold
     */
    LogLevel getLevel();

    /**
     * Adjusts this sink's minimum level. May be called at runtime (e.g. from the log
     * window) to make one output more or less verbose without touching the others.
     *
     * @param level the new threshold (never {@code null})
     */
    void setLevel(LogLevel level);

    /**
     * A short, stable, human-readable name for this output (e.g. {@code "Console"}),
     * used to label its level control in the UI.
     *
     * @return the display name
     */
    String name();
}
