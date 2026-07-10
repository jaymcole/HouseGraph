package io.github.jaymcole.housegraph.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The single source of truth for where HouseGraph keeps its files on disk, so nothing
 * else in the app has to work out an OS-appropriate location. Everything lives under
 * one root:
 * <ul>
 *   <li><b>Windows</b>: {@code %APPDATA%\HouseGraph}</li>
 *   <li><b>macOS</b>: {@code ~/Library/Application Support/HouseGraph}</li>
 *   <li><b>Linux/other</b>: {@code $XDG_DATA_HOME/HouseGraph}, else {@code ~/.local/share/HouseGraph}</li>
 * </ul>
 * with a subdirectory per purpose — {@link #secrets()}, {@link #nodes()},
 * {@link #config()}, {@link #cache()}, {@link #logs()}. Every accessor creates its directory on demand,
 * so callers can simply resolve a path and read/write it.
 * <p>
 * Use the shared machine instance via {@link #get()} (e.g. {@code AppDirectories.get().secrets()}).
 * The root can be overridden with the {@code housegraph.home} system property or the
 * {@code HOUSEGRAPH_HOME} environment variable — handy for a portable install, and for
 * tests that want a throwaway directory instead of the real user profile.
 */
public final class AppDirectories {

    private static final String APP_NAME = "HouseGraph";

    private final Path root;

    /** Package-visible so tests can root an instance at a temp directory without going through {@link #get()}. */
    AppDirectories(Path root) {
        this.root = root;
    }

    // --- Shared default instance --------------------------------------------------

    private static volatile AppDirectories defaultInstance;

    /**
     * The shared instance rooted at this machine's HouseGraph directory (resolved once).
     *
     * @return the shared machine-wide instance
     */
    public static AppDirectories get() {
        AppDirectories local = defaultInstance;
        if (local == null) {
            synchronized (AppDirectories.class) {
                local = defaultInstance;
                if (local == null) {
                    local = new AppDirectories(resolveRoot(
                            System.getProperty("os.name", ""),
                            System::getenv,
                            System.getProperty("user.home", "."),
                            firstNonBlank(System.getProperty("housegraph.home"), System.getenv("HOUSEGRAPH_HOME"))));
                    defaultInstance = local;
                }
            }
        }
        return local;
    }

    // --- Directory accessors (each created on demand) -----------------------------

    /**
     * The application root directory.
     *
     * @return the root directory, created if needed
     */
    public Path root() {
        return ensure(root);
    }

    /**
     * Secret files (e.g. a {@code .env}) that shouldn't live in a project or a save.
     *
     * @return the secrets directory, created if needed
     */
    public Path secrets() {
        return ensure(root.resolve("secrets"));
    }

    /**
     * Root of per-node persistent storage; see {@link #nodeStorage(String)}.
     *
     * @return the nodes storage root, created if needed
     */
    public Path nodes() {
        return ensure(root.resolve("nodes"));
    }

    /**
     * A private storage directory for one node, under {@link #nodes()}, keyed by a
     * caller-chosen string (e.g. the node's class name). The key is sanitised so it
     * can never escape the nodes directory.
     *
     * @param key a caller-chosen storage key (sanitised to a single safe path segment)
     * @return the private per-node directory for {@code key}, created if needed
     */
    public Path nodeStorage(String key) {
        return ensure(nodes().resolve(sanitize(key)));
    }

    /**
     * Default location for user-saved graph files (the save/open dialog starts here).
     *
     * @return the saves directory, created if needed
     */
    public Path saves() {
        return ensure(root.resolve("saves"));
    }

    /**
     * Preferences / UX state (recent files, window layout, …).
     *
     * @return the config directory, created if needed
     */
    public Path config() {
        return ensure(root.resolve("config"));
    }

    /**
     * Regenerable files (thumbnails, derived data, …) — safe to delete.
     *
     * @return the cache directory, created if needed
     */
    public Path cache() {
        return ensure(root.resolve("cache"));
    }

    /**
     * Log files (the rolling {@code housegraph.log} written by the logging system).
     *
     * @return the logs directory, created if needed
     */
    public Path logs() {
        return ensure(root.resolve("logs"));
    }

    // --- Resolution (pure, so each OS branch is unit-testable) --------------------

    /**
     * Computes the root without touching the filesystem. An explicit {@code override}
     * wins over everything; otherwise the location is chosen from {@code osName},
     * reading {@code APPDATA} / {@code XDG_DATA_HOME} from {@code env} and falling back
     * to {@code userHome}.
     */
    static Path resolveRoot(String osName, UnaryOperator<String> env, String userHome, String override) {
        if (!isBlank(override)) {
            return Path.of(override);
        }
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = env.apply("APPDATA");
            Path base = isBlank(appData) ? Path.of(userHome, "AppData", "Roaming") : Path.of(appData);
            return base.resolve(APP_NAME);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(userHome, "Library", "Application Support", APP_NAME);
        }
        String xdgData = env.apply("XDG_DATA_HOME");
        Path base = isBlank(xdgData) ? Path.of(userHome, ".local", "share") : Path.of(xdgData);
        return base.resolve(APP_NAME);
    }

    // --- Helpers ------------------------------------------------------------------

    private static Path ensure(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create application directory: " + directory, e);
        }
        return directory;
    }

    /**
     * Reduces a node-storage key to a single safe path segment: anything outside
     * {@code [A-Za-z0-9._-]} becomes {@code _} (so separators, drive letters, etc. can't
     * be used to climb out), and the traversal names {@code .}/{@code ..} (and a blank
     * result) collapse to {@code _}.
     */
    private static String sanitize(String key) {
        String cleaned = key == null ? "" : key.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..")) {
            return "_";
        }
        return cleaned;
    }

    private static String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return isBlank(second) ? null : second;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
