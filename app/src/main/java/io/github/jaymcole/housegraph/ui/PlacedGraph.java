package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.ui.view.GroupView;
import io.github.jaymcole.housegraph.ui.view.NodeView;

import java.util.List;

/**
 * What {@link GraphCanvas#place} drew: the node views, and the group frames behind them.
 *
 * <p>A standalone record rather than a nested type because {@code ui.command}'s paste command reads
 * it, and undoing a paste has to take the frames away as well as the nodes. Holds only real views —
 * a snapshot slot the loader could not build has no view and is not listed here.
 */
public record PlacedGraph(List<NodeView> nodes, List<GroupView> groups) {
}
