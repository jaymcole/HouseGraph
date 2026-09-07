/**
 * Modules: an ordinary saved graph, referenced by another graph as a single node.
 * <p>
 * This package holds everything about a module's <em>shape</em> — its identity, how a reference to
 * it is resolved, and the interface its boundary markers declare — and, in
 * {@link io.github.jaymcole.housegraph.modules.ModuleInstance}, one standing up and runnable.
 * <ul>
 *   <li>{@link io.github.jaymcole.housegraph.modules.ModuleFile} answers "is this parsed root a
 *   module, what is its id, what does it reference", purely, with no file touched.</li>
 *   <li>{@link io.github.jaymcole.housegraph.modules.ModuleInterface} derives the face a module
 *   presents — {@link io.github.jaymcole.housegraph.modules.ModulePort}s, in the consumer's
 *   orientation — from the boundary markers in {@code graph/nodes/module/}.</li>
 *   <li>{@link io.github.jaymcole.housegraph.modules.ModuleInstance} is one module built onto a
 *   {@code NodeGraph} of its own, with its boundary markers indexed by the names its face is made
 *   of. It is what {@code ModuleNode} drives, one run per invocation; see
 *   {@code docs/decisions/0012-a-module-runs-as-a-nested-graph.md}.</li>
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
 * {@code saveformat/}, {@code catalog/} and {@code cli/} — are all below the UI. It depends on
 * {@code loader/} rather than the reverse, because standing a module up is one more caller of
 * "a snapshot onto a graph" and not a new way of doing it.
 */
package io.github.jaymcole.housegraph.modules;
