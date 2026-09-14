package io.github.jaymcole.housegraph.storage;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A small persistent key/value store for non-sensitive app preferences — the last
 * opened file, the settings the preferences window edits, and whatever UI state we add
 * later. Kept as plain JSON under {@link AppDirectories#config()}.
 * <p>
 * Reading is forgiving: a missing or corrupt file yields empty preferences rather than
 * failing, so a bad preferences file can never stop the app from starting. Writing is
 * explicit via {@link #save()}.
 *
 * <h2>Values are strings; the typed accessors are the forgiving part</h2>
 * The file holds strings only, so anything structured gets a file of its own beside it
 * (see {@code docs/engine/storage.md}). Booleans and numbers are common enough to be
 * worth reading back without every call site writing its own parse, so
 * {@link #getBoolean}, {@link #getInt} and {@link #getLong} take the value to use when
 * nothing is saved <em>and</em> when what is saved does not parse. A hand-edited or
 * stale value therefore reads as the default rather than propagating a
 * {@code NumberFormatException} into whatever was being configured, which is the same
 * rule {@link #loadFrom} follows for the file as a whole.
 */
public final class AppPreferences {

    private static final Logger log = Log.get(AppPreferences.class);

    private static final String FILE = "preferences.json";

    /** Preference key for the absolute path of the most recently saved/opened graph. */
    public static final String LAST_FILE = "lastFile";

    private final Path file;
    private final Map<String, String> values;

    private AppPreferences(Path file, Map<String, String> values) {
        this.file = file;
        this.values = values;
    }

    /**
     * Loads the machine's preferences from {@link AppDirectories#config()}.
     *
     * @return the loaded preferences (empty if the file is missing or unreadable)
     */
    public static AppPreferences load() {
        return loadFrom(AppDirectories.get().config().resolve(FILE));
    }

    /**
     * Loads preferences from an explicit file rather than the machine default — handy for a
     * portable install, and for tests that point at a temp dir. Reading is forgiving in the
     * same way as {@link #load()}.
     *
     * @param file the preferences JSON file to read (may be absent)
     * @return the loaded preferences (empty if the file is missing or unreadable)
     */
    public static AppPreferences loadFrom(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                JSONObject json = new JSONObject(new JSONTokener(Files.readString(file, StandardCharsets.UTF_8)));
                for (String key : json.keySet()) {
                    values.put(key, json.getString(key));
                }
            } catch (IOException | RuntimeException e) {
                // Never let a bad preferences file block startup — just start fresh.
                log.warn("Ignoring unreadable preferences file {}: {}", file, e);
            }
        }
        return new AppPreferences(file, values);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public void put(String key, String value) {
        values.put(key, value);
    }

    public void remove(String key) {
        values.remove(key);
    }

    // --- Typed accessors ----------------------------------------------------------

    /**
     * A saved value read as a boolean.
     *
     * <h4>Unparseable values</h4>
     * Only {@code "true"} and {@code "false"} (either case, surrounded by any whitespace)
     * are recognised. Anything else yields {@code fallback} rather than {@code false},
     * which {@code Boolean.parseBoolean} would give — a typo in a hand-edited file should
     * leave the setting at its default, not silently switch it off.
     *
     * @param key      the preference key
     * @param fallback the value to use when nothing usable is saved
     * @return the saved boolean, or {@code fallback}
     */
    public boolean getBoolean(String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    /**
     * A saved value read as an {@code int}.
     *
     * @param key      the preference key
     * @param fallback the value to use when nothing usable is saved
     * @return the saved int, or {@code fallback} when absent, unparseable, or out of int range
     */
    public int getInt(String key, int fallback) {
        long value = getLong(key, fallback);
        return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? fallback : (int) value;
    }

    /**
     * A saved value read as a {@code long}.
     *
     * @param key      the preference key
     * @param fallback the value to use when nothing usable is saved
     * @return the saved long, or {@code fallback} when absent or unparseable
     */
    public long getLong(String key, long fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Stores a boolean. Written in the form {@link #getBoolean} reads back.
     *
     * @param key   the preference key
     * @param value the value to store
     */
    public void putBoolean(String key, boolean value) {
        values.put(key, Boolean.toString(value));
    }

    /**
     * Stores an integral value. Written in the form {@link #getInt} and {@link #getLong}
     * read back.
     *
     * @param key   the preference key
     * @param value the value to store
     */
    public void putLong(String key, long value) {
        values.put(key, Long.toString(value));
    }

    public void save() {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write preferences: " + file, e);
        }
    }
}
