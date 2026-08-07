package io.github.jaymcole.housegraph.ui.snapshot;

import javafx.geometry.Point2D;

import java.util.List;

/**
 * A data edge between two snapshotted nodes, referenced by index into the node list
 * and variable list, plus its manual routing {@code waypoints} (content coordinates,
 * empty for a straight edge) so re-routing survives copy/paste and save/load.
 */
public record ClipboardDataEdge(int sourceNodeIndex, int sourceVariableIndex, int targetNodeIndex, int targetVariableIndex,
                                List<Point2D> waypoints) {
}
