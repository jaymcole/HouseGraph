package io.github.jaymcole.housegraph.logging;

/**
 * The front-end callers use to emit log messages. Obtain one from {@link Log#get} and keep
 * it in a {@code private static final} field:
 *
 * <pre>{@code
 * private static final Logger log = Log.get(MyClass.class);
 * ...
 * log.info("Connected to {} as {}", host, user);
 * log.warn("Could not read {}: {}", file, e.getMessage());
 * log.error("Connect failed", exception);
 * }</pre>
 *
 * <p>Messages use SLF4J-style {@code {}} placeholders, substituted left-to-right with the
 * given arguments. As a convenience, if the final argument is a {@link Throwable} and no
 * {@code {}} is left for it, it is attached to the record (so its stack trace reaches
 * sinks) rather than being formatted into the text — matching the SLF4J idiom. There is
 * also an explicit {@code (message, throwable)} overload per level.
 *
 * <p>A {@code Logger} is immutable and thread-safe; all routing and filtering happens in
 * {@link LogManager}.
 */
public final class Logger {

    private final String source;

    Logger(String source) {
        this.source = source;
    }

    /** Logs at {@link LogLevel#TRACE}. */
    public void trace(String message, Object... args) {
        log(LogLevel.TRACE, message, args);
    }

    /** Logs at {@link LogLevel#DEBUG}. */
    public void debug(String message, Object... args) {
        log(LogLevel.DEBUG, message, args);
    }

    /** Logs at {@link LogLevel#INFO}. */
    public void info(String message, Object... args) {
        log(LogLevel.INFO, message, args);
    }

    /** Logs at {@link LogLevel#WARN}. */
    public void warn(String message, Object... args) {
        log(LogLevel.WARN, message, args);
    }

    /** Logs at {@link LogLevel#ERROR}. */
    public void error(String message, Object... args) {
        log(LogLevel.ERROR, message, args);
    }

    /**
     * Logs an error with an explicit throwable, whose stack trace reaches the sinks.
     *
     * @param message   the message text (no placeholder substitution)
     * @param throwable the associated error
     */
    public void error(String message, Throwable throwable) {
        LogManager.get().publish(LogLevel.ERROR, source, message, throwable);
    }

    private void log(LogLevel level, String message, Object... args) {
        Throwable throwable = null;
        Object[] formatArgs = args;
        // SLF4J convention: a trailing Throwable with no placeholder to consume it becomes
        // the record's throwable rather than being formatted into the message.
        if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable t
                && placeholderCount(message) < args.length) {
            throwable = t;
            formatArgs = new Object[args.length - 1];
            System.arraycopy(args, 0, formatArgs, 0, args.length - 1);
        }
        LogManager.get().publish(level, source, format(message, formatArgs), throwable);
    }

    /** Counts non-escaped {@code {}} placeholders. */
    private static int placeholderCount(String pattern) {
        int count = 0;
        int from = 0;
        int at;
        while ((at = pattern.indexOf("{}", from)) >= 0) {
            count++;
            from = at + 2;
        }
        return count;
    }

    /** Substitutes {@code {}} placeholders left-to-right; extra args are ignored. */
    static String format(String pattern, Object... args) {
        if (args == null || args.length == 0 || pattern.indexOf("{}") < 0) {
            return pattern;
        }
        StringBuilder out = new StringBuilder(pattern.length() + 16 * args.length);
        int from = 0;
        int argIndex = 0;
        int at;
        while ((at = pattern.indexOf("{}", from)) >= 0 && argIndex < args.length) {
            out.append(pattern, from, at).append(stringify(args[argIndex++]));
            from = at + 2;
        }
        out.append(pattern, from, pattern.length());
        return out.toString();
    }

    /** Renders an argument, tolerating a null and a throwing {@code toString()}. */
    private static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return String.valueOf(value);
        } catch (RuntimeException e) {
            return "[" + value.getClass().getName() + ".toString() failed: " + e + "]";
        }
    }
}
