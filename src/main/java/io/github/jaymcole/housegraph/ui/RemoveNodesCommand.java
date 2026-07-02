package io.github.jaymcole.housegraph.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reversible deletion of a set of nodes plus a set of connections (which may include
 * connections to a node that ISN'T being deleted, e.g. a data edge into a node outside
 * the selection). Ports are the stable identity captured here, not the connection's
 * BaseNode/Edge objects, since undo re-creates fresh Edge/EdgeView instances (an edge's
 * only observable state is which two ports it joins).
 * <p>
 * Repeatable across multiple undo/redo cycles: execute() always deletes whichever live
 * EdgeView/FlowEdgeView currently represents a captured port pair, and undo() replaces
 * that reference with the freshly-recreated one for next time.
 */
class RemoveNodesCommand implements Command {

    private static final class CapturedDataEdge {
        final PortView source;
        final PortView target;
        EdgeView view;

        CapturedDataEdge(PortView source, PortView target, EdgeView view) {
            this.source = source;
            this.target = target;
            this.view = view;
        }
    }

    private static final class CapturedFlowEdge {
        final FlowPortView source;
        final FlowPortView target;
        FlowEdgeView view;

        CapturedFlowEdge(FlowPortView source, FlowPortView target, FlowEdgeView view) {
            this.source = source;
            this.target = target;
            this.view = view;
        }
    }

    private final GraphCanvas canvas;
    private final List<NodeView> nodes;
    private final double[] x;
    private final double[] y;
    private final List<CapturedDataEdge> dataEdges = new ArrayList<>();
    private final List<CapturedFlowEdge> flowEdges = new ArrayList<>();

    /**
     * @param nodesToRemove       the nodes to delete
     * @param connectionsToRemove every connection to delete - must include any connection
     *                            touching one of {@code nodesToRemove}, plus any separately
     *                            selected connection not touching a deleted node
     */
    RemoveNodesCommand(GraphCanvas canvas, Collection<NodeView> nodesToRemove, Collection<ConnectionView> connectionsToRemove) {
        this.canvas = canvas;
        this.nodes = new ArrayList<>(nodesToRemove);
        this.x = new double[nodes.size()];
        this.y = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            x[i] = nodes.get(i).getLayoutX();
            y[i] = nodes.get(i).getLayoutY();
        }

        for (ConnectionView connection : connectionsToRemove) {
            if (connection instanceof EdgeView edgeView) {
                dataEdges.add(new CapturedDataEdge(edgeView.getSourcePort(), edgeView.getTargetPort(), edgeView));
            } else if (connection instanceof FlowEdgeView flowEdgeView) {
                flowEdges.add(new CapturedFlowEdge(flowEdgeView.getSourcePort(), flowEdgeView.getTargetPort(), flowEdgeView));
            }
        }
    }

    @Override
    public void execute() {
        // Deselect before removing: execute() can run again on redo, potentially while
        // the (just-restored-by-undo) nodes have since been manually re-selected, and a
        // removed NodeView must never linger as a stale reference in the selection.
        for (CapturedDataEdge edge : dataEdges) {
            canvas.deselectConnection(edge.view);
            edge.view.delete();
        }
        for (CapturedFlowEdge edge : flowEdges) {
            canvas.deselectConnection(edge.view);
            edge.view.delete();
        }
        for (NodeView node : nodes) {
            canvas.deselectNode(node);
            canvas.removeNode(node);
        }
    }

    @Override
    public void undo() {
        for (int i = 0; i < nodes.size(); i++) {
            canvas.addNode(nodes.get(i), x[i], y[i]);
        }
        // Freshly re-added NodeViews haven't been through a layout pass yet, so their
        // ports' on-screen positions aren't accurate until one happens - see the same
        // fix/explanation on GraphCanvas.place().
        canvas.forceLayout();
        for (CapturedDataEdge edge : dataEdges) {
            edge.view = canvas.createEdge(edge.source, edge.target);
        }
        for (CapturedFlowEdge edge : flowEdges) {
            edge.view = canvas.createFlowEdge(edge.source, edge.target);
        }
    }
}
