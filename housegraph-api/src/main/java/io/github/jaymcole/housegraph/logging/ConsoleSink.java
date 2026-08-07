package io.github.jaymcole.housegraph.logging;

/**
 * A {@link LogSink} that writes to the process's standard streams: {@link LogLevel#WARN}
 * and {@link LogLevel#ERROR} go to {@code System.err}, everything else to {@code System.out}
 * — matching the split callers previously got from ad-hoc {@code System.err.println}s,
 * while now respecting a level. Output uses {@link LogFormat} so it reads the same as the
 * log file.
 *
 * <p>{@code System.out}/{@code System.err} are synchronised internally, so no extra locking
 * is needed for thread safety.
 */
public final class ConsoleSink extends AbstractLogSink {

    /**
     * @param level the minimum level this console prints
     */
    public ConsoleSink(LogLevel level) {
        super("Console", level);
    }

    @Override
    public void publish(LogRecord record) {
        String text = LogFormat.full(record);
        if (record.level().isAtLeast(LogLevel.WARN)) {
            System.err.println(text);
        } else {
            System.out.println(text);
        }
    }
}
