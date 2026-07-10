package io.github.jaymcole.housegraph.logging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the logging core — the whole package is JavaFX-free, so it runs
 * without a display (the log window is tested by hand). A local {@link CollectingSink}
 * captures records so assertions can inspect exactly what each output would receive.
 */
class LoggingTest {

    /** A sink that just records everything it is handed, for assertions. */
    private static final class CollectingSink extends AbstractLogSink {
        final List<LogRecord> received = new CopyOnWriteArrayList<>();

        CollectingSink(LogLevel level) {
            super("Collector", level);
        }

        @Override
        public void publish(LogRecord record) {
            received.add(record);
        }
    }

    // --- LogLevel -----------------------------------------------------------------

    @Test
    void isAtLeastComparesBySeverity() {
        assertTrue(LogLevel.ERROR.isAtLeast(LogLevel.INFO));
        assertTrue(LogLevel.INFO.isAtLeast(LogLevel.INFO));
        assertFalse(LogLevel.DEBUG.isAtLeast(LogLevel.INFO));
        // Nothing clears the OFF threshold — that is how a sink is silenced.
        assertFalse(LogLevel.ERROR.isAtLeast(LogLevel.OFF));
    }

    // --- Logger message formatting ------------------------------------------------

    @Test
    void formatSubstitutesPlaceholdersLeftToRight() {
        assertEquals("a=1 b=2", Logger.format("a={} b={}", 1, 2));
        assertEquals("null then x", Logger.format("{} then {}", null, "x"));
        // Surplus args are ignored; surplus placeholders are left intact.
        assertEquals("only 1", Logger.format("only {}", 1, 2, 3));
        assertEquals("nothing", Logger.format("nothing"));
    }

    @Test
    void trailingThrowableBecomesTheRecordThrowableNotText() {
        CollectingSink sink = new CollectingSink(LogLevel.TRACE);
        LogManager.get().addSink(sink);
        try {
            Logger log = Log.get("Test");
            RuntimeException boom = new RuntimeException("boom");
            log.warn("failed to open {}", "file.txt", boom);

            LogRecord record = last(sink);
            assertEquals("failed to open file.txt", record.message());
            assertSame(boom, record.throwable());
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    @Test
    void throwableConsumedByAPlaceholderIsFormattedNotAttached() {
        CollectingSink sink = new CollectingSink(LogLevel.TRACE);
        LogManager.get().addSink(sink);
        try {
            RuntimeException e = new RuntimeException("x");
            // One placeholder, one arg: the throwable fills the placeholder, so it is not attached.
            Log.get("Test").info("cause: {}", e);

            LogRecord record = last(sink);
            assertNull(record.throwable());
            assertTrue(record.message().contains("x"));
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    // --- LogManager per-output filtering ------------------------------------------

    @Test
    void eachSinkFiltersIndependentlyByItsOwnLevel() {
        CollectingSink verbose = new CollectingSink(LogLevel.DEBUG);
        CollectingSink quiet = new CollectingSink(LogLevel.WARN);
        LogManager.get().addSink(verbose);
        LogManager.get().addSink(quiet);
        try {
            Logger log = Log.get("Test");
            log.debug("d");
            log.warn("w");

            assertEquals(List.of("d", "w"), messages(verbose));
            assertEquals(List.of("w"), messages(quiet), "the WARN sink must not see the DEBUG record");
        } finally {
            LogManager.get().removeSink(verbose);
            LogManager.get().removeSink(quiet);
        }
    }

    @Test
    void changingASinkLevelTakesEffectImmediately() {
        CollectingSink sink = new CollectingSink(LogLevel.ERROR);
        LogManager.get().addSink(sink);
        try {
            Logger log = Log.get("Test");
            log.info("ignored");
            assertTrue(sink.received.isEmpty());

            sink.setLevel(LogLevel.INFO);
            log.info("kept");
            assertEquals(List.of("kept"), messages(sink));
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    @Test
    void aThrowingSinkDoesNotBreakOtherSinksOrTheCaller() {
        LogSink broken = new AbstractLogSink("Broken", LogLevel.TRACE) {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("sink is angry");
            }
        };
        CollectingSink healthy = new CollectingSink(LogLevel.TRACE);
        LogManager.get().addSink(broken);
        LogManager.get().addSink(healthy);
        try {
            Log.get("Test").error("still delivered");
            assertEquals(List.of("still delivered"), messages(healthy));
        } finally {
            LogManager.get().removeSink(broken);
            LogManager.get().removeSink(healthy);
        }
    }

    // --- LogBufferSink ------------------------------------------------------------

    @Test
    void bufferRetainsHistoryForSnapshotAndEvictsOldestPastCapacity() {
        LogBufferSink buffer = new LogBufferSink(3, LogLevel.TRACE);
        for (int i = 1; i <= 5; i++) {
            buffer.publish(record(LogLevel.INFO, "m" + i));
        }
        // Capacity 3: the two oldest have been evicted.
        assertEquals(List.of("m3", "m4", "m5"), snapshotMessages(buffer));
    }

    @Test
    void bufferNotifiesLiveListenersAndStopsAfterRemoval() {
        LogBufferSink buffer = new LogBufferSink(10, LogLevel.TRACE);
        List<String> seen = new ArrayList<>();
        var listener = (java.util.function.Consumer<LogRecord>) r -> seen.add(r.message());

        buffer.addListener(listener);
        buffer.publish(record(LogLevel.INFO, "a"));
        buffer.removeListener(listener);
        buffer.publish(record(LogLevel.INFO, "b"));

        assertEquals(List.of("a"), seen, "a removed listener receives nothing further");
        // ...but the buffer kept capturing regardless, which is what lets a window reopen fully.
        assertEquals(List.of("a", "b"), snapshotMessages(buffer));
    }

    @Test
    void clearEmptiesTheBuffer() {
        LogBufferSink buffer = new LogBufferSink(10, LogLevel.TRACE);
        buffer.publish(record(LogLevel.INFO, "a"));
        buffer.clear();
        assertTrue(buffer.snapshot().isEmpty());
    }

    // --- helpers ------------------------------------------------------------------

    private static LogRecord record(LogLevel level, String message) {
        return new LogRecord(java.time.Instant.now(), level, "Test", "test-thread", message, null);
    }

    private static LogRecord last(CollectingSink sink) {
        return sink.received.get(sink.received.size() - 1);
    }

    private static List<String> messages(CollectingSink sink) {
        return sink.received.stream().map(LogRecord::message).toList();
    }

    private static List<String> snapshotMessages(LogBufferSink buffer) {
        return buffer.snapshot().stream().map(LogRecord::message).toList();
    }
}
