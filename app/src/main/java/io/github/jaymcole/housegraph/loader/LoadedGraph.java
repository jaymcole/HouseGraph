package io.github.jaymcole.housegraph.loader;

import io.github.jaymcole.housegraph.graph.BaseNode;

import java.util.List;

/**
 * What one {@link GraphLoader} pass added to a {@code NodeGraph}.
 *
 * <h2>Why the node list keeps its holes</h2>
 * {@code nodes} is index-aligned with the snapshot's node list, and a node the factory could
 * not build holds a {@code null} slot rather than being dropped. Saved edges reference nodes by
 * array index, so compacting the list would silently misdirect every later edge. See
 * {@code docs/engine/save-format.md}.
 *
 * <h2>Why edges come back paired with their snapshot entries</h2>
 * A snapshot carries state the engine has no place for — manual edge routing, captured as
 * waypoints in view coordinates. Handing back {@link LoadedDataEdge} / {@link LoadedFlowEdge}
 * pairs lets a host apply that state to the right edge without re-resolving indices, and keeps
 * this type free of any view concept. Edges that failed to resolve are simply absent.
 */
public record LoadedGraph(List<BaseNode> nodes, List<LoadedDataEdge> dataEdges, List<LoadedFlowEdge> flowEdges) {
}
