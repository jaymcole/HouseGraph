package io.github.jaymcole.housegraph.store;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A tiny persistent store for a <b>single JSON document</b> — the data behind a data-store
 * resource node, and the thing a hosted website reads/writes through the web server's
 * {@code /api/data} endpoint. It holds one well-formed JSON value (object or array) as
 * text, in memory, and mirrors every write to one file on disk.
 * <p>
 * Design points, mirroring the app's storage discipline:
 * <ul>
 *   <li><b>Not the graph save.</b> The document lives in its own file (a caller-supplied
 *       path, in practice under {@code AppDirectories.nodeStorage}); it is runtime user
 *       data, never written into the {@code .json} graph save.</li>
 *   <li><b>Atomic writes.</b> Each {@link #set} serializes to a sibling {@code .tmp} file
 *       then moves it into place (atomically where the filesystem supports it), so a crash
 *       mid-write can't leave a half-written, corrupt document.</li>
 *   <li><b>Thread-safe.</b> The web server answers requests on many virtual threads;
 *       {@link #get}/{@link #set} are guarded, and reads hand back an immutable String.</li>
 *   <li><b>Forgiving load.</b> A missing or unreadable/corrupt file yields the empty
 *       document {@code {}} rather than failing — a bad file never blocks startup.</li>
 * </ul>
 * {@link #addChangeListener Change listeners} fire after each successful write — multiple, so
 * every node observing a shared store (see {@link DocumentStores}) can react (e.g. refresh a
 * displayed size when the website saves).
 */
public final class JsonDocumentStore {

    private static final Logger log = Log.get(JsonDocumentStore.class);

    /** The empty document handed back before anything has been stored. */
    public static final String EMPTY_DOCUMENT = "{}";

    private final Path file;
    private final Object lock = new Object();
    private String document;
    private final List<Consumer<String>> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * Opens (or creates on first write) the store backed by {@code file}, loading any
     * existing document. A missing/corrupt file starts empty.
     *
     * @param file the on-disk JSON file backing this store
     */
    public JsonDocumentStore(Path file) {
        this.file = file;
        this.document = load(file);
    }

    /** The current document as JSON text; {@code {}} if nothing has been stored. */
    public String get() {
        synchronized (lock) {
            return document;
        }
    }

    /** The document's length in characters — a cheap size hint for UI, without copying semantics. */
    public int length() {
        synchronized (lock) {
            return document.length();
        }
    }

    /**
     * Replaces the document with {@code json} and persists it atomically.
     *
     * @param json the new document; must be a single well-formed JSON value (object or array)
     * @throws IllegalArgumentException if {@code json} is not valid JSON
     * @throws UncheckedIOException     if the document can't be written to disk
     */
    public void set(String json) {
        validate(json);
        synchronized (lock) {
            persist(json);
            this.document = json;
        }
        // Notify outside the lock so a listener can't deadlock against a concurrent read.
        for (Consumer<String> listener : changeListeners) {
            listener.accept(json);
        }
    }

    /** Registers a listener fired (outside the lock) with the new document after each successful {@link #set}. */
    public void addChangeListener(Consumer<String> listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    /** Removes a listener added via {@link #addChangeListener} (e.g. when an observing node is removed). */
    public void removeChangeListener(Consumer<String> listener) {
        changeListeners.remove(listener);
    }

    private void persist(String json) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems (e.g. across volumes) can't move atomically; fall back to a
                // plain replace. Still safe: the temp file was fully written before the move.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write data store: " + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // A leftover temp file is harmless; the next successful write replaces it.
            }
        }
    }

    private static String load(Path file) {
        if (!Files.isRegularFile(file)) {
            return EMPTY_DOCUMENT;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            validate(text);
            return text;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Data store {} is missing/corrupt, starting empty: {}", file, e.getMessage());
            return EMPTY_DOCUMENT;
        }
    }

    /** Ensures {@code json} parses as a single JSON object or array; throws otherwise. */
    private static void validate(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Document must not be null");
        }
        try {
            new JSONObject(json);
            return;
        } catch (JSONException notAnObject) {
            try {
                new JSONArray(json);
                return;
            } catch (JSONException notAnArray) {
                throw new IllegalArgumentException("Document is not valid JSON: " + notAnArray.getMessage());
            }
        }
    }
}
