package io.github.jaymcole.housegraph.modules;

import java.util.Optional;

/**
 * A read-only lookup from a module's stable id to what is known about it — the one thing a caller
 * with an id in hand needs to say more than the bare id.
 *
 * <h2>Why this rather than passing the library</h2>
 * The same reasoning as {@code PluginDirectory}: {@link ModuleLibrary} scans directories, reads
 * files and can publish new ones, and a save is a pure read of "what do we know about this id".
 * Narrowing it to one method keeps {@code GraphFileIO} unable to do the rest, and makes a stub a
 * lambda rather than a class.
 *
 * @see io.github.jaymcole.housegraph.plugin.PluginDirectory
 */
@FunctionalInterface
public interface ModuleDirectory {

    /**
     * A directory that knows nothing, for a caller with no library to hand.
     *
     * <p>Not a failure case. A {@code modules} row written against it degrades to what the
     * referencing node itself remembers — its id, its last-known name and path — which is exactly
     * what a graph re-saved on a machine that does not have the module file must still write back.
     */
    ModuleDirectory EMPTY = id -> Optional.empty();

    /**
     * What is known about one module.
     *
     * @param id the module id, as recorded on a node or in a save file's {@code modules} table
     * @return the resolved module, or empty when this directory cannot find it
     */
    Optional<ModuleEntry> byId(String id);
}
