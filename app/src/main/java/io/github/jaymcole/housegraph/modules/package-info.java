/**
 * Modules: an ordinary saved graph, referenced by another graph as a single node.
 * <p>
 * This package holds everything about a module's <em>shape</em> — its identity, how a reference to
 * it is resolved, and the interface its boundary markers declare. Nothing here runs a module.
 * <ul>
 *   <li>{@link io.github.jaymcole.housegraph.modules.ModuleFile} answers "is this parsed root a
 *   module, what is its id, what does it reference", purely, with no file touched.</li>
 *   <li>{@link io.github.jaymcole.housegraph.modules.ModuleInterface} derives the face a module
 *   presents — {@link io.github.jaymcole.housegraph.modules.ModulePort}s, in the consumer's
 *   orientation — from the boundary markers in {@code graph/nodes/module/}.</li>
 *   <li>{@link io.github.jaymcole.housegraph.modules.ModuleLibrary} is the only component that
 *   touches disk: it finds a module file by id under
 *   {@link io.github.jaymcole.housegraph.storage.AppDirectories#modules()}, and is what assigns an
 *   id in the first place.</li>
 *   <li>{@link io.github.jaymcole.housegraph.modules.ModuleDirectory} is the narrow read-only view
 *   of that library which the save format takes, mirroring {@code PluginDirectory}.</li>
 * </ul>
 * <p>
 * The package sits beside {@code saveformat/} and {@code loader/} rather than inside either, and is
 * headless for the same reason they are: its callers — {@code graph/nodes/module/},
 * {@code saveformat/}, {@code catalog/} and {@code cli/} — are all below the UI.
 */
package io.github.jaymcole.housegraph.modules;
