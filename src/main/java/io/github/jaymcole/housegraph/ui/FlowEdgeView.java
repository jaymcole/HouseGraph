package io.github.jaymcole.housegraph.ui;

import javafx.scene.Group;
import javafx.scene.paint.Color;

/**
 * Visual curve connecting an OUT {@link FlowPortView} to an IN {@link FlowPortView},
 * styled green to keep control flow visually distinct from data {@link EdgeView}s.
 * All the drawing, selection, and re-routing lives in {@link AbstractEdgeView}; this
 * subclass only pins down the flow-edge colour and the concrete port types.
 */
public class FlowEdgeView extends AbstractEdgeView {

    private static final Color FLOW_STROKE = Color.web("#98c379");

    private final FlowPortView sourcePort;
    private final FlowPortView targetPort;

    public FlowEdgeView(FlowPortView source, FlowPortView target, Group content, EdgeInteractionListener listener, Runnable onDelete) {
        super(source, target, content, listener, onDelete);
        this.sourcePort = source;
        this.targetPort = target;
    }

    @Override
    protected Color baseStroke() {
        return FLOW_STROKE;
    }

    public boolean hasTarget(FlowPortView port) {
        return targetPort == port;
    }

    public FlowPortView getSourcePort() {
        return sourcePort;
    }

    public FlowPortView getTargetPort() {
        return targetPort;
    }
}
