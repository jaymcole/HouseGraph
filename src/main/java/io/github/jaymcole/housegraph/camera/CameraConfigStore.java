package io.github.jaymcole.housegraph.camera;

import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads/merges/writes the camera registry — a JSON file keyed by camera MAC, each entry
 * {@code { name, model, lastKnownIp, password }}. Merging is non-destructive: a
 * rediscovered camera refreshes its {@code name}/{@code model}/{@code lastKnownIp} from
 * what was observed, a brand-new one is added with a blank password, and anything you'd
 * added yourself (the password, extra fields) is left untouched. Cameras with no resolved
 * MAC are skipped (no stable key). A malformed existing file is refused rather than
 * clobbered, so live passwords can't be lost.
 */
public final class CameraConfigStore {

    private static final String FILE = "cameras.json";

    private CameraConfigStore() {
    }

    /** The default registry location under {@link AppDirectories#config()}. */
    public static Path defaultPath() {
        return AppDirectories.get().config().resolve(FILE);
    }

    /** Merges discovered cameras into the default registry; see {@link #merge(List, Path)}. */
    public static MergeResult merge(List<DiscoveredCamera> cameras) {
        return merge(cameras, defaultPath());
    }

    /** Package-visible: merge into an explicit file (for tests). */
    static MergeResult merge(List<DiscoveredCamera> cameras, Path file) {
        JSONObject root = read(file);
        JSONObject registry;
        if (root.has("cameras")) {
            registry = root.optJSONObject("cameras");
            if (registry == null) {
                throw new IllegalStateException("Refusing to write: 'cameras' in " + file + " is not a JSON object.");
            }
        } else {
            registry = new JSONObject();
            root.put("cameras", registry);
        }

        int added = 0;
        int updated = 0;
        int skipped = 0;
        for (DiscoveredCamera camera : cameras) {
            if (camera.mac() == null) {
                skipped++;
                continue;
            }
            if (registry.has(camera.mac())) {
                if (updateEntry(registry.getJSONObject(camera.mac()), camera)) {
                    updated++;
                }
            } else {
                registry.put(camera.mac(), newEntry(camera));
                added++;
            }
        }

        write(file, root);
        return new MergeResult(added, updated, skipped);
    }

    private static JSONObject newEntry(DiscoveredCamera camera) {
        JSONObject entry = new JSONObject();
        entry.put("name", camera.name() == null ? "" : camera.name());
        entry.put("model", camera.model() == null ? "" : camera.model());
        entry.put("lastKnownIp", camera.ip());
        entry.put("password", "");
        return entry;
    }

    /** Refreshes the camera-authoritative fields, preserving everything else; returns whether anything changed. */
    private static boolean updateEntry(JSONObject entry, DiscoveredCamera camera) {
        boolean changed = false;
        changed |= setIfObserved(entry, "name", camera.name());
        changed |= setIfObserved(entry, "model", camera.model());
        changed |= setIfObserved(entry, "lastKnownIp", camera.ip());
        return changed;
    }

    private static boolean setIfObserved(JSONObject entry, String key, String value) {
        if (value == null || value.isBlank() || value.equals(entry.optString(key, null))) {
            return false;
        }
        entry.put(key, value);
        return true;
    }

    private static JSONObject read(Path file) {
        if (!Files.isRegularFile(file)) {
            return new JSONObject();
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read camera config: " + file, e);
        }
        // A JSONException here (malformed file) propagates on purpose - refuse rather than clobber.
        return new JSONObject(new JSONTokener(text));
    }

    private static void write(Path file, JSONObject root) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(2) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write camera config: " + file, e);
        }
    }

    /** Outcome of a merge: how many cameras were newly added, refreshed, or skipped (no MAC). */
    public record MergeResult(int added, int updated, int skipped) {
    }
}
