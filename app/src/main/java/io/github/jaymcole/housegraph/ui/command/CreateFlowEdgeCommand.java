package io.github.jaymcole.housegraph.ui.command;

import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.view.FlowEdgeView;
import io.github.jaymcole.housegraph.ui.view.FlowPortView;

/**
 * Reversible creation of a flow edge by dragging between two flow ports - see {@link CreateEdgeCommand},
 * same idea for flow ports, minus the displaced-edge bookkeeping. A data input is fed by one edge, so
 * wiring a new one there replaces the old and undo has to put it back; a flow-in port takes any number
 * of edges, so this only ever adds one and undoing is just deleting it again.
 */
public class CreateFlowEdgeCommand implements Command {

    private final GraphCanvas canvas;
    private final FlowPortView a;
    private final FlowPortView b;
    private FlowEdgeView createdView;

    public CreateFlowEdgeCommand(GraphCanvas canvas, FlowPortView a, FlowPortView b) {
        this.canvas = canvas;
        this.a = a;
        this.b = b;
    }

    @Override
    public void execute() {
        createdView = canvas.createFlowEdge(a, b);
    }

    @Override
    public void undo() {
        createdView.delete();
    }
}
