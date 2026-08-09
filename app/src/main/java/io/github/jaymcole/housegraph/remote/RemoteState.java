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
 * <p>Atomic writes, in the same shape as {@code PluginCatalog}: a half-written state file would
 * leave the daemon unsure what it had already deployed.
 */
public final class RemoteState {

    private static final Logger log = Log.get(RemoteState.class);

    static final String FILE_NAME = "remote-state.json";

    private final Path file;
    private final Map<String, String> shaByKey = new LinkedHashMap<>();

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

    /** Writes the state atomically, replacing whatever was there. */
    public void save() {
        try {
            Files.createDirectories(file.getParent());
            JSONObject root = new JSONObject().put("synced", new JSONObject(shaByKey));
            Path temp = Files.createTempFile(file.getParent(), "remote-state", ".tmp");
            Files.writeString(temp, root.toString(2), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not write {}", file, e);
        }
    }
}
