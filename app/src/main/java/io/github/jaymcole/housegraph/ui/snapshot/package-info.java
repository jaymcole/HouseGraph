/**
 * The snapshot model: a captured slice of the graph, independent of any JavaFX view.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot} bundles
 * {@link io.github.jaymcole.housegraph.ui.snapshot.ClipboardNode}s and the
 * {@link io.github.jaymcole.housegraph.ui.snapshot.ClipboardDataEdge} /
 * {@link io.github.jaymcole.housegraph.ui.snapshot.ClipboardFlowEdge} between them, each
 * referenced positionally by index. These are plain data carriers with no dependency on
 * {@code GraphCanvas}'s internals, so all three consumers build on the same shape:
 * {@code GraphCanvas} (copy/paste), {@link io.github.jaymcole.housegraph.ui.command} (the
 * paste command), and {@link io.github.jaymcole.housegraph.ui.io} (save/load).
 * See {@code docs/architecture/ui.md}.
 */
package io.github.jaymcole.housegraph.ui.snapshot;
