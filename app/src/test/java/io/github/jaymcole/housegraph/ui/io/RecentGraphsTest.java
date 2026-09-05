package io.github.jaymcole.housegraph.ui.io;

import io.github.jaymcole.housegraph.storage.AppPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the most-recently-used list headlessly: ordering, deduplication, the cap, and the
 * forgiving read. Preferences are backed by a temp file, never the real profile.
 */
class RecentGraphsTest {

    private static AppPreferences preferencesIn(Path dir) {
        return AppPreferences.loadFrom(dir.resolve("preferences.json"));
    }

    /** The names of the remembered files, newest first — the order the menu renders. */
    private static List<String> names(List<File> files) {
        return files.stream().map(File::getName).toList();
    }

    @Test
    void nothingIsRememberedOnAFreshProfile(@TempDir Path dir) {
        assertEquals(List.of(), RecentGraphs.load(preferencesIn(dir)));
    }

    @Test
    void newestComesFirstAndSurvivesTheNextLaunch(@TempDir Path dir) {
        RecentGraphs.remember(preferencesIn(dir), new File(dir.toFile(), "lights.json"));
        RecentGraphs.remember(preferencesIn(dir), new File(dir.toFile(), "doorbell.json"));

        // A separate load stands in for the next launch: the list came back off disk.
        assertEquals(List.of("doorbell.json", "lights.json"), names(RecentGraphs.load(preferencesIn(dir))));
    }

    @Test
    void reopeningAFileMovesItToTheFrontRatherThanRepeatingIt(@TempDir Path dir) {
        RecentGraphs.remember(preferencesIn(dir), new File(dir.toFile(), "lights.json"));
        RecentGraphs.remember(preferencesIn(dir), new File(dir.toFile(), "doorbell.json"));
        RecentGraphs.remember(preferencesIn(dir), new File(dir.toFile(), "lights.json"));

        assertEquals(List.of("lights.json", "doorbell.json"), names(RecentGraphs.load(preferencesIn(dir))));
    }

    @Test
    void pathsAreStoredAbsoluteSoARelativeArgumentStillMatches(@TempDir Path dir) {
        RecentGraphs.remember(preferencesIn(dir), new File("lights.json"));
        RecentGraphs.remember(preferencesIn(dir), new File("lights.json").getAbsoluteFile());

        List<File> recent = RecentGraphs.load(preferencesIn(dir));
        assertEquals(1, recent.size(), "the same file named two ways is one entry");
        assertTrue(recent.get(0).isAbsolute());
    }

    @Test
    void theOldestEntryFallsOffTheEndAtTheCap(@TempDir Path dir) {
        for (int i = 0; i <= RecentGraphs.MAX_ENTRIES; i++) {
            RecentGraphs.remember(preferencesIn(dir), new File(dir.toFile(), "graph-" + i + ".json"));
        }

        List<File> recent = RecentGraphs.load(preferencesIn(dir));
        assertEquals(RecentGraphs.MAX_ENTRIES, recent.size());
        assertEquals("graph-" + RecentGraphs.MAX_ENTRIES + ".json", recent.get(0).getName());
        assertTrue(names(recent).stream().noneMatch("graph-0.json"::equals), "the oldest is gone");
    }

    @Test
    void clearForgetsEverything(@TempDir Path dir) {
        RecentGraphs.remember(preferencesIn(dir), new File(dir.toFile(), "lights.json"));
        RecentGraphs.clear(preferencesIn(dir));

        assertEquals(List.of(), RecentGraphs.load(preferencesIn(dir)));
    }

    @Test
    void anUnreadableStoredValueYieldsAnEmptyListRatherThanFailing(@TempDir Path dir) {
        AppPreferences preferences = preferencesIn(dir);
        preferences.put(RecentGraphs.PREFERENCE_KEY, "{not a json array");
        preferences.save();

        assertEquals(List.of(), RecentGraphs.load(preferencesIn(dir)));
    }

    @Test
    void aMissingFileIsKeptSoAnUnpluggedDriveIsNotForgotten(@TempDir Path dir) {
        File absent = new File(dir.toFile(), "on-a-detached-drive.json");
        RecentGraphs.remember(preferencesIn(dir), absent);

        assertEquals(List.of("on-a-detached-drive.json"), names(RecentGraphs.load(preferencesIn(dir))));
    }

    @Test
    void describeElidesTheStartOfADeepFolderSoTheMenuStaysNarrow() {
        File deep = new File("/very-long-prefix-that-goes-on/and-on-and-on/and-further-still/"
                + "projects/house/graphs/lights.json");
        String label = RecentGraphs.describe(deep);

        assertTrue(label.startsWith("lights.json"), label);
        assertTrue(label.contains("…" + File.separator), "the folder is elided from the left: " + label);
        assertTrue(label.endsWith("projects" + File.separator + "house" + File.separator + "graphs"),
                "the folders nearest the file survive: " + label);
    }

    @Test
    void describeNamesTheFileAndItsFolder() {
        File home = new File(System.getProperty("user.home"));
        String label = RecentGraphs.describe(new File(new File(home, "graphs"), "lights.json"));

        assertTrue(label.startsWith("lights.json"), label);
        assertTrue(label.endsWith("~" + File.separator + "graphs"), "the home directory is shortened: " + label);
    }
}
