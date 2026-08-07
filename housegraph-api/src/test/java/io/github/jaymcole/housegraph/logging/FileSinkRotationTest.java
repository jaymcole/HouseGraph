package io.github.jaymcole.housegraph.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filesystem tests for {@link FileSink}'s size-based rotation, rooted at a temp directory
 * (never the real user profile). {@code maxBytes} is set tiny so each record rolls the
 * file, making the generation shuffle easy to assert.
 */
class FileSinkRotationTest {

    @Test
    void rollsOverPastMaxBytesAndKeepsBoundedBackups(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("housegraph.log");
        // 1-byte threshold: every record exceeds it, so every publish rolls the previous file.
        FileSink sink = new FileSink(log, LogLevel.TRACE, 1, 2);
        try {
            sink.publish(record("first"));
            sink.publish(record("second"));
            sink.publish(record("third"));
            sink.publish(record("fourth"));
        } finally {
            sink.close();
        }

        // Active file plus exactly maxBackups (2) generations — the oldest was discarded.
        assertTrue(Files.exists(log), "active file exists");
        assertTrue(Files.exists(dir.resolve("housegraph.log.1")));
        assertTrue(Files.exists(dir.resolve("housegraph.log.2")));
        assertFalse(Files.exists(dir.resolve("housegraph.log.3")), "backups are bounded to maxBackups");

        // Newest content is in .1, next-newest in .2 (the shuffle pushes older content outward).
        assertTrue(Files.readString(dir.resolve("housegraph.log.1")).contains("fourth"));
        assertTrue(Files.readString(dir.resolve("housegraph.log.2")).contains("third"));
    }

    @Test
    void zeroBackupsTruncatesInsteadOfKeepingHistory(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("housegraph.log");
        FileSink sink = new FileSink(log, LogLevel.TRACE, 1, 0);
        try {
            sink.publish(record("old"));
            sink.publish(record("new"));
        } finally {
            sink.close();
        }

        assertFalse(Files.exists(dir.resolve("housegraph.log.1")), "no backups are kept when maxBackups is 0");
        assertFalse(Files.readString(log).contains("old"), "rolled-away content is gone");
    }

    @Test
    void doesNotRollWhileUnderThreshold(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("housegraph.log");
        FileSink sink = new FileSink(log, LogLevel.TRACE, 1_000_000, 3);
        try {
            sink.publish(record("a"));
            sink.publish(record("b"));
        } finally {
            sink.close();
        }

        assertFalse(Files.exists(dir.resolve("housegraph.log.1")), "small logs stay in one file");
        String contents = Files.readString(log);
        assertTrue(contents.contains("a") && contents.contains("b"));
    }

    @Test
    void rejectsInvalidRotationSettings(@TempDir Path dir) {
        Path log = dir.resolve("housegraph.log");
        assertThrows(IllegalArgumentException.class, () -> new FileSink(log, LogLevel.TRACE, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FileSink(log, LogLevel.TRACE, 10, -1));
    }

    private static LogRecord record(String message) {
        return new LogRecord(Instant.now(), LogLevel.INFO, "Test", "test-thread", message, null);
    }
}
