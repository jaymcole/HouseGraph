package io.github.jaymcole.housegraph.ui.command;

import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.view.NodeView;

/** Reversible add of a single freshly-created node at a given canvas position (e.g. from the "Add Node" menu). */
public class AddNodeCommand implements Command {

    private final GraphCanvas canvas;
    private final NodeView nodeView;
    private final double x;
    private final double y;

    public AddNodeCommand(GraphCanvas canvas, NodeView nodeView, double x, double y) {
        this.canvas = canvas;
        this.nodeView = nodeView;
        this.x = x;
        this.y = y;
    }

    @Override
    public void execute() {
        canvas.addNode(nodeView, x, y);
    }

    @Override
    public void undo() {
        // The node could have been selected since it was added, so make sure it
        // doesn't linger as a stale reference in the selection once it's removed.
        canvas.deselectNode(nodeView);
        canvas.removeNode(nodeView);
    }
}
