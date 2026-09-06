package io.github.jaymcole.housegraph.loader;

import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.saveformat.ClipboardDataEdge;

/**
 * One data edge the loader resolved: the snapshot entry it came from, and the live
 * {@link Edge} now registered on the graph.
 * <p>
 * The pairing is what lets a host apply whatever the snapshot carries that the engine has no
 * place for — a {@code GraphCanvas} reads {@link ClipboardDataEdge#waypoints()} off the entry
 * and hangs it on the edge's view. Edges that failed to resolve are absent, so a pair is
 * always both halves.
 */
public record LoadedDataEdge(ClipboardDataEdge entry, Edge edge) {
}
