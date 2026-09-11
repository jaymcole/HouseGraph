package io.github.jaymcole.housegraph.ui.log;

import io.github.jaymcole.housegraph.logging.LogLevel;
import io.github.jaymcole.housegraph.logging.LogManager;
import io.github.jaymcole.housegraph.logging.LogSink;
import io.github.jaymcole.housegraph.storage.AppPreferences;

import java.util.Optional;

/**
 * Persists each log output's chosen {@link LogLevel} across launches, reusing the app's
 * existing {@link AppPreferences} store. A sink's level is keyed by its
 * {@linkplain LogSink#name() name} (e.g. {@code log.level.Console}), so the console, file,
 * and window each remember their own setting.
 *
 * <p>This glue lives in the UI layer rather than the {@code logging} package on purpose:
 * {@code logging} stays dependency-free (it must not import {@code storage}), so the layer
 * that already knows about both — the UI — ties them together.
 */
public final class LogLevelPreferences {

    private static final String PREFIX = "log.level.";

    private LogLevelPreferences() {
    }

    /**
     * Applies any saved levels to the currently-registered sinks. Call once at startup,
     * after logging is bootstrapped, so every output honours its remembered level even
     * before the log window is opened. Unknown or unparseable values are ignored (the sink
     * keeps its default), so an edited or stale preferences file can't break logging.
     *
     * @param preferences the shared preferences store to read from
     */
    public static void restore(AppPreferences preferences) {
        for (LogSink sink : LogManager.get().sinks()) {
            preferences.get(PREFIX + sink.name()).ifPresent(value -> {
                try {
                    sink.setLevel(LogLevel.valueOf(value));
                } catch (IllegalArgumentException ignored) {
                    // A no-longer-valid level name: leave the sink at its default.
                }
            });
        }
    }

    /**
     * Records one sink's current level and writes the store to disk. Called when the user
     * changes a level in the log window.
     *
     * @param preferences the shared preferences store to write to
     * @param sink        the sink whose level was just changed
     */
    public static void persist(AppPreferences preferences, LogSink sink) {
        persist(preferences, sink.name(), sink.getLevel());
    }

    /**
     * Records a level against an output's name, for a destination whose level is chosen
     * <em>before</em> a sink exists to carry it — an external destination configured while it
     * is switched off has no registered sink to read the level from.
     *
     * @param preferences the shared preferences store to write to
     * @param sinkName    the sink's {@link LogSink#name() name}
     * @param level       the level to remember
     */
    public static void persist(AppPreferences preferences, String sinkName, LogLevel level) {
        preferences.put(PREFIX + sinkName, level.name());
        preferences.save();
    }

    /**
     * The level remembered for an output that is <em>not</em> registered yet, read under the
     * same key {@link #persist} writes. {@link #restore} can only reach sinks the manager
     * already holds, so an output that has to be built from its saved settings — an external
     * destination — asks for its level here and passes it to its own constructor.
     *
     * @param preferences the shared preferences store to read from
     * @param sinkName    the sink's {@link LogSink#name() name}
     * @return the saved level, or empty if none is saved or the saved value is unparseable
     */
    public static Optional<LogLevel> savedLevel(AppPreferences preferences, String sinkName) {
        return preferences.get(PREFIX + sinkName).flatMap(value -> {
            try {
                return Optional.of(LogLevel.valueOf(value));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }
}
