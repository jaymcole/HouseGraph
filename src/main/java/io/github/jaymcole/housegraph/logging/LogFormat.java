package io.github.jaymcole.housegraph.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders a {@link LogRecord} to a single-line (plus optional stack trace) text form,
 * shared by the text-oriented sinks ({@link ConsoleSink}, {@link FileSink}) so the console
 * and the log file read identically. The layout is:
 *
 * <pre>{@code HH:mm:ss.SSS LEVEL [source] message}</pre>
 *
 * with any throwable's stack trace appended on following lines.
 *
 * <p>The class is {@code public} only so the log window can reuse {@link #time} for its
 * time column; the line/stack-trace helpers stay package-private to the sinks.
 */
public final class LogFormat {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private LogFormat() {
    }

    /**
     * The wall-clock time portion of a record, e.g. for a table column.
     *
     * @param record the record to read the timestamp from
     * @return the formatted {@code HH:mm:ss.SSS} time
     */
    public static String time(LogRecord record) {
        return TIME.format(record.timestamp());
    }

    /** The full one-line header without the stack trace. */
    static String line(LogRecord record) {
        return time(record) + " " + pad(record.level().name()) + " ["
                + record.source() + "] " + record.message();
    }

    /** The one-line header plus, if present, the throwable's stack trace on following lines. */
    static String full(LogRecord record) {
        String line = line(record);
        Throwable t = record.throwable();
        return t == null ? line : line + System.lineSeparator() + stackTrace(t);
    }

    /** A throwable's stack trace as a string. */
    static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        String text = writer.toString();
        // Trim the trailing newline the trace writer adds; callers join with their own.
        return text.stripTrailing();
    }

    /** Right-pads a level name to a fixed width so columns line up in plain text. */
    private static String pad(String level) {
        return level.length() >= 5 ? level : (level + "     ").substring(0, 5);
    }
}
