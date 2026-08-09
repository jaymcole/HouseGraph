package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The operator's own configuration for unattended running, read from {@code config/remote.json}.
 *
 * <h2>This file is the trust boundary</h2>
 * Everything the daemon will fetch and run traces back to a URL written here <em>by hand</em>. That
 * is deliberate. A save file is untrusted input — {@code docs/architecture/plugins.md} is explicit
 * that a graph proposing a code download must never be acted on silently — so the daemon takes its
 * marching orders from this file, not from the graphs it syncs. A graph repository can say which
 * node libraries it wants; it cannot widen the set of places they may come from.
 *
 * <p>Auto-installing node libraries is gated twice over: {@link #allowPluginInstall()} is
 * <b>false</b> unless the operator turns it on, and even then a library is only installed when its
 * repository appears in {@link #trustedPluginRepositories()}. A graph needing something outside that
 * set still runs — its nodes become placeholders, which is already safe — with a line in the log
 * saying what was skipped.
 *
 * <p>Reads are forgiving in the {@code AppPreferences}/{@code PluginCatalog} style: a missing file
 * yields defaults so {@code doctor} can explain what to write, and a corrupt one is reported rather
 * than thrown, because a daemon that refuses to start is harder to diagnose remotely than one that
 * starts and says what is wrong.
 */
public final class RemoteConfig {

    private static final Logger log = Log.get(RemoteConfig.class);

    static final String FILE_NAME = "remote.json";

    /**
     * How often to ask each remote whether it has moved, when the file doesn't say. A minute is
     * cheap — {@code git ls-remote} is one short-lived connection against the git protocol, not the
     * REST API whose 60-per-hour budget shapes {@code GitHubReleases} — and it bounds how stale a
     * deployed graph can be.
     */
    static final int DEFAULT_POLL_SECONDS = 60;

    /** The floor on {@link #pollSeconds()}. Below this the polling is the load. */
    static final int MINIMUM_POLL_SECONDS = 5;

    /**
     * One graph repository to track.
     *
     * @param url        a git URL — SSH ({@code git@github.com:owner/repo.git}) is the documented
     *                   default because it keeps credentials out of HouseGraph entirely
     * @param branch     the branch to follow
     * @param tokenSecret the {@code SecretsStore} key holding a personal access token, for an HTTPS
     *                   URL against a private repository; null for SSH or a public repository
     */
    public record Repository(String url, String branch, String tokenSecret) {

        public Repository {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("a repository needs a url");
            }
            url = url.trim();
            branch = branch == null || branch.isBlank() ? "main" : branch.trim();
            tokenSecret = tokenSecret == null || tokenSecret.isBlank() ? null : tokenSecret.trim();
        }

        /**
         * A stable, filesystem-safe key for this repository's local clone, derived from the URL's
         * owner and repo. Derived rather than user-supplied so the same URL always maps to the same
         * directory across restarts; {@code AppDirectories.remoteRepo} sanitises it again before it
         * touches the disk.
         */
        public String key() {
            String path = url.replaceAll("\\.git$", "");
            // Handles both https://host/owner/repo and git@host:owner/repo.
            int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
            String repo = cut < 0 ? path : path.substring(cut + 1);
            String rest = cut < 0 ? "" : path.substring(0, cut);
            int ownerCut = Math.max(rest.lastIndexOf('/'), rest.lastIndexOf(':'));
            String owner = ownerCut < 0 ? rest : rest.substring(ownerCut + 1);
            String key = (owner + "-" + repo).toLowerCase(Locale.ROOT);
            return key.isBlank() || key.equals("-") ? "repository" : key;
        }
    }

    private final List<Repository> repositories;
    private final int pollSeconds;
    private final boolean allowPluginInstall;
    private final List<String> trustedPluginRepositories;

    RemoteConfig(List<Repository> repositories,
                 int pollSeconds,
                 boolean allowPluginInstall,
                 List<String> trustedPluginRepositories) {
        this.repositories = List.copyOf(repositories);
        this.pollSeconds = Math.max(MINIMUM_POLL_SECONDS, pollSeconds);
        this.allowPluginInstall = allowPluginInstall;
        this.trustedPluginRepositories = List.copyOf(trustedPluginRepositories);
    }

    /** The config at {@code config/remote.json}, or defaults when there isn't one yet. */
    public static RemoteConfig load() {
        return loadFrom(AppDirectories.get().config().resolve(FILE_NAME));
    }

    /**
     * Reads a config from an explicit path. Package-visible so tests never touch the real profile.
     *
     * @param file the config file; need not exist
     * @return the parsed config, or defaults when the file is missing or unreadable
     */
    static RemoteConfig loadFrom(Path file) {
        if (!Files.isRegularFile(file)) {
            return new RemoteConfig(List.of(), DEFAULT_POLL_SECONDS, false, List.of());
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return fromJson(new JSONObject(new JSONTokener(reader)));
        } catch (IOException | RuntimeException e) {
            log.error("Could not read {} — running with no repositories configured", file, e);
            return new RemoteConfig(List.of(), DEFAULT_POLL_SECONDS, false, List.of());
        }
    }

    /** Builds a config from parsed JSON. Pure, so the parsing rules are directly testable. */
    static RemoteConfig fromJson(JSONObject root) {
        List<Repository> repositories = new ArrayList<>();
        JSONArray array = root.optJSONArray("repositories");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String url = entry.optString("url", "").trim();
                if (url.isEmpty()) {
                    // One malformed row must not cost the operator every other repository.
                    log.warn("Ignoring a repository entry with no url");
                    continue;
                }
                repositories.add(new Repository(url,
                        entry.optString("branch", "main"),
                        entry.optString("tokenSecret", null)));
            }
        }

        List<String> trusted = new ArrayList<>();
        JSONArray trustedArray = root.optJSONArray("trustedPluginRepositories");
        if (trustedArray != null) {
            for (int i = 0; i < trustedArray.length(); i++) {
                String value = trustedArray.optString(i, "").trim();
                if (!value.isEmpty()) {
                    trusted.add(value);
                }
            }
        }

        return new RemoteConfig(repositories,
                root.optInt("pollSeconds", DEFAULT_POLL_SECONDS),
                root.optBoolean("allowPluginInstall", false),
                trusted);
    }

    public List<Repository> repositories() {
        return repositories;
    }

    /** Seconds between {@code ls-remote} checks, never below {@link #MINIMUM_POLL_SECONDS}. */
    public int pollSeconds() {
        return pollSeconds;
    }

    /** Whether the daemon may install node libraries at all. Off unless the operator says so. */
    public boolean allowPluginInstall() {
        return allowPluginInstall;
    }

    public List<String> trustedPluginRepositories() {
        return trustedPluginRepositories;
    }

    /**
     * Whether a node library may be installed from {@code repositoryUrl} without a human present.
     *
     * <p>Compared after normalising away a trailing {@code .git} and case, so the operator writing
     * the URL the way GitHub displays it still matches a manifest that wrote it the way git clones
     * it. Anything not matched is refused — an allowlist that guesses is not an allowlist.
     *
     * @param repositoryUrl the repository a manifest asked to install from
     * @return true when installing from it is permitted
     */
    public boolean isTrustedForInstall(String repositoryUrl) {
        if (!allowPluginInstall || repositoryUrl == null || repositoryUrl.isBlank()) {
            return false;
        }
        String candidate = normalise(repositoryUrl);
        return trustedPluginRepositories.stream().anyMatch(trusted -> normalise(trusted).equals(candidate));
    }

    private static String normalise(String url) {
        return url.trim().toLowerCase(Locale.ROOT).replaceAll("\\.git$", "").replaceAll("/+$", "");
    }
}
