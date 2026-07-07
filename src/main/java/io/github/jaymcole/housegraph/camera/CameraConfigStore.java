package io.github.jaymcole.housegraph.camera;

import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads/merges/writes the camera registry — a JSON file keyed by camera MAC, each entry
 * {@code { name, model, lastKnownIp }}. Merging is non-destructive: a rediscovered camera
 * refreshes its {@code name}/{@code model}/{@code lastKnownIp} from what was observed, a
 * brand-new one is added, and anything you'd added yourself (extra fields) is left
 * untouched. Cameras with no resolved MAC are skipped (no stable key). A malformed existing
 * file is refused rather than clobbered.
 * <p>
 * This file is <em>not</em> encrypted, so it deliberately holds no credentials. A camera's
 * password is a secret: store it in the encrypted {@code SecretsStore} and feed it to a
 * camera node's Password input via a Secret Loader.
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

    /** The known cameras from the default registry, sorted by display label; see {@link #list(Path)}. */
    public static List<KnownCamera> list() {
        return list(defaultPath());
    }

    /**
     * Reads the registry into a display-friendly list. Unlike a merge, this is read-only
     * and lenient: a missing or malformed file yields an empty list (logged) rather than an
     * error, so a UI populating a dropdown from it can't be broken by a bad file.
     */
    static List<KnownCamera> list(Path file) {
        JSONObject registry = readRegistryLenient(file);
        List<KnownCamera> cameras = new ArrayList<>();
        for (String mac : registry.keySet()) {
            JSONObject entry = registry.optJSONObject(mac);
            if (entry == null) {
                continue;
            }
            cameras.add(new KnownCamera(mac,
                    entry.optString("name", ""),
                    entry.optString("model", ""),
                    entry.optString("lastKnownIp", "")));
        }
        cameras.sort(Comparator.comparing(camera -> camera.label().toLowerCase(Locale.ROOT)));
        return cameras;
    }

    /** The known camera with this MAC, or empty if none (or {@code mac} is null). */
    public static Optional<KnownCamera> find(String mac) {
        return find(mac, defaultPath());
    }

    static Optional<KnownCamera> find(String mac, Path file) {
        if (mac == null) {
            return Optional.empty();
        }
        return list(file).stream().filter(camera -> camera.mac().equals(mac)).findFirst();
    }

    /** Records a camera's current IP in the default registry; see {@link #updateIp(String, String, Path)}. */
    public static boolean updateIp(String mac, String newIp) {
        return updateIp(mac, newIp, defaultPath());
    }

    /**
     * Updates the {@code lastKnownIp} of an existing camera (e.g. after it moved on DHCP and
     * was rediscovered), leaving every other field untouched. Returns whether anything
     * changed — false if the camera is unknown or the IP already matches. Like a merge, this
     * uses the strict read that refuses to clobber a malformed file.
     */
    static boolean updateIp(String mac, String newIp, Path file) {
        if (mac == null || newIp == null || newIp.isBlank()) {
            return false;
        }
        JSONObject root = read(file);
        JSONObject registry = root.optJSONObject("cameras");
        if (registry == null || !registry.has(mac)) {
            return false;
        }
        JSONObject entry = registry.getJSONObject(mac);
        if (newIp.equals(entry.optString("lastKnownIp", null))) {
            return false;
        }
        entry.put("lastKnownIp", newIp);
        write(file, root);
        return true;
    }

    /** Reads the {@code cameras} object, tolerating a missing/malformed file (for read-only callers). */
    private static JSONObject readRegistryLenient(Path file) {
        if (!Files.isRegularFile(file)) {
            return new JSONObject();
        }
        try {
            JSONObject root = new JSONObject(new JSONTokener(Files.readString(file, StandardCharsets.UTF_8)));
            JSONObject registry = root.optJSONObject("cameras");
            return registry == null ? new JSONObject() : registry;
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read camera registry " + file + ": " + e.getMessage());
            return new JSONObject();
        }
    }

    private static JSONObject newEntry(DiscoveredCamera camera) {
        JSONObject entry = new JSONObject();
        entry.put("name", camera.name() == null ? "" : camera.name());
        entry.put("model", camera.model() == null ? "" : camera.model());
        entry.put("lastKnownIp", camera.ip());
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

    /**
     * One camera as stored in the registry, keyed by its stable {@code mac}. The
     * {@code lastKnownIp} is where it was last seen — a starting point that may be stale if
     * DHCP has since moved it (see {@link #updateIp}).
     */
    public record KnownCamera(String mac, String name, String model, String lastKnownIp) {

        /** The best human-readable label available, falling back to the model then the MAC. */
        public String label() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            if (model != null && !model.isBlank()) {
                return model;
            }
            return mac;
        }
    }
}
