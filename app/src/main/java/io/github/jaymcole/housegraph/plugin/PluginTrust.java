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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which repositories the user has agreed may install node libraries without asking again, persisted
 * to {@code config/plugin-trust.json}.
 *
 * <h2>Two gates, not one</h2>
 * A save file is untrusted input proposing a code download — it names a repository URL, and acting on
 * that URL means fetching and running arbitrary code with the user's full privileges. A single
 * "auto-install missing libraries" switch would therefore let <em>any</em> graph file nominate
 * <em>any</em> repository, which is a far larger blast radius than the convenience is worth.
 *
 * <p>So this mirrors the shape the daemon already uses ({@code RemoteConfig.allowPluginInstall} plus
 * {@code trustedPluginRepositories}): {@link #isAutoInstallEnabled()} is <b>false</b> unless the user
 * turns it on, and even then {@link #isTrustedForInstall} only says yes for a repository already on
 * the list. The difference is how the list gets written. The operator of a remote server edits
 * {@code remote.json} by hand; here the list is populated one entry at a time from the install
 * confirmation dialog the user is already looking at, which is the only place {@link #trust} is
 * called. There is no path from a save file to a new trusted repository.
 *
 * <h2>Its own file</h2>
 * Not a key in {@code AppPreferences}, which is string-values-only. Not a sibling key in
 * {@code plugins.json} either: that file is the record of what <em>is</em> installed, and this is
 * policy about what <em>may</em> be. Keeping them apart means a corrupt catalog cannot silently widen
 * trust, and a reset of one does not reset the other.
 *
 * <p>Reads are forgiving in the {@link PluginCatalog} style — a missing or corrupt file yields the
 * safe defaults (auto-install off, nothing trusted) plus a log line, rather than stopping the app.
 * Writes are atomic for the same reason the catalog's are, with the failure direction that matters
 * here: a torn write must never leave a half-parsed file that happens to read as "trusted".
 */
public final class PluginTrust {

    private static final Logger log = Log.get(PluginTrust.class);

    static final String FILE_NAME = "plugin-trust.json";

    private final Path file;
    private final Set<String> trustedRepositories = new LinkedHashSet<>();
    private boolean autoInstall;

    private PluginTrust(Path file) {
        this.file = file;
    }

    /** Loads the trust store from the user's config directory. */
    public static PluginTrust load() {
        return loadFrom(AppDirectories.get().config().resolve(FILE_NAME));
    }

    /**
     * Loads from an explicit location. The test seam, mirroring {@link PluginCatalog#loadFrom}.
     *
     * @param file the trust JSON
     * @return the store; auto-install off and nothing trusted if the file is absent or unreadable
     */
    static PluginTrust loadFrom(Path file) {
        PluginTrust trust = new PluginTrust(file);
        if (!Files.isRegularFile(file)) {
            return trust;
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JSONObject root = new JSONObject(new JSONTokener(reader));
            trust.autoInstall = root.optBoolean("autoInstall", false);
            JSONArray repositories = root.optJSONArray("trustedRepositories");
            if (repositories != null) {
                for (int i = 0; i < repositories.length(); i++) {
                    String url = repositories.optString(i, "").trim();
                    if (!url.isEmpty()) {
                        trust.trustedRepositories.add(url);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Fail closed. Anything unreadable here must land on "trust nothing", never on a
            // partially-parsed list that happens to contain a repository.
            log.warn("Could not read the node-library trust store at {}; trusting nothing: {}", file, e.toString());
            trust.trustedRepositories.clear();
            trust.autoInstall = false;
        }
        return trust;
    }

    /** Whether the user has switched on installing without a prompt at all. */
    public boolean isAutoInstallEnabled() {
        return autoInstall;
    }

    /** Turns auto-install on or off. Saves immediately — this is a deliberate, one-off user action. */
    public void setAutoInstallEnabled(boolean enabled) {
        autoInstall = enabled;
        save();
    }

    /** The trusted repository URLs, as the user entered them, in the order they were accepted. */
    public List<String> trustedRepositories() {
        return List.copyOf(trustedRepositories);
    }

    /**
     * Records that the user accepted this repository, so later installs and updates from it need no
     * prompt.
     *
     * <p><b>Only ever call this from a confirmation the user actually saw.</b> The stored URL is the
     * one they were shown, not a normalised form — {@link RepositoryUrls} handles matching, and
     * keeping the original spelling means the list they later review reads the way they wrote it.
     *
     * @param repositoryUrl the repository just confirmed
     */
    public void trust(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return;
        }
        if (trustedRepositories.add(repositoryUrl.trim())) {
            save();
        }
    }

    /**
     * Withdraws trust from a repository, matching however it was spelled when it was added.
     *
     * @param repositoryUrl the repository to stop trusting
     * @return true when something was actually removed
     */
    public boolean revoke(String repositoryUrl) {
        boolean removed = trustedRepositories.removeIf(
                trusted -> RepositoryUrls.normalise(trusted).equals(RepositoryUrls.normalise(repositoryUrl)));
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * Whether a node library may be installed from {@code repositoryUrl} with no prompt.
     *
     * <p>Both gates, in one call, deliberately: every caller wants the conjunction, and a caller that
     * checked only the list would auto-install for a user who never switched the feature on.
     * Mirrors {@code RemoteConfig.isTrustedForInstall} in name and meaning so the two trust models
     * read the same way.
     *
     * @param repositoryUrl the repository a save file named
     * @return true when installing from it needs no confirmation
     */
    public boolean isTrustedForInstall(String repositoryUrl) {
        return autoInstall && RepositoryUrls.matches(trustedRepositories, repositoryUrl);
    }

    /**
     * Writes the store atomically, staging alongside and moving into place — the same approach as
     * {@link PluginCatalog#save()}, because a torn write to a file that governs whether code may be
     * downloaded is the one outcome worth spending a temp file to avoid.
     */
    private void save() {
        JSONObject root = new JSONObject();
        root.put("autoInstall", autoInstall);
        root.put("trustedRepositories", new JSONArray(trustedRepositories));

        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, root.toString(2), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the node-library trust store to " + file, e);
        }
    }
}
