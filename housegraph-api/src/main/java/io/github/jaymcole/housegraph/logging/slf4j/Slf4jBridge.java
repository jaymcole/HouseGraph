package io.github.jaymcole.housegraph.logging.slf4j;

import io.github.jaymcole.housegraph.logging.LogLevel;
import org.slf4j.event.Level;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared configuration and level mapping for the SLF4J &rarr; {@code LogManager} bridge.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Third-party libraries (notably JDA and jmdns) log through SLF4J. HouseGraph provides its
 * own SLF4J {@linkplain HouseGraphSlf4jProvider provider} so those messages flow into the same
 * {@code LogManager} pipeline — console, file, and the log window — as the app's own logs,
 * instead of a separate {@code slf4j-simple} console binding.
 *
 * <p>Library logging can be chatty, so the bridge applies its <b>own minimum level</b>
 * before a message even reaches {@code LogManager} (where the usual per-sink filtering then
 * applies). This gate is what SLF4J's {@code isXxxEnabled()} checks report, so a library skips
 * building a message the bridge would drop.
 *
 * <h2>Two levels of gate</h2>
 *
 * <p>The <b>default threshold</b> applies to every bridged logger. It defaults to
 * {@link LogLevel#WARN} — matching the old {@code simplelogger.properties} setting that kept
 * JDA quiet — and can be overridden at startup with the {@code housegraph.slf4j.level} system
 * property, or at runtime via {@link #setLevel(LogLevel)}.
 *
 * <p>A <b>per-logger threshold</b> overrides that default for one logger and its descendants,
 * because a library can be quiet overall yet have one component that reports normal conditions
 * as warnings. Overrides are keyed by SLF4J logger name and match hierarchically: the key
 * {@code javax.jmdns} covers {@code javax.jmdns.impl.DNSIncoming}, and the longest matching
 * key wins. Set one at startup with {@code -Dhousegraph.slf4j.level.<logger>=<level>} (e.g.
 * {@code -Dhousegraph.slf4j.level.javax.jmdns=WARN}), or at runtime via
 * {@link #setLevel(String, LogLevel)}. {@link LogLevel#OFF} silences a logger outright.
 *
 * <h2>Built-in overrides</h2>
 *
 * <p>{@code javax.jmdns} ships gated at {@link LogLevel#ERROR}. jmdns 3.5.9's record-type
 * table predates the SVCB/HTTPS record types of RFC 9460, so every ordinary name lookup by a
 * client that asks for an HTTPS record (type 65) — which Apple's resolver does as a matter of
 * course — makes it log a {@code WARN} naming the unknown type and dump the whole packet. It
 * parses the rest of the packet correctly and answers the query; the warning reports a gap in
 * the library's table, not a problem with the lookup, and it arrives once per name resolution
 * per interface. Gating at {@code ERROR} rather than {@code OFF} keeps genuine jmdns failures
 * visible. Override the built-in like any other per-logger level to get the warnings back.
 */
public final class Slf4jBridge {

    /** System property to set the bridge's default level at startup (e.g. {@code -Dhousegraph.slf4j.level=INFO}). */
    public static final String LEVEL_PROPERTY = "housegraph.slf4j.level";

    /**
     * System-property prefix for a per-logger level at startup: the logger name follows the
     * prefix, as in {@code -Dhousegraph.slf4j.level.javax.jmdns=WARN}.
     */
    public static final String LOGGER_LEVEL_PREFIX = LEVEL_PROPERTY + ".";

    /**
     * Per-logger thresholds applied unless overridden. See the class comment for why jmdns is
     * here; a library earns a place only when it reports a normal condition as a warning.
     */
    private static final Map<String, LogLevel> BUILT_IN_LOGGER_LEVELS =
            Map.of("javax.jmdns", LogLevel.ERROR);

    private static volatile LogLevel level = resolveInitialLevel();

    private static final ConcurrentMap<String, LogLevel> loggerLevels = resolveInitialLoggerLevels();

    /**
     * Bumped by every level change. {@link HouseGraphSlf4jLogger} caches its resolved
     * threshold against this, so the hot path costs two reads instead of a map scan while the
     * configuration is unchanged.
     */
    private static final AtomicInteger generation = new AtomicInteger();

    private Slf4jBridge() {
    }

    /**
     * The default minimum level a bridged (SLF4J) message must reach to be forwarded, for
     * loggers with no {@linkplain #setLevel(String, LogLevel) per-logger override}.
     *
     * @return the current default threshold
     */
    public static LogLevel getLevel() {
        return level;
    }

    /**
     * Adjusts the default minimum level for bridged messages at runtime. Loggers carrying a
     * per-logger override are unaffected.
     *
     * @param newLevel the new default threshold (never {@code null})
     */
    public static void setLevel(LogLevel newLevel) {
        if (newLevel == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        level = newLevel;
        generation.incrementAndGet();
    }

    /**
     * Overrides the threshold for {@code loggerName} and its descendants — {@code
     * javax.jmdns} covers {@code javax.jmdns.impl.DNSIncoming}. Where several overrides match
     * a logger, the longest one wins.
     *
     * @param loggerName the SLF4J logger name to gate (never {@code null} or blank)
     * @param newLevel   the threshold for it, {@link LogLevel#OFF} to silence it entirely
     */
    public static void setLevel(String loggerName, LogLevel newLevel) {
        if (loggerName == null || loggerName.isBlank()) {
            throw new IllegalArgumentException("loggerName must not be null or blank");
        }
        if (newLevel == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        loggerLevels.put(loggerName, newLevel);
        generation.incrementAndGet();
    }

    /**
     * Drops the override for {@code loggerName}, returning it to the default threshold. This
     * removes a {@linkplain #BUILT_IN_LOGGER_LEVELS built-in} override too.
     *
     * @param loggerName the SLF4J logger name to stop gating
     */
    public static void clearLevel(String loggerName) {
        if (loggerName != null && loggerLevels.remove(loggerName) != null) {
            generation.incrementAndGet();
        }
    }

    /**
     * The per-logger overrides currently in force, keyed by logger name.
     *
     * @return an immutable snapshot; changes afterwards are not reflected
     */
    public static Map<String, LogLevel> getLoggerLevels() {
        return Map.copyOf(loggerLevels);
    }

    /**
     * The threshold that applies to one logger: the longest matching per-logger override, or
     * the default level when none matches.
     *
     * @param loggerName the SLF4J logger name
     * @return the minimum level a message from that logger must reach
     */
    public static LogLevel levelFor(String loggerName) {
        LogLevel resolved = null;
        int longestMatch = -1;
        if (loggerName != null) {
            for (Map.Entry<String, LogLevel> override : loggerLevels.entrySet()) {
                String key = override.getKey();
                if (key.length() > longestMatch && covers(key, loggerName)) {
                    longestMatch = key.length();
                    resolved = override.getValue();
                }
            }
        }
        return resolved != null ? resolved : level;
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

    /** The current configuration revision; see {@link #generation}. */
    static int configGeneration() {
        return generation.get();
    }

    /**
     * Whether an override key applies to a logger: the same name, or an ancestor of it in the
     * dot-separated hierarchy. The separator check is what stops {@code javax.jmdns} from
     * capturing an unrelated {@code javax.jmdnsx}.
     */
    private static boolean covers(String key, String loggerName) {
        return loggerName.equals(key)
                || (loggerName.length() > key.length()
                    && loggerName.startsWith(key)
                    && loggerName.charAt(key.length()) == '.');
    }

    private static LogLevel resolveInitialLevel() {
        LogLevel configured = parseLevel(System.getProperty(LEVEL_PROPERTY));
        return configured != null ? configured : LogLevel.WARN;
    }

    /** The built-in overrides, with any {@code housegraph.slf4j.level.<logger>} property layered on top. */
    private static ConcurrentMap<String, LogLevel> resolveInitialLoggerLevels() {
        ConcurrentMap<String, LogLevel> resolved = new ConcurrentHashMap<>(BUILT_IN_LOGGER_LEVELS);
        for (String name : System.getProperties().stringPropertyNames()) {
            if (!name.startsWith(LOGGER_LEVEL_PREFIX)) {
                continue;
            }
            String loggerName = name.substring(LOGGER_LEVEL_PREFIX.length());
            LogLevel configured = parseLevel(System.getProperty(name));
            if (!loggerName.isBlank() && configured != null) {
                resolved.put(loggerName, configured);
            }
        }
        return resolved;
    }

    /** Parses a level name, returning {@code null} for absent or unrecognised values. */
    private static LogLevel parseLevel(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LogLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // An unrecognised value leaves the caller's default in place.
            return null;
        }
    }
}
