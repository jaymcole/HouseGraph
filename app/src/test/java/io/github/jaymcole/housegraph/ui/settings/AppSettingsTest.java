package io.github.jaymcole.housegraph.ui.settings;

import io.github.jaymcole.housegraph.logging.FileSink;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the settings model: what a fresh profile gets, what survives a round trip,
 * and what a hand-edited preferences file is allowed to do. {@link SettingsWindow} needs a display;
 * everything asserted here deliberately does not.
 */
class AppSettingsTest {

    private static AppPreferences preferencesIn(Path dir) {
        return AppPreferences.loadFrom(dir.resolve("preferences.json"));
    }

    @Test
    void aFreshProfileGetsTheDocumentedDefaults(@TempDir Path dir) {
        AppSettings settings = AppSettings.load(preferencesIn(dir));

        assertNull(settings.graphFolder(), "no override until one is chosen");
        assertTrue(settings.rememberLastFolder());
        assertTrue(settings.reopenLastGraph());
        assertTrue(settings.restoreWindowSize());
        assertEquals(10, settings.recentFilesCap());
        assertEquals(0, settings.defaultStepDelayMillis(), "watch speed is off by default");
        assertEquals(FileSink.DEFAULT_MAX_BYTES, settings.logFileMaxBytes());
        assertEquals(FileSink.DEFAULT_MAX_BACKUPS, settings.logFileMaxBackups());
        assertTrue(settings.logAutoScroll());
        assertFalse(settings.skipInstallWarning(), "the install warning starts switched on");
        assertEquals(AppSettings.defaults(), settings);
    }

    @Test
    void everySettingSurvivesARoundTrip(@TempDir Path dir, @TempDir Path graphs) {
        AppSettings saved = new AppSettings(
                graphs, false, false, false, 4, 500, 2L * 1024 * 1024, 2, 1_000, false, true);

        AppPreferences preferences = preferencesIn(dir);
        saved.save(preferences);

        assertEquals(saved, AppSettings.load(preferencesIn(dir)));
    }

    @Test
    void clearingTheGraphFolderRemovesTheKeyRatherThanWritingItEmpty(@TempDir Path dir, @TempDir Path graphs) {
        AppSettings chosen = new AppSettings(
                graphs, true, true, true, 10, 0,
                FileSink.DEFAULT_MAX_BYTES, FileSink.DEFAULT_MAX_BACKUPS, 5_000, true, false);
        chosen.save(preferencesIn(dir));
        assertTrue(preferencesIn(dir).get(AppSettings.GRAPH_FOLDER).isPresent());

        AppSettings.defaults().save(preferencesIn(dir));

        assertTrue(preferencesIn(dir).get(AppSettings.GRAPH_FOLDER).isEmpty(),
                "back to the default is said the same way a fresh profile says it");
        assertNull(AppSettings.load(preferencesIn(dir)).graphFolder());
    }

    // --- A hand-edited file must still yield a working app ----------------------------

    @Test
    void outOfRangeValuesAreClampedRatherThanHonoured() {
        AppSettings settings = new AppSettings(
                null, true, true, true, -5, -100, 1, -3, 0, true, false);

        assertEquals(1, settings.recentFilesCap(), "a list of zero entries is not a list");
        assertEquals(0, settings.defaultStepDelayMillis(), "a negative delay is off");
        assertEquals(64L * 1024, settings.logFileMaxBytes(), "below the floor the rotation is the I/O");
        assertEquals(0, settings.logFileMaxBackups());
        assertEquals(100, settings.logBufferCapacity(), "a zero-capacity buffer would throw on use");
    }

    @Test
    void absurdlyLargeValuesAreClampedToo() {
        AppSettings settings = new AppSettings(
                null, true, true, true, 10_000, 0,
                FileSink.DEFAULT_MAX_BYTES, 10_000, 100_000_000, true, false);

        assertEquals(50, settings.recentFilesCap());
        assertEquals(50, settings.logFileMaxBackups());
        assertEquals(200_000, settings.logBufferCapacity());
    }

    @Test
    void aGarbledFileReadsAsTheDefaults(@TempDir Path dir) {
        AppPreferences preferences = preferencesIn(dir);
        preferences.put(AppSettings.RECENT_FILES_CAP, "lots");
        preferences.put(AppSettings.LOG_BUFFER_CAPACITY, "");
        preferences.put(AppSettings.REOPEN_LAST_GRAPH, "maybe");
        preferences.save();

        AppSettings settings = AppSettings.load(preferencesIn(dir));

        assertEquals(AppSettings.defaults(), settings);
    }

    // --- Graph folder validation --------------------------------------------------------

    @Test
    void theDefaultFolderIsAlwaysUsable() {
        assertNull(AppSettings.graphFolderProblem(null));
    }

    @Test
    void aMissingFolderIsCreatedRatherThanRefused(@TempDir Path dir) {
        Path folder = dir.resolve("graphs-that-do-not-exist-yet");

        assertNull(AppSettings.graphFolderProblem(folder));
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    void aFileWhereAFolderIsWantedIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "x");

        String problem = AppSettings.graphFolderProblem(file);

        assertNotNull(problem);
        assertTrue(problem.contains("file"), problem);
    }

    // --- Where a file dialog opens --------------------------------------------------------

    @Test
    void aDialogStartsInTheFolderLastUsedWhenThatIsSwitchedOn(@TempDir Path dir, @TempDir Path graphs) {
        AppPreferences preferences = preferencesIn(dir);

        AppSettings.defaults().rememberGraphDialogDirectory(preferences, new File(graphs.toFile(), "lights.json"));

        assertEquals(graphs, AppSettings.load(preferencesIn(dir)).graphDialogDirectory(preferencesIn(dir)));
    }

    @Test
    void switchingRememberingOffStopsBothTheWriteAndTheRead(@TempDir Path dir, @TempDir Path graphs) {
        AppPreferences preferences = preferencesIn(dir);
        AppSettings off = new AppSettings(
                null, false, true, true, 10, 0,
                FileSink.DEFAULT_MAX_BYTES, FileSink.DEFAULT_MAX_BACKUPS, 5_000, true, false);

        off.rememberGraphDialogDirectory(preferences, new File(graphs.toFile(), "lights.json"));

        assertTrue(preferencesIn(dir).get(AppSettings.LAST_FOLDER).isEmpty(),
                "nothing is recorded while the setting is off");
    }

    @Test
    void aFolderThatHasGoneAwayFallsBackRatherThanLeavingTheDialogNowhere(@TempDir Path dir) {
        AppPreferences preferences = preferencesIn(dir);
        preferences.put(AppSettings.LAST_FOLDER, dir.resolve("on-a-detached-drive").toString());
        preferences.save();

        Path opened = AppSettings.load(preferencesIn(dir)).graphDialogDirectory(preferencesIn(dir));

        assertNotEquals(dir.resolve("on-a-detached-drive"), opened, "the vanished folder is not offered");
        assertTrue(Files.isDirectory(opened), "a dialog is never pointed at a folder that is not there");
    }
}
