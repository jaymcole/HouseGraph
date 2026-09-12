package io.github.jaymcole.housegraph.logging.slf4j;

import io.github.jaymcole.housegraph.logging.LogLevel;
import io.github.jaymcole.housegraph.logging.LogManager;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * An SLF4J {@code Logger} that forwards into HouseGraph's {@link LogManager}. SLF4J's many
 * overloaded logging methods are funnelled by {@link LegacyAbstractLogger} into a single
 * {@link #handleNormalizedLoggingCall} call, so this adapter only has to format the message
 * and republish it.
 *
 * <p>The bridge's own threshold gates every call — reflected in the {@code isXxxEnabled()}
 * checks so a library skips work the bridge would drop — after which {@code LogManager}'s
 * per-sink filtering applies as usual. That threshold is {@linkplain
 * Slf4jBridge#levelFor(String) resolved from this logger's name}, so a per-logger override
 * beats the default level. Resolving walks the overrides, which is too much for a call that
 * a chatty library makes thousands of times, so the answer is cached against {@link
 * Slf4jBridge#configGeneration()} and recomputed only when the configuration changes.
 *
 * <p>The SLF4J logger name is usually a fully-qualified class name; it is shortened to the
 * simple name so bridged logs read like the app's own {@code [Source]} labels.
 */
final class HouseGraphSlf4jLogger extends LegacyAbstractLogger {

    /** A threshold and the configuration revision it was resolved at. */
    private record Threshold(int generation, LogLevel level) {
    }

    private final String source;

    private volatile Threshold threshold;

    HouseGraphSlf4jLogger(String name) {
        this.name = name;
        this.source = simpleName(name);
    }

    /**
     * This logger's minimum level, re-resolved only when {@link Slf4jBridge}'s configuration
     * has changed since the cached answer. Reading the generation before resolving is what
     * makes a concurrent change safe: it caches the older revision number, so the next call
     * sees the mismatch and recomputes rather than pinning a stale level.
     */
    private LogLevel threshold() {
        int generation = Slf4jBridge.configGeneration();
        Threshold cached = threshold;
        if (cached == null || cached.generation() != generation) {
            cached = new Threshold(generation, Slf4jBridge.levelFor(name));
            threshold = cached;
        }
        return cached.level();
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                               Object[] arguments, Throwable throwable) {
        LogLevel mapped = Slf4jBridge.toLogLevel(level);
        if (!mapped.isAtLeast(threshold())) {
            return;
        }
        String message = (arguments == null || arguments.length == 0)
                ? messagePattern
                : MessageFormatter.basicArrayFormat(messagePattern, arguments);
        LogManager.get().publish(mapped, source, message, throwable);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        // No location awareness is needed; sinks label by source name, not caller frame.
        return null;
    }

    private boolean enabled(LogLevel level) {
        return level.isAtLeast(threshold());
    }

    @Override public boolean isTraceEnabled() { return enabled(LogLevel.TRACE); }
    @Override public boolean isTraceEnabled(Marker marker) { return isTraceEnabled(); }
    @Override public boolean isDebugEnabled() { return enabled(LogLevel.DEBUG); }
    @Override public boolean isDebugEnabled(Marker marker) { return isDebugEnabled(); }
    @Override public boolean isInfoEnabled() { return enabled(LogLevel.INFO); }
    @Override public boolean isInfoEnabled(Marker marker) { return isInfoEnabled(); }
    @Override public boolean isWarnEnabled() { return enabled(LogLevel.WARN); }
    @Override public boolean isWarnEnabled(Marker marker) { return isWarnEnabled(); }
    @Override public boolean isErrorEnabled() { return enabled(LogLevel.ERROR); }
    @Override public boolean isErrorEnabled(Marker marker) { return isErrorEnabled(); }

    private static String simpleName(String name) {
        if (name == null || name.isEmpty()) {
            return "slf4j";
        }
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 && lastDot < name.length() - 1 ? name.substring(lastDot + 1) : name;
    }
}
