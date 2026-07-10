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
 * opened file, and whatever UI state we add later (window size, recent files, …). Kept
 * as plain JSON under {@link AppDirectories#config()}.
 * <p>
 * Reading is forgiving: a missing or corrupt file yields empty preferences rather than
 * failing, so a bad preferences file can never stop the app from starting. Writing is
 * explicit via {@link #save()}.
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
