package io.github.jaymcole.housegraph.ui.settings;

import io.github.jaymcole.housegraph.logging.FileSink;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.logging.Logging;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import io.github.jaymcole.housegraph.storage.AppPreferences;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Everything the preferences window edits, as one immutable value read from and written to
 * {@link AppPreferences}.
 *
 * <h2>Why a record rather than scattered key lookups</h2>
 * Before this existed, each remembered choice was a key read at its own call site with its own
 * inline parse and its own idea of the default. Collecting them here means the defaults are
 * stated once, the clamping is stated once, and a settings dialog can show what is currently in
 * force without asking five different classes. The keys themselves stay flat strings in
 * {@code preferences.json} — see {@code docs/engine/storage.md}.
 *
 * <h2>Changes take effect immediately</h2>
 * Nothing here is read only at startup. {@link #applyGlobally()} pushes the process-wide half —
 * the graph folder and the logging outputs — onto the live objects that implement them, and the
 * host application applies the per-window half to the windows it owns. The two settings that
 * <em>describe</em> startup ({@link #reopenLastGraph()} and {@link #restoreWindowSize()}) are the
 * exception in kind rather than in plumbing: there is no running thing for them to change, since
 * what they govern has already happened.
 *
 * <h2>Reading is forgiving</h2>
 * Every value is clamped into a usable range by the canonical constructor, so a hand-edited
 * preferences file yields a working app rather than a zero-capacity log buffer or a negative
 * recent-files cap. This is the same rule {@link AppPreferences} follows for the file as a whole.
 *
 * <p>Deliberately free of JavaFX, like {@code ui.io.RecentGraphs}, so the load/save/clamp logic is
 * unit-testable headlessly; {@link SettingsWindow} is the part that needs a display.
 *
 * @param graphFolder             where graph open/save dialogs start, or null for the built-in
 *                                {@link AppDirectories#defaultSaves()}
 * @param rememberLastFolder      whether those dialogs reopen in the folder last used instead
 * @param reopenLastGraph         whether launching bare reopens the last graph
 * @param restoreWindowSize       whether a new editor window takes the last window's size
 * @param recentFilesCap          how many entries File ▸ Open Recent keeps
 * @param defaultStepDelayMillis  the Watch Speed a window starts at
 * @param logFileMaxBytes         the size at which {@code housegraph.log} rolls over
 * @param logFileMaxBackups       how many rolled-over log generations are kept
 * @param logBufferCapacity       how many records the log window's buffer retains
 * @param logAutoScroll           whether the log window follows new records
 * @param skipInstallWarning      whether the node-library install warning is suppressed
 */
public record AppSettings(
        Path graphFolder,
        boolean rememberLastFolder,
        boolean reopenLastGraph,
        boolean restoreWindowSize,
        int recentFilesCap,
        long defaultStepDelayMillis,
        long logFileMaxBytes,
        int logFileMaxBackups,
        int logBufferCapacity,
        boolean logAutoScroll,
        boolean skipInstallWarning) {

    private static final Logger log = Log.get(AppSettings.class);

    // --- Preference keys ----------------------------------------------------------

    /** Absolute path of the chosen graph folder; absent means the built-in default. */
    public static final String GRAPH_FOLDER = "graph.folder";
    /** Whether a graph dialog reopens where the last one left off. */
    public static final String REMEMBER_LAST_FOLDER = "graph.rememberLastFolder";
    /** The folder a graph was last opened from or saved to — state, not a setting. */
    public static final String LAST_FOLDER = "graph.lastFolder";
    /** Whether launching bare reopens {@link AppPreferences#LAST_FILE}. */
    public static final String REOPEN_LAST_GRAPH = "startup.reopenLastGraph";
    /** Whether a new window takes the remembered size. */
    public static final String RESTORE_WINDOW_SIZE = "window.restoreSize";
    /** Remembered editor window width — state, written when a window closes. */
    public static final String WINDOW_WIDTH = "window.width";
    /** Remembered editor window height — state, written when a window closes. */
    public static final String WINDOW_HEIGHT = "window.height";
    /** How many entries the recent-graphs list keeps. */
    public static final String RECENT_FILES_CAP = "recentFiles.cap";
    /** The step delay a window's Watch Speed starts at. */
    public static final String DEFAULT_STEP_DELAY = "run.defaultStepDelayMillis";
    /** The log file's roll threshold in bytes. */
    public static final String LOG_FILE_MAX_BYTES = "log.file.maxBytes";
    /** How many rolled-over log generations are kept. */
    public static final String LOG_FILE_MAX_BACKUPS = "log.file.maxBackups";
    /** How many records the shared log buffer retains. */
    public static final String LOG_BUFFER_CAPACITY = "log.buffer.capacity";
    /** Whether the log window follows new records as they arrive. */
    public static final String LOG_AUTO_SCROLL = "log.window.autoScroll";
    /**
     * Whether the trust-on-first-use install warning is suppressed. Written by the node-library
     * window's "don't show this again" checkbox; named here because the preferences window is
     * the only place it can be switched back on.
     */
    public static final String SKIP_INSTALL_WARNING = "plugin.skipInstallWarning";

    // --- Defaults and bounds ------------------------------------------------------

    /** Default editor window size, used until one has been remembered. */
    public static final double DEFAULT_WINDOW_WIDTH = 1100;
    /** @see #DEFAULT_WINDOW_WIDTH */
    public static final double DEFAULT_WINDOW_HEIGHT = 750;

    /** Default recent-graphs cap. */
    public static final int DEFAULT_RECENT_FILES_CAP = 10;

    private static final int MIN_RECENT_FILES_CAP = 1;
    private static final int MAX_RECENT_FILES_CAP = 50;

    /** Floor on the log roll threshold: below this the rotation is the I/O. */
    private static final long MIN_LOG_FILE_MAX_BYTES = 64L * 1024;
    private static final int MAX_LOG_FILE_MAX_BACKUPS = 50;

    /** Floor on the buffer: fewer records than this and the log window cannot show a stack trace in context. */
    private static final int MIN_LOG_BUFFER_CAPACITY = 100;
    private static final int MAX_LOG_BUFFER_CAPACITY = 200_000;

    /** Clamps every value into a usable range, so a hand-edited file still yields a working app. */
    public AppSettings {
        recentFilesCap = clamp(recentFilesCap, MIN_RECENT_FILES_CAP, MAX_RECENT_FILES_CAP);
        defaultStepDelayMillis = Math.max(0, defaultStepDelayMillis);
        logFileMaxBytes = Math.max(MIN_LOG_FILE_MAX_BYTES, logFileMaxBytes);
        logFileMaxBackups = clamp(logFileMaxBackups, 0, MAX_LOG_FILE_MAX_BACKUPS);
        logBufferCapacity = clamp(logBufferCapacity, MIN_LOG_BUFFER_CAPACITY, MAX_LOG_BUFFER_CAPACITY);
    }

    /** The settings a fresh profile starts with. */
    public static AppSettings defaults() {
        return new AppSettings(
                null,
                true,
                true,
                true,
                DEFAULT_RECENT_FILES_CAP,
                0,
                FileSink.DEFAULT_MAX_BYTES,
                FileSink.DEFAULT_MAX_BACKUPS,
                Logging.BUFFER_CAPACITY,
                true,
                false);
    }

    /**
     * Reads the saved settings, falling back to {@link #defaults()} per value.
     *
     * @param preferences the shared store to read from
     * @return the settings currently in force
     */
    public static AppSettings load(AppPreferences preferences) {
        AppSettings fallback = defaults();
        return new AppSettings(
                readPath(preferences, GRAPH_FOLDER),
                preferences.getBoolean(REMEMBER_LAST_FOLDER, fallback.rememberLastFolder()),
                preferences.getBoolean(REOPEN_LAST_GRAPH, fallback.reopenLastGraph()),
                preferences.getBoolean(RESTORE_WINDOW_SIZE, fallback.restoreWindowSize()),
                preferences.getInt(RECENT_FILES_CAP, fallback.recentFilesCap()),
                preferences.getLong(DEFAULT_STEP_DELAY, fallback.defaultStepDelayMillis()),
                preferences.getLong(LOG_FILE_MAX_BYTES, fallback.logFileMaxBytes()),
                preferences.getInt(LOG_FILE_MAX_BACKUPS, fallback.logFileMaxBackups()),
                preferences.getInt(LOG_BUFFER_CAPACITY, fallback.logBufferCapacity()),
                preferences.getBoolean(LOG_AUTO_SCROLL, fallback.logAutoScroll()),
                preferences.getBoolean(SKIP_INSTALL_WARNING, fallback.skipInstallWarning()));
    }

    /**
     * Writes these settings and flushes the store to disk. Keys whose value is the "unset" one —
     * a null {@link #graphFolder()} — are removed rather than written empty, so the file says
     * "use the default" the same way a fresh profile does.
     *
     * @param preferences the shared store to write to
     */
    public void save(AppPreferences preferences) {
        if (graphFolder == null) {
            preferences.remove(GRAPH_FOLDER);
        } else {
            preferences.put(GRAPH_FOLDER, graphFolder.toString());
        }
        preferences.putBoolean(REMEMBER_LAST_FOLDER, rememberLastFolder);
        preferences.putBoolean(REOPEN_LAST_GRAPH, reopenLastGraph);
        preferences.putBoolean(RESTORE_WINDOW_SIZE, restoreWindowSize);
        preferences.putLong(RECENT_FILES_CAP, recentFilesCap);
        preferences.putLong(DEFAULT_STEP_DELAY, defaultStepDelayMillis);
        preferences.putLong(LOG_FILE_MAX_BYTES, logFileMaxBytes);
        preferences.putLong(LOG_FILE_MAX_BACKUPS, logFileMaxBackups);
        preferences.putLong(LOG_BUFFER_CAPACITY, logBufferCapacity);
        preferences.putBoolean(LOG_AUTO_SCROLL, logAutoScroll);
        preferences.putBoolean(SKIP_INSTALL_WARNING, skipInstallWarning);
        preferences.save();
    }

    // --- Applying ------------------------------------------------------------------

    /**
     * Pushes the process-wide half of these settings onto the live objects that implement them:
     * the graph folder on {@link AppDirectories}, and the rotation policy and buffer size on the
     * registered log outputs.
     *
     * <h4>What this deliberately does not reach</h4>
     * Anything owned by a window — a graph's step delay, the log window's controls — because this
     * class has no way to enumerate windows and no business importing JavaFX to try. The host
     * application applies those; see {@code App.applySettings}.
     *
     * <h4>An unusable graph folder falls back rather than throwing</h4>
     * This runs at startup, where the saved folder may name a drive that is no longer plugged in.
     * A preferences file must never be able to stop the app starting, so a folder that cannot be
     * created is logged and the built-in default is used for this session — the preference itself
     * is left alone, so the folder comes back when the drive does. A dialog collecting a folder
     * from a user should call {@link #graphFolderProblem} first and say so while the picker is
     * still open, rather than relying on this.
     */
    public void applyGlobally() {
        try {
            AppDirectories.get().setSaves(graphFolder);
        } catch (UncheckedIOException e) {
            log.warn("Graph folder {} is unusable, falling back to the default: {}", graphFolder, e.getMessage());
            AppDirectories.get().setSaves(null);
        }
        Logging.fileSink().ifPresent(sink -> sink.setRotationPolicy(logFileMaxBytes, logFileMaxBackups));
        Logging.buffer().setCapacity(logBufferCapacity);
    }

    /**
     * Why {@code folder} cannot be used as the graph folder, for a dialog to show before it
     * commits a choice. Checked here rather than left to {@link #applyGlobally()} throwing,
     * because a user picking a bad folder should be told while the picker is still open.
     *
     * @param folder a candidate folder, or null for the default (always usable)
     * @return the problem, or null when the folder is usable
     */
    public static String graphFolderProblem(Path folder) {
        if (folder == null) {
            return null;
        }
        if (Files.exists(folder) && !Files.isDirectory(folder)) {
            return "That path is a file, not a folder.";
        }
        try {
            Files.createDirectories(folder);
        } catch (Exception e) {
            return "That folder could not be created: " + e.getMessage();
        }
        return Files.isWritable(folder) ? null : "That folder is not writable.";
    }

    // --- Graph dialog folders -------------------------------------------------------

    /**
     * Where a graph open/save dialog should start: the folder last used when
     * {@link #rememberLastFolder()} is on and one has been recorded that still exists, otherwise
     * the graph folder itself.
     *
     * @param preferences the shared store holding the last-used folder
     * @return an existing directory to open the dialog in
     */
    public Path graphDialogDirectory(AppPreferences preferences) {
        if (rememberLastFolder) {
            Path last = readPath(preferences, LAST_FOLDER);
            // A folder that has since gone (an unplugged drive) falls back rather than leaving the
            // dialog at the platform default, which is rarely anywhere useful.
            if (last != null && Files.isDirectory(last)) {
                return last;
            }
        }
        return AppDirectories.get().saves();
    }

    /**
     * Records the folder a graph was just opened from or saved to, for
     * {@link #graphDialogDirectory}. A no-op when {@link #rememberLastFolder()} is off, so
     * switching it off stops the store being updated as well as stopping it being read.
     *
     * @param preferences the shared store to write to
     * @param file        the graph file just opened or saved
     */
    public void rememberGraphDialogDirectory(AppPreferences preferences, File file) {
        if (!rememberLastFolder || file == null) {
            return;
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null) {
            return;
        }
        preferences.put(LAST_FOLDER, parent.getPath());
        preferences.save();
    }

    // --- Helpers ---------------------------------------------------------------------

    /** A saved absolute path, or null when absent, blank or unparseable as a path. */
    private static Path readPath(AppPreferences preferences, String key) {
        return preferences.get(key)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    try {
                        return Path.of(value);
                    } catch (InvalidPathException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }
}
