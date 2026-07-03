package io.github.jaymcole.housegraph.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPreferencesTest {

    @Test
    void valuesRoundTripThroughTheFile(@TempDir Path dir) {
        Path file = dir.resolve("preferences.json");

        AppPreferences prefs = AppPreferences.loadFrom(file);
        prefs.put(AppPreferences.LAST_FILE, "C:\\graphs\\demo.json");
        prefs.save();

        AppPreferences reloaded = AppPreferences.loadFrom(file);
        assertEquals(Optional.of("C:\\graphs\\demo.json"), reloaded.get(AppPreferences.LAST_FILE));
    }

    @Test
    void missingKeyIsEmpty(@TempDir Path dir) {
        AppPreferences prefs = AppPreferences.loadFrom(dir.resolve("preferences.json"));
        assertTrue(prefs.get("nope").isEmpty());
    }

    @Test
    void removeClearsAValue(@TempDir Path dir) {
        Path file = dir.resolve("preferences.json");
        AppPreferences prefs = AppPreferences.loadFrom(file);
        prefs.put(AppPreferences.LAST_FILE, "x");
        prefs.remove(AppPreferences.LAST_FILE);
        prefs.save();

        assertTrue(AppPreferences.loadFrom(file).get(AppPreferences.LAST_FILE).isEmpty());
    }

    @Test
    void aCorruptFileIsIgnoredRatherThanCrashing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("preferences.json");
        Files.writeString(file, "{ not valid json ", StandardCharsets.UTF_8);

        AppPreferences prefs = AppPreferences.loadFrom(file);
        assertTrue(prefs.get(AppPreferences.LAST_FILE).isEmpty());
    }
}
