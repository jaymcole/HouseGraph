package io.github.jaymcole.housegraph.ui.view;

import javafx.geometry.Point2D;

import java.util.List;

/**
 * How an {@link AbstractEdgeView} reports user interactions back to the canvas, which
 * owns selection and the undo history — so the edge view itself needs no direct
 * dependency on {@link GraphCanvas}.
 */
public interface EdgeInteractionListener {

    /** A single click on the edge: select just this one, replacing the current selection. */
    void selectEdge(AbstractEdgeView edge);

    /**
     * The edge's routing waypoints changed (one was added, dragged, or removed). The
     * change has already been applied to the edge live; this is the canvas's cue to
     * record it as a single undoable step. {@code before}/{@code after} are the full
     * waypoint lists either side of the change.
     */
    void waypointsChanged(AbstractEdgeView edge, List<Point2D> before, List<Point2D> after);
}
