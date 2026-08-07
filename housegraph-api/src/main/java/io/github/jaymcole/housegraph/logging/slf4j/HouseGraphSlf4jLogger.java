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
 * <p>The bridge's {@linkplain Slf4jBridge#getLevel() own level} gates every call — reflected
 * in the {@code isXxxEnabled()} checks so a library skips work the bridge would drop — after
 * which {@code LogManager}'s per-sink filtering applies as usual. The SLF4J logger name is
 * usually a fully-qualified class name; it is shortened to the simple name so bridged logs
 * read like the app's own {@code [Source]} labels.
 */
final class HouseGraphSlf4jLogger extends LegacyAbstractLogger {

    private final String source;

    HouseGraphSlf4jLogger(String name) {
        this.name = name;
        this.source = simpleName(name);
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                               Object[] arguments, Throwable throwable) {
        LogLevel mapped = Slf4jBridge.toLogLevel(level);
        if (!mapped.isAtLeast(Slf4jBridge.getLevel())) {
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
        return level.isAtLeast(Slf4jBridge.getLevel());
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
