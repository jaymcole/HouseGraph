package io.github.jaymcole.housegraph.logging;

import java.nio.file.Path;

/**
 * One-call setup and teardown for the application's logging outputs, plus the handle the
 * UI uses to reach the shared in-memory buffer.
 *
 * <p>{@link LogManager} always has a {@link ConsoleSink} (so logging works from the very
 * first line, and in tests). {@link #bootstrap(Path)} adds the two app-specific outputs on
 * top:
 * <ul>
 *   <li>the shared {@link LogBufferSink} — the ring buffer the {@code LogWindow} reads, so
 *       it can open and close without losing history; and</li>
 *   <li>a {@link FileSink} under the given log directory, when one is supplied.</li>
 * </ul>
 *
 * <p>This class deliberately takes the log directory as a parameter rather than importing
 * {@code AppDirectories}, so the {@code logging} package stays free of dependencies on the
 * rest of the app and can't form an import cycle with packages that log. The caller (the
 * app entry point) resolves the path via {@code AppDirectories.get().logs()}.
 *
 * <p>Default per-output levels: console at {@link LogLevel#INFO} (quiet by default), buffer
 * and file at {@link LogLevel#DEBUG} (retain detail for inspection). Any of these can be
 * changed at runtime from the log window. See {@code docs/architecture/logging.md}.
 */
public final class Logging {

    /** Number of records the window buffer retains before evicting the oldest. */
    public static final int BUFFER_CAPACITY = 5_000;

    private static final LogBufferSink BUFFER = new LogBufferSink(BUFFER_CAPACITY, LogLevel.DEBUG);

    private static boolean bootstrapped;
    private static FileSink fileSink;

    private Logging() {
    }

    /**
     * The shared in-memory buffer that the log window renders. Always present (it exists
     * whether or not {@link #bootstrap(Path)} has run), but only receives records once
     * {@code bootstrap} has registered it.
     *
     * @return the shared log buffer
     */
    public static LogBufferSink buffer() {
        return BUFFER;
    }

    /**
     * Registers the buffer and (if {@code logDirectory} is non-null) a file output. Idempotent:
     * a second call is a no-op, so it is safe to call from application startup unconditionally.
     *
     * @param logDirectory directory for the {@code housegraph.log} file, or {@code null} to
     *                     skip file logging (e.g. in a headless/test run)
     */
    public static synchronized void bootstrap(Path logDirectory) {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;

        LogManager manager = LogManager.get();
        manager.addSink(BUFFER);

        Logger log = Log.get(Logging.class);
        if (logDirectory != null) {
            try {
                fileSink = new FileSink(logDirectory.resolve("housegraph.log"), LogLevel.DEBUG);
                manager.addSink(fileSink);
                log.info("Logging to {}", fileSink.file());
            } catch (RuntimeException e) {
                log.warn("File logging disabled: {}", e.getMessage());
            }
        }
    }

    /**
     * Flushes and closes the file output, if any. Call at application shutdown so the last
     * lines reach disk. The console and buffer need no teardown.
     */
    public static synchronized void shutdown() {
        if (fileSink != null) {
            fileSink.close();
            fileSink = null;
        }
    }
}
