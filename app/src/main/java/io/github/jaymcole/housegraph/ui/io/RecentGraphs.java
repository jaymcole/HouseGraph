package io.github.jaymcole.housegraph.ui.io;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The most-recently-used list of graph files, persisted in {@link AppPreferences} beside
 * {@link AppPreferences#LAST_FILE}.
 *
 * <h2>Shape on disk</h2>
 * One preference key, {@value #PREFERENCE_KEY}, holding a JSON array of absolute paths, newest
 * first. {@code AppPreferences} is string-valued, so the array is encoded into a single string
 * rather than getting a file of its own — the list is a handful of paths, not structured state.
 *
 * <p>Entries are deduplicated by absolute path and capped at {@link #MAX_ENTRIES}; re-opening a
 * file already in the list moves it to the front rather than adding a second row.
 *
 * <h2>Reading is forgiving</h2>
 * A malformed or hand-edited value yields an empty list rather than an error, matching
 * {@code AppPreferences} itself: a broken recent list must never be able to stop a graph opening.
 *
 * <p>Files are recorded verbatim and <b>never pruned for being absent</b>. A graph on an unplugged
 * drive or an unmounted share would otherwise be forgotten by the one launch that happened while it
 * was away; the UI marks such an entry instead, and it comes back when the path does.
 *
 * <p>This is deliberately free of JavaFX so it can be unit-tested headlessly, like the rest of this
 * package — {@code App} owns the menu that renders it.
 */
public final class RecentGraphs {

    private static final Logger log = Log.get(RecentGraphs.class);

    /** Preference key holding the JSON array of recent absolute paths, newest first. */
    public static final String PREFERENCE_KEY = "recentFiles";

    /** How many files are remembered. Beyond this the oldest entry falls off the end. */
    public static final int MAX_ENTRIES = 10;

    /**
     * How much of a folder {@link #describe} will show before eliding its start. A menu is as wide
     * as its widest item, so an unbounded path would stretch it across the window; the folders
     * nearest the file are the ones that identify it, so the elision takes from the left.
     */
    private static final int MAX_FOLDER_LENGTH = 48;

    private RecentGraphs() {
    }

    /**
     * The remembered files, newest first.
     *
     * @param preferences the shared preferences store to read from
     * @return the recent files, empty if none are recorded or the stored value is unreadable
     */
    public static List<File> load(AppPreferences preferences) {
        return preferences.get(PREFERENCE_KEY).map(RecentGraphs::decode).orElseGet(List::of);
    }

    /**
     * Moves {@code file} to the front of the list and writes the store to disk.
     *
     * <p>Writing here is what persists the whole preferences store, so a caller that has just
     * {@code put} another key — {@code App} setting {@link AppPreferences#LAST_FILE} — does not need
     * a second {@link AppPreferences#save()}.
     *
     * @param preferences the shared preferences store to update
     * @param file        the graph file just saved or opened
     * @return the updated list, newest first
     */
    public static List<File> remember(AppPreferences preferences, File file) {
        List<File> updated = new ArrayList<>();
        updated.add(file.getAbsoluteFile());
        updated.addAll(load(preferences));
        List<File> trimmed = trim(updated);

        JSONArray array = new JSONArray();
        trimmed.forEach(entry -> array.put(entry.getPath()));
        preferences.put(PREFERENCE_KEY, array.toString());
        preferences.save();
        return trimmed;
    }

    /**
     * Forgets every remembered file and writes the store to disk.
     *
     * @param preferences the shared preferences store to update
     */
    public static void clear(AppPreferences preferences) {
        preferences.remove(PREFERENCE_KEY);
        preferences.save();
    }

    /**
     * A one-line label for a menu entry: the file's name, then the folder holding it with the home
     * directory shortened to {@code ~} and a long path elided from the left. The folder is part of
     * it because two graphs in different projects are commonly both called something like
     * {@code lights.json}.
     *
     * @param file a remembered file
     * @return the label to show for it
     */
    public static String describe(File file) {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null) {
            return file.getName();
        }
        return file.getName() + "  —  " + elideStart(abbreviateHome(parent.getPath()));
    }

    /** Trims a long folder to its last {@link #MAX_FOLDER_LENGTH} characters, cut at a separator. */
    private static String elideStart(String path) {
        if (path.length() <= MAX_FOLDER_LENGTH) {
            return path;
        }
        int cut = path.indexOf(File.separatorChar, path.length() - MAX_FOLDER_LENGTH);
        // No separator left to cut at means one very long final segment; take the tail as it is.
        return "…" + (cut < 0 ? path.substring(path.length() - MAX_FOLDER_LENGTH) : path.substring(cut));
    }

    private static String abbreviateHome(String path) {
        String home = System.getProperty("user.home", "");
        if (home.isEmpty()) {
            return path;
        }
        if (path.equals(home)) {
            return "~";
        }
        return path.startsWith(home + File.separator) ? "~" + path.substring(home.length()) : path;
    }

    private static List<File> decode(String encoded) {
        List<File> files = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                String path = array.optString(i, "");
                if (!path.isBlank()) {
                    files.add(new File(path));
                }
            }
        } catch (RuntimeException e) {
            // A hand-edited or truncated value: start from an empty list rather than failing.
            log.warn("Ignoring unreadable recent-graph list: {}", e.toString());
            return List.of();
        }
        // Trimmed on read too, so a hand-edited value can't leave duplicates or an unbounded menu.
        return trim(files);
    }

    /** Deduplicates by absolute path, keeping the earliest (newest) occurrence, and caps the size. */
    private static List<File> trim(List<File> files) {
        Map<String, File> byPath = new LinkedHashMap<>();
        for (File file : files) {
            File absolute = file.getAbsoluteFile();
            byPath.putIfAbsent(absolute.getPath(), absolute);
            if (byPath.size() == MAX_ENTRIES) {
                break;
            }
        }
        return List.copyOf(byPath.values());
    }
}
