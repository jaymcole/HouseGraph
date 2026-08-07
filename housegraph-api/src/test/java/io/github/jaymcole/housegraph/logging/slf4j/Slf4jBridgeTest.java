package io.github.jaymcole.housegraph.logging.slf4j;

import io.github.jaymcole.housegraph.logging.AbstractLogSink;
import io.github.jaymcole.housegraph.logging.LogLevel;
import io.github.jaymcole.housegraph.logging.LogManager;
import io.github.jaymcole.housegraph.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the SLF4J → {@code LogManager} bridge. Logger instances are obtained straight
 * from {@link HouseGraphLoggerFactory} (rather than the global {@code LoggerFactory}) so the
 * routing is exercised without depending on which provider the JVM's ServiceLoader bound.
 * A {@link CollectingSink} captures what {@code LogManager} would deliver.
 */
class Slf4jBridgeTest {

    private static final class CollectingSink extends AbstractLogSink {
        final List<LogRecord> received = new CopyOnWriteArrayList<>();

        CollectingSink() {
            super("Collector", LogLevel.TRACE);
        }

        @Override
        public void publish(LogRecord record) {
            received.add(record);
        }
    }

    private final HouseGraphLoggerFactory factory = new HouseGraphLoggerFactory();
    private final LogLevel originalBridgeLevel = Slf4jBridge.getLevel();

    @AfterEach
    void restoreBridgeLevel() {
        Slf4jBridge.setLevel(originalBridgeLevel);
    }

    @Test
    void mapsEverySlf4jLevelOneToOne() {
        assertEquals(LogLevel.TRACE, Slf4jBridge.toLogLevel(Level.TRACE));
        assertEquals(LogLevel.DEBUG, Slf4jBridge.toLogLevel(Level.DEBUG));
        assertEquals(LogLevel.INFO, Slf4jBridge.toLogLevel(Level.INFO));
        assertEquals(LogLevel.WARN, Slf4jBridge.toLogLevel(Level.WARN));
        assertEquals(LogLevel.ERROR, Slf4jBridge.toLogLevel(Level.ERROR));
    }

    @Test
    void factoryCachesOneLoggerPerName() {
        Logger a = factory.getLogger("net.dv8tion.jda.Foo");
        Logger b = factory.getLogger("net.dv8tion.jda.Foo");
        assertSame(a, b);
    }

    @Test
    void gatesBelowTheBridgeLevelAndReflectsItInIsEnabled() {
        Slf4jBridge.setLevel(LogLevel.WARN);
        CollectingSink sink = new CollectingSink();
        LogManager.get().addSink(sink);
        try {
            Logger log = factory.getLogger("net.dv8tion.jda.internal.JDAImpl");
            assertFalse(log.isDebugEnabled(), "debug is below the WARN gate");
            assertTrue(log.isWarnEnabled());

            log.debug("dropped");
            log.warn("kept");

            assertEquals(List.of("kept"), messages(sink));
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    @Test
    void shortensSourceFormatsPlaceholdersAndAttachesThrowable() {
        Slf4jBridge.setLevel(LogLevel.TRACE);
        CollectingSink sink = new CollectingSink();
        LogManager.get().addSink(sink);
        try {
            Logger log = factory.getLogger("net.dv8tion.jda.internal.JDAImpl");
            RuntimeException boom = new RuntimeException("boom");
            log.error("connect to {} failed", "gateway", boom);

            LogRecord record = sink.received.get(sink.received.size() - 1);
            assertEquals(LogLevel.ERROR, record.level());
            assertEquals("JDAImpl", record.source(), "FQCN is shortened to the simple name");
            assertEquals("connect to gateway failed", record.message());
            assertSame(boom, record.throwable());
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    private static List<String> messages(CollectingSink sink) {
        return sink.received.stream().map(LogRecord::message).toList();
    }
}
