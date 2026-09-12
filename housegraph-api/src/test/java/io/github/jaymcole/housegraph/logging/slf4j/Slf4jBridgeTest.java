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
import java.util.Map;
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

    /** jmdns's two noisy sources, exactly as they name themselves. */
    private static final String JMDNS_RECORD_TYPE = "javax.jmdns.impl.constants.DNSRecordType";
    private static final String JMDNS_INCOMING = "javax.jmdns.impl.DNSIncoming";

    private final HouseGraphLoggerFactory factory = new HouseGraphLoggerFactory();
    private final LogLevel originalBridgeLevel = Slf4jBridge.getLevel();
    private final Map<String, LogLevel> originalLoggerLevels = Slf4jBridge.getLoggerLevels();

    @AfterEach
    void restoreBridgeConfiguration() {
        Slf4jBridge.setLevel(originalBridgeLevel);
        Slf4jBridge.getLoggerLevels().keySet().forEach(Slf4jBridge::clearLevel);
        originalLoggerLevels.forEach(Slf4jBridge::setLevel);
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

    @Test
    void jmdnsShipsGatedAtErrorByDefault() {
        assertEquals(LogLevel.ERROR, Slf4jBridge.levelFor(JMDNS_RECORD_TYPE),
                "the built-in override covers jmdns's whole package");
        assertEquals(LogLevel.ERROR, Slf4jBridge.levelFor(JMDNS_INCOMING));
        assertEquals(LogLevel.ERROR, Slf4jBridge.levelFor("javax.jmdns"),
                "the key matches the logger named exactly after it, too");
    }

    @Test
    void dropsJmdnsUnknownRecordTypeWarningsButKeepsItsErrors() {
        Slf4jBridge.setLevel(LogLevel.WARN);
        CollectingSink sink = new CollectingSink();
        LogManager.get().addSink(sink);
        try {
            Logger recordType = factory.getLogger(JMDNS_RECORD_TYPE);
            Logger incoming = factory.getLogger(JMDNS_INCOMING);
            assertFalse(recordType.isWarnEnabled(), "an RFC 9460 type 65 question is not news");
            assertTrue(recordType.isErrorEnabled(), "a real jmdns failure still gets through");

            recordType.warn("Could not find record type for index: {}", 65);
            incoming.warn("Could not find record type: {}", "dns[query,192.168.50.74:5353]");
            recordType.error("genuinely broken");

            assertEquals(List.of("genuinely broken"), messages(sink));
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    @Test
    void aQuietedLibraryDoesNotQuietTheRest() {
        Slf4jBridge.setLevel(LogLevel.WARN);
        CollectingSink sink = new CollectingSink();
        LogManager.get().addSink(sink);
        try {
            factory.getLogger(JMDNS_RECORD_TYPE).warn("dropped");
            factory.getLogger("net.dv8tion.jda.internal.JDAImpl").warn("kept");

            assertEquals(List.of("kept"), messages(sink));
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    @Test
    void theLongestMatchingOverrideWins() {
        Slf4jBridge.setLevel("javax.jmdns", LogLevel.OFF);
        Slf4jBridge.setLevel("javax.jmdns.impl.DNSIncoming", LogLevel.DEBUG);

        assertEquals(LogLevel.DEBUG, Slf4jBridge.levelFor(JMDNS_INCOMING),
                "the more specific key beats the package-wide one");
        assertEquals(LogLevel.OFF, Slf4jBridge.levelFor(JMDNS_RECORD_TYPE));
    }

    @Test
    void anOverrideStopsAtADotBoundary() {
        Slf4jBridge.setLevel(LogLevel.WARN);
        assertEquals(LogLevel.WARN, Slf4jBridge.levelFor("javax.jmdnsx.Client"),
                "javax.jmdns must not capture an unrelated package it merely prefixes");
    }

    @Test
    void offSilencesALoggerEntirely() {
        CollectingSink sink = new CollectingSink();
        LogManager.get().addSink(sink);
        try {
            Slf4jBridge.setLevel("com.example.chatty", LogLevel.OFF);
            Logger log = factory.getLogger("com.example.chatty.Thing");
            assertFalse(log.isErrorEnabled());

            log.error("dropped");

            assertEquals(List.of(), messages(sink));
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    @Test
    void aLevelChangeReachesALoggerHandedOutEarlier() {
        Slf4jBridge.setLevel(LogLevel.WARN);
        CollectingSink sink = new CollectingSink();
        LogManager.get().addSink(sink);
        try {
            // Resolve the threshold once so the change below has a cached answer to invalidate.
            Logger log = factory.getLogger(JMDNS_INCOMING);
            assertFalse(log.isWarnEnabled());

            Slf4jBridge.clearLevel("javax.jmdns");
            assertTrue(log.isWarnEnabled(), "clearing the override returns it to the default");
            log.warn("kept");

            Slf4jBridge.setLevel("javax.jmdns", LogLevel.ERROR);
            assertFalse(log.isWarnEnabled(), "and setting one takes effect just as promptly");
            log.warn("dropped");

            assertEquals(List.of("kept"), messages(sink));
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    private static List<String> messages(CollectingSink sink) {
        return sink.received.stream().map(LogRecord::message).toList();
    }
}
