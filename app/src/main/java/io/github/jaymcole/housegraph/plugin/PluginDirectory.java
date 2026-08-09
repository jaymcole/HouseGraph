package io.github.jaymcole.housegraph.plugin;

import java.util.Optional;

/**
 * A read-only lookup from a node library's id to what is known about it — the one thing a caller
 * needs when it has a library id in hand and wants to say more about it than the bare id.
 *
 * <h2>Why this exists rather than passing the catalog</h2>
 * {@link PluginCatalog} is mutable, is backed by a file, and can install/remove/enable entries. A
 * save is a pure read of "what do we know about this id", and {@code GraphFileIO} has no business
 * being able to do the rest. Narrowing it to this one method keeps the save path pure and makes it
 * trivially stubbable in a test — a lambda is a whole implementation.
 *
 * <p>{@code PluginCatalog} implements it directly; its {@code byId} already had exactly this shape.
 *
 * @see GraphDependencyCheck
 */
@FunctionalInterface
public interface PluginDirectory {

    /**
     * A directory that knows nothing, for a caller with no catalog to hand.
     *
     * <p>Not a failure case: it makes "no library metadata is available here" an explicit choice at
     * the call site rather than a null that has to be guessed at. Rows written against it degrade to
     * the bare id, which is exactly what every save wrote before libraries were recorded at all.
     */
    PluginDirectory EMPTY = id -> Optional.empty();

    /**
     * What is known about one node library.
     *
     * @param id the library id, as recorded on a node or in a save file's {@code plugins} table
     * @return the installed entry, or empty when this directory has never heard of {@code id}
     */
    Optional<PluginCatalog.Installed> byId(String id);
}
