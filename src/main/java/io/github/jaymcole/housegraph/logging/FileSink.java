package io.github.jaymcole.housegraph.logging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * A {@link LogSink} that appends records to a text file, so a session's logs survive after
 * the app closes. Lines are formatted with {@link LogFormat} (identical to the console) and
 * flushed per record, so a crash still leaves the last message on disk.
 *
 * <p><b>Size-based rotation.</b> To stop the file growing without bound over long-lived
 * sessions, the sink rolls over once the active file passes {@code maxBytes}: the current
 * {@code housegraph.log} becomes {@code housegraph.log.1}, the previous {@code .1} becomes
 * {@code .2}, and so on up to {@code maxBackups} kept generations (the oldest is discarded),
 * then a fresh empty file is opened. A single over-long record can push the file a little
 * past {@code maxBytes} before the roll — the cap is a threshold, not a hard limit. With
 * {@code maxBackups == 0} the file is simply truncated on roll (no history kept).
 *
 * <p>The file lives under {@code AppDirectories.logs()} — never a hardcoded path — and is
 * opened once in {@link Logging#bootstrap(Path)}. Writes are guarded by an intrinsic lock so
 * concurrent emitters can't interleave a line; an {@link IOException} while writing or
 * rotating is reported once to {@code System.err} and then suppressed, so a full or
 * read-only disk can never take down the app through logging.
 */
public final class FileSink extends AbstractLogSink {

    /** Default roll threshold: 5 MiB. */
    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    /** Default number of rolled-over generations kept alongside the active file. */
    public static final int DEFAULT_MAX_BACKUPS = 5;

    private final Path file;
    private final long maxBytes;
    private final int maxBackups;

    private Writer writer;
    private long bytesWritten;
    private boolean writeFailed;

    /**
     * Opens {@code file} for appending with the default rotation policy
     * ({@link #DEFAULT_MAX_BYTES}, {@link #DEFAULT_MAX_BACKUPS}).
     *
     * @param file  the log file (created if absent, appended if present)
     * @param level the minimum level written to the file
     * @throws UncheckedIOException if the file cannot be opened
     */
    public FileSink(Path file, LogLevel level) {
        this(file, level, DEFAULT_MAX_BYTES, DEFAULT_MAX_BACKUPS);
    }

    /**
     * Opens {@code file} for appending with an explicit rotation policy.
     *
     * @param file       the log file (created if absent, appended if present)
     * @param level      the minimum level written to the file
     * @param maxBytes   roll the file over once it grows past this many bytes (must be positive)
     * @param maxBackups how many rolled-over generations to keep (0 = truncate on roll, keep none)
     * @throws UncheckedIOException if the file cannot be opened
     */
    public FileSink(Path file, LogLevel level, long maxBytes, int maxBackups) {
        super("File", level);
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (maxBackups < 0) {
            throw new IllegalArgumentException("maxBackups must not be negative: " + maxBackups);
        }
        this.file = file;
        this.maxBytes = maxBytes;
        this.maxBackups = maxBackups;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            this.writer = openWriter();
            // Continue counting from an existing file's size, so appending across restarts
            // still rolls over at the right point rather than after a fresh maxBytes.
            this.bytesWritten = Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open log file: " + file, e);
        }
    }

    /**
     * The path being written to.
     *
     * @return the active log file path
     */
    public Path file() {
        return file;
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (writeFailed) {
            return;
        }
        String text = LogFormat.full(record) + System.lineSeparator();
        try {
            writer.write(text);
            writer.flush();
            bytesWritten += text.getBytes(StandardCharsets.UTF_8).length;
            if (bytesWritten >= maxBytes) {
                rotate();
            }
        } catch (IOException e) {
            // Report once, then stop trying: a broken disk shouldn't spam nor crash.
            writeFailed = true;
            System.err.println("Disabling log file " + file + " after write failure: " + e);
        }
    }

    /** Flushes and closes the active file. Safe to call at shutdown. */
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("Could not close log file " + file + ": " + e);
        }
    }

    /**
     * Closes the active file, shifts the kept generations down by one (discarding the
     * oldest), moves the active file to {@code .1}, and opens a fresh empty active file.
     */
    private void rotate() throws IOException {
        writer.close();
        if (maxBackups == 0) {
            // Keep no history: just start over from an empty file.
            Files.deleteIfExists(file);
        } else {
            Files.deleteIfExists(backup(maxBackups));
            for (int i = maxBackups - 1; i >= 1; i--) {
                Path src = backup(i);
                if (Files.exists(src)) {
                    Files.move(src, backup(i + 1), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(file, backup(1), StandardCopyOption.REPLACE_EXISTING);
        }
        writer = openWriter();
        bytesWritten = 0;
    }

    /** The path of the {@code n}-th rolled-over generation ({@code housegraph.log.n}). */
    private Path backup(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }

    private Writer openWriter() throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
