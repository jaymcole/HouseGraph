package io.github.jaymcole.housegraph.ui.command;

import io.github.jaymcole.housegraph.ui.view.AbstractEdgeView;

import javafx.geometry.Point2D;

import java.util.List;

/**
 * Reversible change to a single edge's routing waypoints — a waypoint added, dragged,
 * or removed. The edit is already applied live as the user interacts, so this is
 * {@link UndoManager#record}ed (not executed); it just holds the before/after lists so
 * the step can be undone and redone.
 */
public class SetWaypointsCommand implements Command {

    private final AbstractEdgeView edge;
    private final List<Point2D> before;
    private final List<Point2D> after;

    public SetWaypointsCommand(AbstractEdgeView edge, List<Point2D> before, List<Point2D> after) {
        this.edge = edge;
        // Point2D is immutable, so copies are enough to freeze each side of the change.
        this.before = List.copyOf(before);
        this.after = List.copyOf(after);
    }

    @Override
    public void execute() {
        edge.setWaypoints(after);
    }

    @Override
    public void undo() {
        edge.setWaypoints(before);
    }
}
