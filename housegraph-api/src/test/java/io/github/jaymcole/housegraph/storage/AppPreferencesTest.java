package io.github.jaymcole.housegraph.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // --- Typed accessors ---------------------------------------------------------

    @Test
    void typedValuesRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("preferences.json");
        AppPreferences prefs = AppPreferences.loadFrom(file);
        prefs.putBoolean("on", true);
        prefs.putBoolean("off", false);
        prefs.putLong("count", 42);
        prefs.putLong("big", 5L * 1024 * 1024);
        prefs.save();

        AppPreferences reloaded = AppPreferences.loadFrom(file);
        assertTrue(reloaded.getBoolean("on", false));
        assertFalse(reloaded.getBoolean("off", true));
        assertEquals(42, reloaded.getInt("count", 0));
        assertEquals(5L * 1024 * 1024, reloaded.getLong("big", 0));
    }

    @Test
    void anAbsentKeyReadsAsTheFallback(@TempDir Path dir) {
        AppPreferences prefs = AppPreferences.loadFrom(dir.resolve("preferences.json"));

        assertTrue(prefs.getBoolean("missing", true));
        assertEquals(7, prefs.getInt("missing", 7));
        assertEquals(7L, prefs.getLong("missing", 7));
    }

    @Test
    void anUnparseableValueReadsAsTheFallbackRatherThanZeroOrFalse(@TempDir Path dir) {
        AppPreferences prefs = AppPreferences.loadFrom(dir.resolve("preferences.json"));
        prefs.put("bool", "yes please");
        prefs.put("number", "ten");

        // The point of the fallback: a hand-edited typo must not silently switch a setting off or
        // zero a capacity, which is what parseBoolean/parseInt would do left to themselves.
        assertTrue(prefs.getBoolean("bool", true));
        assertEquals(500, prefs.getInt("number", 500));
        assertEquals(500L, prefs.getLong("number", 500));
    }

    @Test
    void booleansAreReadCaseAndWhitespaceInsensitively(@TempDir Path dir) {
        AppPreferences prefs = AppPreferences.loadFrom(dir.resolve("preferences.json"));
        prefs.put("a", "  TRUE ");
        prefs.put("b", "False");

        assertTrue(prefs.getBoolean("a", false));
        assertFalse(prefs.getBoolean("b", true));
    }

    @Test
    void aValueTooLargeForAnIntFallsBackRatherThanWrappingAround(@TempDir Path dir) {
        AppPreferences prefs = AppPreferences.loadFrom(dir.resolve("preferences.json"));
        prefs.putLong("huge", Long.MAX_VALUE);

        assertEquals(12, prefs.getInt("huge", 12));
        assertEquals(Long.MAX_VALUE, prefs.getLong("huge", 12));
    }
}
