package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which node libraries are installed, persisted to {@code config/plugins.json}.
 *
 * <p>Its own file rather than a key in {@code AppPreferences}, because that store is
 * string-values-only and an entry here is a structured record. Writes are atomic (temp file plus
 * {@code Files.move}, copying {@code JsonDocumentStore}'s approach rather than
 * {@code AppPreferences}'s plain write): a half-written catalog would leave the app unable to find
 * libraries it has already downloaded, which is a worse failure than losing one edit.
 *
 * <p>Reads are forgiving in the {@code AppPreferences.loadFrom} style — a missing or corrupt file
 * yields an empty catalog and a log line. The consequence is bounded and recoverable: the user's
 * libraries show as not installed and can be reinstalled, whereas throwing would stop the app
 * starting at all.
 *
 * <p>It implements {@link PluginDirectory} so a caller that only needs to <em>ask about</em> a
 * library id — {@code GraphFileIO} writing a save file's {@code plugins} table — can take that
 * one-method view instead of the whole mutable catalog.
 */
public final class PluginCatalog implements PluginDirectory {

    private static final Logger log = Log.get(PluginCatalog.class);

    static final String FILE_NAME = "plugins.json";

    /**
     * One installed library.
     *
     * @param sha256  the hash of the jar as installed. Recorded so a swapped cached jar <em>can</em>
     *                be noticed — but nothing verifies it on load yet; see
     *                {@link PluginInstaller#matchesRecordedHash} and the Security section of
     *                {@code docs/architecture/plugins.md}
     * @param enabled false keeps the jar on disk but out of the class loader
     */
    public record Installed(String id,
                            String name,
                            String version,
                            String repository,
                            String apiVersion,
                            List<String> nodePackages,
                            String categoryPrefix,
                            String sha256,
                            boolean enabled) {

        public Installed {
            nodePackages = List.copyOf(nodePackages);
        }

        static Installed fromManifest(PluginManifest manifest, String repository, String sha256) {
            return new Installed(manifest.id(), manifest.name(), manifest.version(),
                    repository != null ? repository : manifest.repository(),
                    manifest.apiVersion(), manifest.nodePackages(), manifest.categoryPrefix(), sha256, true);
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("name", name);
            json.put("version", version);
            json.put("enabled", enabled);
            json.put("nodePackages", new JSONArray(nodePackages));
            json.put("categoryPrefix", categoryPrefix);
            putIfPresent(json, "repository", repository);
            putIfPresent(json, "apiVersion", apiVersion);
            putIfPresent(json, "sha256", sha256);
            return json;
        }

        private static void putIfPresent(JSONObject json, String key, String value) {
            if (value != null && !value.isBlank()) {
                json.put(key, value);
            }
        }
    }

    private final Path file;
    private final Path pluginsRoot;
    private final Map<String, Installed> byId = new LinkedHashMap<>();

    private PluginCatalog(Path file, Path pluginsRoot) {
        this.file = file;
        this.pluginsRoot = pluginsRoot;
    }

    /** Loads the catalog from the user's config directory. */
    public static PluginCatalog load() {
        return loadFrom(AppDirectories.get().config().resolve(FILE_NAME), AppDirectories.get().plugins());
    }

    /**
     * Loads from an explicit location, mirroring {@code AppPreferences.loadFrom}.
     *
     * <p>Public so a test can build a catalog without touching the real profile — including from
     * another package, since the daemon's install decision is exercised from {@code remote}. Nothing
     * in production should call this: {@link #load()} is the one that knows where the catalog lives.
     *
     * @param file        the catalog JSON
     * @param pluginsRoot where installed jars live
     * @return the catalog; empty if the file is absent or unreadable
     */
    public static PluginCatalog loadFrom(Path file, Path pluginsRoot) {
        PluginCatalog catalog = new PluginCatalog(file, pluginsRoot);
        if (!Files.isRegularFile(file)) {
            return catalog;
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JSONObject root = new JSONObject(new JSONTokener(reader));
            JSONArray plugins = root.optJSONArray("plugins");
            if (plugins != null) {
                for (int i = 0; i < plugins.length(); i++) {
                    JSONObject entry = plugins.optJSONObject(i);
                    Installed installed = readEntry(entry);
                    if (installed != null) {
                        catalog.byId.put(installed.id(), installed);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the node-library catalog at {}; treating it as empty: {}", file, e.toString());
            catalog.byId.clear();
        }
        return catalog;
    }

    private static Installed readEntry(JSONObject entry) {
        if (entry == null) {
            return null;
        }
        String id = entry.optString("id", "").trim();
        if (id.isEmpty()) {
            return null;
        }
        List<String> packages = new ArrayList<>();
        JSONArray declared = entry.optJSONArray("nodePackages");
        if (declared != null) {
            for (int i = 0; i < declared.length(); i++) {
                packages.add(declared.optString(i, ""));
            }
        }
        packages.removeIf(String::isBlank);
        return new Installed(id,
                entry.optString("name", id),
                entry.optString("version", ""),
                entry.optString("repository", null),
                entry.optString("apiVersion", null),
                packages,
                entry.optString("categoryPrefix", id),
                entry.optString("sha256", null),
                entry.optBoolean("enabled", true));
    }

    /** Every installed library, in insertion order. */
    public List<Installed> all() {
        return List.copyOf(byId.values());
    }

    /** Only the libraries whose nodes should currently be loaded. */
    public List<Installed> enabled() {
        return byId.values().stream().filter(Installed::enabled).toList();
    }

    @Override
    public Optional<Installed> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public boolean contains(String id) {
        return id != null && byId.containsKey(id);
    }

    /** Adds or replaces an entry. Does not save; call {@link #save()} when the change is complete. */
    public void put(Installed installed) {
        byId.put(installed.id(), installed);
    }

    public void remove(String id) {
        byId.remove(id);
    }

    /** Enables or disables a library, returning false if it isn't installed. */
    public boolean setEnabled(String id, boolean enabled) {
        Installed existing = byId.get(id);
        if (existing == null) {
            return false;
        }
        byId.put(id, new Installed(existing.id(), existing.name(), existing.version(), existing.repository(),
                existing.apiVersion(), existing.nodePackages(), existing.categoryPrefix(), existing.sha256(), enabled));
        return true;
    }

    /** Where a library's jar lives on disk. */
    public Path jarFor(Installed installed) {
        return pluginsRoot
                .resolve(sanitize(installed.id()))
                .resolve(sanitize(installed.version()))
                .resolve(sanitize(installed.id()) + ".jar");
    }

    /** The plugins root, so the installer and the pruner agree on where jars go. */
    public Path pluginsRoot() {
        return pluginsRoot;
    }

    /**
     * Mirrors {@code AppDirectories.sanitize}, which is private there. Kept identical on purpose:
     * the two must agree, or {@link #jarFor} would look in a directory the installer never wrote to.
     */
    static String sanitize(String key) {
        String cleaned = key == null ? "" : key.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..")) {
            return "_";
        }
        return cleaned;
    }

    /**
     * Writes the catalog atomically. A torn write here would leave the app unable to find libraries
     * it has already downloaded, so the file is staged alongside and moved into place.
     */
    public void save() {
        JSONArray plugins = new JSONArray();
        for (Installed installed : byId.values()) {
            plugins.put(installed.toJson());
        }
        JSONObject root = new JSONObject();
        root.put("plugins", plugins);

        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, root.toString(2), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems (and some network shares) can't do it; a plain replace is still
                // better than writing in place.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the node-library catalog to " + file, e);
        }
    }
}
