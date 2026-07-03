package io.github.jaymcole.housegraph.ui;

import javafx.scene.Group;
import javafx.scene.paint.Color;

/**
 * Visual curve connecting an output {@link PortView} to an input {@link PortView}.
 * All the drawing, selection, and re-routing lives in {@link AbstractEdgeView}; this
 * subclass only pins down the data-edge colour and the concrete port types.
 */
public class EdgeView extends AbstractEdgeView {

    private static final Color DATA_STROKE = Color.web("#61afef");

    private final PortView sourcePort;
    private final PortView targetPort;

    public EdgeView(PortView source, PortView target, Group content, EdgeInteractionListener listener, Runnable onDelete) {
        super(source, target, content, listener, onDelete);
        this.sourcePort = source;
        this.targetPort = target;
    }

    @Override
    protected Color baseStroke() {
        return DATA_STROKE;
    }

    public boolean hasTarget(PortView port) {
        return targetPort == port;
    }

    public PortView getSourcePort() {
        return sourcePort;
    }

    public PortView getTargetPort() {
        return targetPort;
    }
}
