package io.github.jaymcole.housegraph.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The resizable half of {@link LogBufferSink}: the ring can be re-sized on the live buffer, which
 * is what lets the preferences window change how much log history is retained without restarting
 * the app — and without invalidating the single instance {@code Logging.buffer()} has handed out.
 */
class LogBufferSinkCapacityTest {

    @Test
    void shrinkingDropsTheOldestRecordsStraightAway() {
        LogBufferSink buffer = new LogBufferSink(10, LogLevel.TRACE);
        for (int i = 0; i < 10; i++) {
            buffer.publish(record("record-" + i));
        }

        buffer.setCapacity(3);

        assertEquals(3, buffer.capacity());
        assertEquals(List.of("record-7", "record-8", "record-9"), messages(buffer),
                "the newest are kept — trimmed on the spot, not at the next publish");
    }

    @Test
    void theNewCapacityBoundsSubsequentPublishes() {
        LogBufferSink buffer = new LogBufferSink(10, LogLevel.TRACE);
        buffer.setCapacity(2);

        buffer.publish(record("a"));
        buffer.publish(record("b"));
        buffer.publish(record("c"));

        assertEquals(List.of("b", "c"), messages(buffer));
    }

    @Test
    void growingKeepsWhatIsHeldAndRoomForMore() {
        LogBufferSink buffer = new LogBufferSink(2, LogLevel.TRACE);
        buffer.publish(record("a"));
        buffer.publish(record("b"));

        buffer.setCapacity(4);
        buffer.publish(record("c"));

        assertEquals(List.of("a", "b", "c"), messages(buffer), "nothing is discarded by growing");
        assertEquals(4, buffer.capacity());
    }

    @Test
    void rejectsANonPositiveCapacity() {
        LogBufferSink buffer = new LogBufferSink(4, LogLevel.TRACE);
        assertThrows(IllegalArgumentException.class, () -> buffer.setCapacity(0));
        assertThrows(IllegalArgumentException.class, () -> buffer.setCapacity(-1));
        assertEquals(4, buffer.capacity(), "a rejected change leaves the buffer as it was");
    }

    private static List<String> messages(LogBufferSink buffer) {
        return buffer.snapshot().stream().map(LogRecord::message).toList();
    }

    private static LogRecord record(String message) {
        return new LogRecord(Instant.now(), LogLevel.INFO, "Test", "test-thread", message, null);
    }
}
