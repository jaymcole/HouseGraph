package io.github.jaymcole.housegraph.logging;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A {@link LogSink} that appends records to a text file, so a session's logs survive after
 * the app closes. Lines are formatted with {@link LogFormat} (identical to the console) and
 * flushed per record, so a crash still leaves the last message on disk.
 *
 * <p>The file lives under {@code AppDirectories.logs()} — never a hardcoded path — and is
 * opened once in {@link Logging#bootstrap()}. Writes are guarded by an intrinsic lock so
 * concurrent emitters can't interleave a line; an {@link IOException} while writing is
 * reported once to {@code System.err} and then suppressed, so a full or read-only disk can
 * never take down the app through logging.
 */
public final class FileSink extends AbstractLogSink {

    private final Path file;
    private final Writer writer;
    private boolean writeFailed;

    /**
     * Opens {@code file} for appending.
     *
     * @param file  the log file (created if absent, appended if present)
     * @param level the minimum level written to the file
     * @throws UncheckedIOException if the file cannot be opened
     */
    public FileSink(Path file, LogLevel level) {
        super("File", level);
        this.file = file;
        try {
            Files.createDirectories(file.getParent());
            this.writer = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open log file: " + file, e);
        }
    }

    /**
     * The path being written to.
     *
     * @return the log file path
     */
    public Path file() {
        return file;
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (writeFailed) {
            return;
        }
        try {
            writer.write(LogFormat.full(record));
            writer.write(System.lineSeparator());
            writer.flush();
        } catch (IOException e) {
            // Report once, then stop trying: a broken disk shouldn't spam nor crash.
            writeFailed = true;
            System.err.println("Disabling log file " + file + " after write failure: " + e);
        }
    }

    /** Flushes and closes the file. Safe to call at shutdown. */
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("Could not close log file " + file + ": " + e);
        }
    }
}
