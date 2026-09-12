package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The last commit successfully synced for each tracked repository, kept in
 * {@code config/remote-state.json}.
 *
 * <p>Written so a restarted daemon doesn't treat every repository as changed and bounce every graph
 * it supervises — which, on a machine that reboots, would mean an unnecessary restart storm at
 * exactly the moment the operator wants things to come up quietly.
 *
 * <p>It also carries what the self-updater has to remember between runs: the {@code ETag} of the
 * last release lookup, so a restart costs nothing against GitHub's hourly budget, and the version it
 * last installed, which is what stops a botched swap from downloading the same release forever.
 *
 * <p>Atomic writes, in the same shape as {@code PluginCatalog}: a half-written state file would
 * leave the daemon unsure what it had already deployed.
 */
public final class RemoteState {

    private static final Logger log = Log.get(RemoteState.class);

    static final String FILE_NAME = "remote-state.json";

    private final Path file;
    private final Map<String, String> shaByKey = new LinkedHashMap<>();
    private String releaseEtag;
    private String appliedVersion;

    private RemoteState(Path file) {
        this.file = file;
    }

    /** The state at {@code config/remote-state.json}. */
    public static RemoteState load() {
        return loadFrom(AppDirectories.get().config().resolve(FILE_NAME));
    }

    /** Reads state from an explicit path. Package-visible so tests never touch the real profile. */
    static RemoteState loadFrom(Path file) {
        RemoteState state = new RemoteState(file);
        if (!Files.isRegularFile(file)) {
            return state;
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JSONObject root = new JSONObject(new JSONTokener(reader));
            JSONObject synced = root.optJSONObject("synced");
            if (synced != null) {
                for (String key : synced.keySet()) {
                    state.shaByKey.put(key, synced.optString(key, ""));
                }
            }
            JSONObject selfUpdate = root.optJSONObject("selfUpdate");
            if (selfUpdate != null) {
                state.releaseEtag = blankToNull(selfUpdate.optString("etag", null));
                state.appliedVersion = blankToNull(selfUpdate.optString("appliedVersion", null));
            }
        } catch (IOException | RuntimeException e) {
            // Forgiving, like every other store here. The cost of a lost state file is one extra
            // sync and one extra restart, which is far cheaper than refusing to start.
            log.warn("Could not read {}; treating every repository as unsynced", file, e);
        }
        return state;
    }

    /** The commit last deployed for {@code key}, if any. */
    public Optional<String> lastSha(String key) {
        String sha = shaByKey.get(key);
        return sha == null || sha.isBlank() ? Optional.empty() : Optional.of(sha);
    }

    /** Records {@code sha} as deployed for {@code key}. Call {@link #save()} to persist. */
    public void record(String key, String sha) {
        shaByKey.put(key, sha);
    }

    /**
     * The {@code ETag} of the last release lookup, replayed as {@code If-None-Match}.
     *
     * <h4>Why it is worth persisting</h4>
     * A conditional request answered 304 does not count against GitHub's 60-per-hour budget, so a
     * daemon that is restarted often — which is exactly what a supervisor does when something else
     * is wrong — checks for updates for free instead of spending the budget it would need to
     * actually apply one.
     *
     * @return the stored ETag, if there is one
     */
    public Optional<String> releaseEtag() {
        return Optional.ofNullable(releaseEtag);
    }

    /** Records the ETag of a release lookup whose outcome needs no further action. */
    public void recordReleaseEtag(String etag) {
        this.releaseEtag = etag == null || etag.isBlank() ? null : etag;
    }

    /**
     * The HouseGraph version this machine last installed for itself.
     *
     * <h4>What it is really for</h4>
     * Not bookkeeping — a loop stopper. The updater decides by comparing the <em>running</em> version
     * with the latest release, so if a swap appears to succeed and the next process still reports the
     * old version (a jar that did not land where the supervisor reads it from, a build with no
     * version stamped in its manifest), that comparison stays true forever and the machine would
     * download, restart, and rediscover the same update every hour.
     *
     * @return the version last applied, if any
     */
    public Optional<String> appliedVersion() {
        return Optional.ofNullable(appliedVersion);
    }

    /** Records {@code version} as the build this machine installed for itself. */
    public void recordAppliedVersion(String version) {
        this.appliedVersion = version == null || version.isBlank() ? null : version;
    }

    /** Writes the state atomically, replacing whatever was there. */
    public void save() {
        try {
            Files.createDirectories(file.getParent());
            JSONObject root = new JSONObject().put("synced", new JSONObject(shaByKey));
            if (releaseEtag != null || appliedVersion != null) {
                root.put("selfUpdate", new JSONObject()
                        .putOpt("etag", releaseEtag)
                        .putOpt("appliedVersion", appliedVersion));
            }
            Path temp = Files.createTempFile(file.getParent(), "remote-state", ".tmp");
            Files.writeString(temp, root.toString(2), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not write {}", file, e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
