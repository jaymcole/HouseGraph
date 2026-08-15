package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code housegraph.json} at the root of a synced graph repository: which graphs to run, and which
 * node libraries they need.
 *
 * <h2>Why a manifest, when the save files also record their dependencies</h2>
 * {@code RemoteDeployment} reads <b>both</b>. This file used to argue that it must not — that a save
 * file's {@code plugins} table describes what a graph was built against on someone else's machine,
 * and so could not be acted on unattended. <b>That reasoning has been withdrawn.</b> It does not
 * survive contact with how a graph repository is actually used: these save files are commits in a
 * repository the operator named by hand in {@code remote.json}, sitting beside this very manifest.
 * Anyone able to add a save file here can already edit this file, or commit a graph that does
 * anything at all. Requiring the libraries to be restated here as well was ceremony, and it was the
 * thing stopping a fresh server from coming up with no per-library configuration.
 *
 * <p>What the manifest is still for, and why its entries take precedence:
 * <ul>
 *   <li><b>A version floor.</b> {@code plugins[].version} is the only place to say "at least this",
 *       which is what makes an update happen at all. A save file's recorded version is whatever the
 *       authoring machine had; this one is a statement of intent someone wrote down.</li>
 *   <li><b>Which graphs run</b>, including {@code enabled: false} to park one.</li>
 *   <li><b>A repository for a library a save file names bare.</b> A v1 save, or one written before
 *       the full {@code plugins} row existed, records an id with no URL and cannot be installed from
 *       on its own.</li>
 * </ul>
 *
 * <p>{@link RemoteConfig#isTrustedForInstall} has the final say regardless of what either source
 * asks for. The desktop app remains different in kind: it never auto-installs, because a save file
 * opened there may have arrived from anywhere.
 *
 * <h2>Path safety</h2>
 * {@code graphs[].file} is a repository-relative path from a file fetched over the network, so it is
 * resolved and then checked to still be inside the clone. Without that, {@code ../../../} in a
 * manifest would make the daemon load and execute a graph from anywhere on the disk.
 */
public final class RepoManifest {

    private static final Logger log = Log.get(RepoManifest.class);

    /** The file a synced repository is expected to carry at its root. */
    public static final String FILE_NAME = "housegraph.json";

    /**
     * One graph to run.
     *
     * @param file    repository-relative path to the {@code .json} save file
     * @param enabled false parks a graph in the repository without running it
     */
    public record GraphEntry(String file, boolean enabled) {
    }

    /**
     * One node library the graphs here need.
     *
     * @param id         the library id, matching its manifest and the save files' {@code plugin} key
     * @param repository where it can be installed from — still subject to the operator's allowlist
     * @param version    the minimum version the graphs need. Read as "at least this": when the
     *                   installed library is behind it, {@code RemoteDeployment} updates to the
     *                   repository's latest release. May be null, which means "any version will do",
     *                   so an entry with no version installs once and is never moved again
     */
    public record PluginEntry(String id, String repository, String version) {
    }

    private final List<GraphEntry> graphs;
    private final List<PluginEntry> plugins;

    RepoManifest(List<GraphEntry> graphs, List<PluginEntry> plugins) {
        this.graphs = List.copyOf(graphs);
        this.plugins = List.copyOf(plugins);
    }

    /**
     * Reads the manifest at the root of a clone.
     *
     * @param cloneRoot the repository's local clone directory
     * @return the manifest, or empty when the repository doesn't carry one
     */
    public static Optional<RepoManifest> read(Path cloneRoot) {
        Path file = cloneRoot.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Optional.of(fromJson(new JSONObject(new JSONTokener(reader))));
        } catch (IOException | RuntimeException e) {
            // A broken manifest means running nothing from this repository. Reported, not thrown:
            // the daemon keeps serving every other repository it tracks.
            log.error("Could not read {} — no graphs will run from this repository", file, e);
            return Optional.empty();
        }
    }

    /** Builds a manifest from parsed JSON. Pure, so the parsing rules are directly testable. */
    static RepoManifest fromJson(JSONObject root) {
        List<GraphEntry> graphs = new ArrayList<>();
        JSONArray graphsJson = root.optJSONArray("graphs");
        if (graphsJson != null) {
            for (int i = 0; i < graphsJson.length(); i++) {
                JSONObject entry = graphsJson.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String file = entry.optString("file", "").trim();
                if (file.isEmpty()) {
                    log.warn("Ignoring a graphs[] entry with no file");
                    continue;
                }
                graphs.add(new GraphEntry(file, entry.optBoolean("enabled", true)));
            }
        }

        List<PluginEntry> plugins = new ArrayList<>();
        JSONArray pluginsJson = root.optJSONArray("plugins");
        if (pluginsJson != null) {
            for (int i = 0; i < pluginsJson.length(); i++) {
                JSONObject entry = pluginsJson.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String id = entry.optString("id", "").trim();
                if (id.isEmpty()) {
                    log.warn("Ignoring a plugins[] entry with no id");
                    continue;
                }
                plugins.add(new PluginEntry(id,
                        emptyToNull(entry.optString("repository", "")),
                        emptyToNull(entry.optString("version", ""))));
            }
        }
        return new RepoManifest(graphs, plugins);
    }

    /** Every graph entry, including disabled ones. */
    public List<GraphEntry> graphs() {
        return graphs;
    }

    public List<PluginEntry> plugins() {
        return plugins;
    }

    /**
     * The absolute paths of the enabled graphs, skipping any that escape the clone or don't exist.
     *
     * @param cloneRoot the repository's local clone directory
     * @return resolved, existing, in-repository graph files, in manifest order
     */
    public List<Path> resolveGraphs(Path cloneRoot) {
        Path root = cloneRoot.toAbsolutePath().normalize();
        List<Path> resolved = new ArrayList<>();
        for (GraphEntry entry : graphs) {
            if (!entry.enabled()) {
                continue;
            }
            Path candidate = root.resolve(entry.file()).normalize();
            if (!candidate.startsWith(root)) {
                // The manifest came from the network. A path climbing out of the clone would have
                // the daemon load and run a graph from somewhere the repository has no business
                // reaching, so it is refused rather than clamped.
                log.error("Refusing graph \"{}\": it resolves outside the repository", entry.file());
                continue;
            }
            if (!Files.isRegularFile(candidate)) {
                log.error("Manifest lists \"{}\" but there is no such file in the repository", entry.file());
                continue;
            }
            resolved.add(candidate);
        }
        return resolved;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
