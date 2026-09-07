package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import org.json.JSONObject;

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
 * <h2>Two halves, one injection point</h2>
 * {@link #byId} answers what a module <em>looks like</em> — enough to give a consumer its ports and
 * to write a save file's {@code modules} row. {@link #rootOf} and {@link #nodeRegistry()} answer
 * what it takes to <em>run</em> one: the module's parsed graph, and the registry that resolves the
 * node types in it. They are defaults returning nothing, so a stub is still a lambda and
 * {@code GraphFileIO}, which only ever saves, is unaffected.
 * <p>
 * They sit here rather than on a second interface because a consumer resolves a module exactly once
 * — {@code ModuleNode.bindTo} — and standing the same module up from a <em>different</em> source
 * than the one that described it is not a case worth being able to express. One binding, one
 * answer to both questions.
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

    /**
     * The parsed save file behind one module id — what a consumer needs to build the module's nodes
     * and run them, as opposed to merely describing them.
     *
     * <p>Returns the root as parsed, not a copy: a caller that means to modify it must copy first.
     * A directory that cannot supply one answers null, and a consumer bound to it can read the
     * module's shape but not run it.
     *
     * @param id the module's stable id
     * @return its parsed root, or null when this directory has none
     */
    default JSONObject rootOf(String id) {
        return null;
    }

    /**
     * The registry that resolves node types inside a module — <b>the host's own</b>, so a module
     * built from a plugin's nodes finds them. A fresh registry would resolve only the core nodes
     * and load the rest as placeholders that do nothing.
     *
     * @return the registry, or null when this directory has none
     */
    default NodeRegistry nodeRegistry() {
        return null;
    }
}
