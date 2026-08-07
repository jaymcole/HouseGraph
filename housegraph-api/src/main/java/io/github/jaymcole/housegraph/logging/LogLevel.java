package io.github.jaymcole.housegraph.logging;

/**
 * Severity of a log message, ordered from most verbose to most severe. A single
 * scale is shared by two roles:
 * <ul>
 *   <li>the level a message is <em>emitted</em> at ({@link #TRACE}…{@link #ERROR}), and</li>
 *   <li>the <em>threshold</em> a {@link LogSink} filters at — a message is delivered to a
 *       sink only when its level is at least the sink's threshold.</li>
 * </ul>
 * {@link #OFF} exists only as a threshold: no message is ever emitted at {@code OFF}, so a
 * sink set to {@code OFF} receives nothing. Because filtering is defined purely by
 * {@linkplain Enum#ordinal() ordinal} order, keep these constants in ascending severity.
 */
public enum LogLevel {
    /** Fine-grained diagnostic detail, normally silenced. */
    TRACE,
    /** Developer-oriented detail useful when diagnosing a problem. */
    DEBUG,
    /** A normal, noteworthy event in the app's operation. */
    INFO,
    /** Something unexpected that the app recovered from. */
    WARN,
    /** A failure a user or developer should know about. */
    ERROR,
    /** Threshold-only: silences a sink entirely. Never used as a message level. */
    OFF;

    /**
     * Whether a message at this level clears {@code threshold} and should be delivered.
     *
     * @param threshold the minimum level a sink accepts
     * @return {@code true} if this level is at least as severe as {@code threshold}
     */
    public boolean isAtLeast(LogLevel threshold) {
        return ordinal() >= threshold.ordinal();
    }
}
