package io.github.jaymcole.housegraph.ui.log;

import io.github.jaymcole.housegraph.logging.AbstractLogSink;
import io.github.jaymcole.housegraph.logging.LogLevel;
import io.github.jaymcole.housegraph.logging.LogManager;
import io.github.jaymcole.housegraph.logging.LogRecord;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that a chosen per-output level round-trips through {@link AppPreferences} and is
 * reapplied on the next launch. A uniquely-named sink is registered so it can't collide with
 * the real sinks other tests share. Preferences are backed by a temp file, never the real
 * profile.
 */
class LogLevelPreferencesTest {

    /** A no-op sink with a distinctive name, used only to observe its level. */
    private static final class NamedSink extends AbstractLogSink {
        NamedSink(String name, LogLevel level) {
            super(name, level);
        }

        @Override
        public void publish(LogRecord record) {
            // no-op: this test only cares about the sink's level, not its output
        }
    }

    @Test
    void persistThenRestoreReappliesTheSavedLevel(@TempDir Path dir) {
        Path file = dir.resolve("preferences.json");
        NamedSink sink = new NamedSink("TestOutput", LogLevel.INFO);
        LogManager.get().addSink(sink);
        try {
            // User picks WARN for this output; it is written to disk.
            sink.setLevel(LogLevel.WARN);
            LogLevelPreferences.persist(AppPreferences.loadFrom(file), sink);

            // Next launch: the sink starts at its default, then restore lifts it back to WARN.
            sink.setLevel(LogLevel.INFO);
            LogLevelPreferences.restore(AppPreferences.loadFrom(file));
            assertEquals(LogLevel.WARN, sink.getLevel());
        } finally {
            LogManager.get().removeSink(sink);
        }
    }

    @Test
    void restoreIgnoresAnUnparseableSavedValue(@TempDir Path dir) {
        Path file = dir.resolve("preferences.json");
        AppPreferences prefs = AppPreferences.loadFrom(file);
        prefs.put("log.level.TestOutput", "NOT_A_LEVEL");
        prefs.save();

        NamedSink sink = new NamedSink("TestOutput", LogLevel.INFO);
        LogManager.get().addSink(sink);
        try {
            LogLevelPreferences.restore(AppPreferences.loadFrom(file));
            assertEquals(LogLevel.INFO, sink.getLevel(), "a bad saved value leaves the default untouched");
        } finally {
            LogManager.get().removeSink(sink);
        }
    }
}
