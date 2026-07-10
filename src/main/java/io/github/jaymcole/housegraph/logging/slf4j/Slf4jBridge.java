package io.github.jaymcole.housegraph.logging.slf4j;

import io.github.jaymcole.housegraph.logging.LogLevel;
import org.slf4j.event.Level;

/**
 * Shared configuration and level mapping for the SLF4J → {@code LogManager} bridge.
 *
 * <p>Third-party libraries (notably JDA) log through SLF4J. HouseGraph provides its own
 * SLF4J {@linkplain HouseGraphSlf4jProvider provider} so those messages flow into the same
 * {@code LogManager} pipeline — console, file, and the log window — as the app's own logs,
 * instead of a separate {@code slf4j-simple} console binding.
 *
 * <p>Library logging can be chatty, so the bridge applies its <b>own minimum level</b>
 * before a message even reaches {@code LogManager} (where the usual per-sink filtering then
 * applies). It defaults to {@link LogLevel#WARN} — matching the old {@code
 * simplelogger.properties} setting that kept JDA quiet — and can be overridden at startup
 * with the {@code housegraph.slf4j.level} system property, or at runtime via
 * {@link #setLevel}. This gate is what SLF4J's {@code isXxxEnabled()} checks report, so a
 * library skips building a message the bridge would drop.
 */
public final class Slf4jBridge {

    /** System property to set the bridge's minimum level at startup (e.g. {@code -Dhousegraph.slf4j.level=INFO}). */
    public static final String LEVEL_PROPERTY = "housegraph.slf4j.level";

    private static volatile LogLevel level = resolveInitialLevel();

    private Slf4jBridge() {
    }

    /**
     * The minimum level a bridged (SLF4J) message must reach to be forwarded.
     *
     * @return the current bridge threshold
     */
    public static LogLevel getLevel() {
        return level;
    }

    /**
     * Adjusts the minimum level for bridged messages at runtime.
     *
     * @param newLevel the new threshold (never {@code null})
     */
    public static void setLevel(LogLevel newLevel) {
        if (newLevel == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        level = newLevel;
    }

    /**
     * Maps an SLF4J {@link Level} onto the matching {@link LogLevel} (a one-to-one mapping).
     *
     * @param slf4jLevel the SLF4J level
     * @return the corresponding {@link LogLevel}
     */
    public static LogLevel toLogLevel(Level slf4jLevel) {
        return switch (slf4jLevel) {
            case TRACE -> LogLevel.TRACE;
            case DEBUG -> LogLevel.DEBUG;
            case INFO -> LogLevel.INFO;
            case WARN -> LogLevel.WARN;
            case ERROR -> LogLevel.ERROR;
        };
    }

    private static LogLevel resolveInitialLevel() {
        String configured = System.getProperty(LEVEL_PROPERTY);
        if (configured != null) {
            try {
                return LogLevel.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the default on an unrecognised value.
            }
        }
        return LogLevel.WARN;
    }
}
