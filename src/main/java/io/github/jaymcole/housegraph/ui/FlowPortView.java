package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.FlowPort;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.StrokeType;
import javafx.util.Duration;

/**
 * Control-flow anchor point rendered as a small triangle at the top-left (IN) or
 * top-right (OUT) corner of a {@link NodeView}'s title bar. Dragging from one flow
 * port to another wires a {@link io.github.jaymcole.housegraph.graph.FlowEdge},
 * letting a node be triggered directly instead of only via data edges.
 * <p>
 * Backed by a {@link FlowPort} on the node model, which is the stable identity a
 * wired {@link io.github.jaymcole.housegraph.graph.FlowEdge} refers to — important
 * now that a node can expose more than one out-port and the engine needs to tell
 * them apart.
 */
public class FlowPortView extends Polygon implements EdgeAnchor {

    private static final Color FILL = Color.web("#98c379");
    private static final Color INVALID_FILL = Color.web("#e06c75");
    private static final Color BASE_STROKE = Color.web("#282c34");
    private static final Color HOVER_STROKE = Color.web("#ffffff");
    private static final double STROKE_WIDTH = 1;

    // Effects are ignored by layout, so the glow can reach well past the triangle
    // without nudging the title bar the way the old thicker centered stroke did.
    // (Effect instances are plain state holders, safe to share across nodes.)
    private static final Effect HOVER_GLOW =
            new DropShadow(BlurType.GAUSSIAN, Color.web("#ffffff", 0.8), 7, 0.4, 0, 0);

    private final NodeView owner;
    private final FlowPort flowPort;
    private boolean highlighted = false;
    private boolean invalid = false;

    public FlowPortView(NodeView owner, FlowPort flowPort) {
        super(0.0, 0.0, 10.0, 5.0, 0.0, 10.0);
        this.owner = owner;
        this.flowPort = flowPort;

        setFill(FILL);
        setStroke(BASE_STROKE);
        setStrokeWidth(STROKE_WIDTH);
        // Inside stroke + constant width keeps the layout bounds pinned to the
        // 10x10 geometry; the triangle is too small for a thicker highlight ring,
        // so hover emphasis comes from the glow effect instead.
        setStrokeType(StrokeType.INSIDE);
        setCursor(Cursor.CROSSHAIR);
        setOnMouseEntered(event -> setHighlighted(true));
        setOnMouseExited(event -> setHighlighted(false));
        installTooltip();
    }

    /**
     * Flow ports carry no data type, so instead of a "type" the tooltip states the one
     * thing they signal: whether control enters ({@code IN}) or leaves ({@code OUT})
     * the node here. When the backing {@link FlowPort} is named (a node with more than
     * one out-branch), the name is included so the branches can be told apart on hover.
     */
    private void installTooltip() {
        String flow = flowPort.direction == FlowPort.Direction.IN ? "Flow in" : "Flow out";
        String text = flowPort.name == null || flowPort.name.isBlank()
                ? flow
                : flow + ": " + flowPort.name;
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(this, tooltip);
    }

    /**
     * Highlights (or un-highlights) this port's border. Driven both by normal mouse
     * hover and, since JavaFX doesn't fire hover events on nodes other than the one
     * that captured a mouse press, by {@link GraphCanvas} manually hit-testing the
     * cursor position while a flow edge drag is in progress.
     */
    public void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
        applyVisualState();
    }

    /**
     * Marks (or unmarks) this port as an invalid target for the flow edge currently
     * being dragged (wrong direction or owner) — set on every other flow port for the
     * duration of a drag by {@link GraphCanvas}, same as {@link PortView#setInvalid}.
     */
    public void setInvalid(boolean invalid) {
        this.invalid = invalid;
        applyVisualState();
    }

    private void applyVisualState() {
        setFill(invalid ? INVALID_FILL : FILL);
        if (highlighted) {
            setStroke(HOVER_STROKE);
            setEffect(HOVER_GLOW);
        } else {
            setStroke(BASE_STROKE);
            setEffect(null);
        }
    }

    public Point2D getCenterInContent(Group content) {
        Point2D scenePoint = localToScene(5, 5);
        return content.sceneToLocal(scenePoint);
    }

    public NodeView getOwner() {
        return owner;
    }

    /** The node-model port this view anchors — the identity a wired FlowEdge is built from. */
    public FlowPort getFlowPort() {
        return flowPort;
    }

    public FlowPort.Direction getDirection() {
        return flowPort.direction;
    }
}
