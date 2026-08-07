package io.github.jaymcole.housegraph.store;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vends a single {@link JsonDocumentStore} per backing file, process-wide.
 * <p>
 * A data store is addressed by a user-chosen name that maps to a file on disk. Two nodes that
 * name the same store — or a webserver holding one while a node observes it — must operate on
 * the <em>same</em> in-memory instance, or their cached documents would diverge and their
 * writes clobber each other. Keying by the normalised absolute path (so aliasing names that
 * sanitise to the same folder still collapse to one instance) makes "same name = the same
 * store" true, turning same-name sharing into a coherent feature rather than a corruption bug.
 * <p>
 * Instances are never evicted — they're tiny, and a store's identity outlives any single node
 * (deleting and recreating a node with the same name must reopen the same data).
 */
public final class DocumentStores {

    private static final Map<Path, JsonDocumentStore> CACHE = new ConcurrentHashMap<>();

    private DocumentStores() {
    }

    /**
     * The shared store backing {@code file}, creating and loading it on first request.
     *
     * @param file the on-disk JSON file backing the store
     * @return the process-wide {@link JsonDocumentStore} for that file
     */
    public static JsonDocumentStore forFile(Path file) {
        return CACHE.computeIfAbsent(file.toAbsolutePath().normalize(), JsonDocumentStore::new);
    }
}
