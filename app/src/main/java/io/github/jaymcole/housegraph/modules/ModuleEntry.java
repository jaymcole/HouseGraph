package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;

import java.util.List;

/**
 * Everything a resolved module tells a consumer about itself: its identity, where it was found, the
 * face it presents, and what has to be installed for it to run.
 *
 * <h2>The path is a hint, never the identity</h2>
 * {@link #path()} is recorded so resolution can try the obvious place first and so a user can be
 * told where a module came from. It is not what a consumer stores as its reference — see
 * {@code docs/decisions/0011-modules-are-referenced-by-id.md}. A file at this path whose root
 * carries a different id is a different module, and resolution says so.
 *
 * <h2>Why the required libraries travel with the module</h2>
 * A module built from a Discord node needs that library wherever the module runs, including in a
 * consuming graph whose own canvas holds no Discord node. The consumer's {@code plugins} table is
 * derived from its own nodes and will never mention it, so the requirement is recorded at save
 * time, from here, into the consumer's {@code modules} row. That is what keeps
 * {@code GraphDependencyCheck} a pure read of one root.
 *
 * @param id              the module's stable id, from its file's root
 * @param name            its declared name, or its file name when it declares none — a label only
 * @param path            where it was found, as a hint for the next resolution; never the identity
 * @param moduleInterface the face it presents to a consumer, with any boundary conflicts attached
 * @param requiredPlugins the node libraries its own file names, ready to be copied into a consumer
 */
public record ModuleEntry(String id,
                          String name,
                          String path,
                          ModuleInterface moduleInterface,
                          List<GraphDependencyCheck.RequiredPlugin> requiredPlugins) {

    public ModuleEntry {
        requiredPlugins = List.copyOf(requiredPlugins);
    }
}
