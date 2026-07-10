package io.github.jaymcole.housegraph.ui.snapshot;

import javafx.geometry.Point2D;

import java.util.List;

/**
 * A flow edge between two snapshotted nodes, referenced by index into the node list,
 * plus which flow port on each (index into the node's flow-out / flow-in list) so a
 * multi-branch node's edges reconnect to the right ports, and its routing waypoints.
 */
public record ClipboardFlowEdge(int sourceNodeIndex, int sourcePortIndex, int targetNodeIndex, int targetPortIndex,
                                List<Point2D> waypoints) {
}
