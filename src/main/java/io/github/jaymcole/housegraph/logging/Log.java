package io.github.jaymcole.housegraph.logging;

/**
 * Entry point for obtaining a {@link Logger}. This is the one type most code touches:
 *
 * <pre>{@code
 * private static final Logger log = Log.get(MyClass.class);
 * }</pre>
 *
 * <p>The returned logger's {@code source} — the label shown in the console, file, and log
 * window — is the class's simple name. Getting a logger is cheap and side-effect-free; the
 * message routing lives in {@link LogManager}.
 */
public final class Log {

    private Log() {
    }

    /**
     * A logger whose source is {@code type}'s simple name.
     *
     * @param type the class doing the logging
     * @return a logger for that class
     */
    public static Logger get(Class<?> type) {
        return new Logger(type.getSimpleName());
    }

    /**
     * A logger with an explicit source name (for cases without a natural class).
     *
     * @param source the label to tag messages with
     * @return a logger for that source
     */
    public static Logger get(String source) {
        return new Logger(source);
    }
}
