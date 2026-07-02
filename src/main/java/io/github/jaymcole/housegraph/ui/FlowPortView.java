package io.github.jaymcole.housegraph.ui;

import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;

/**
 * Control-flow anchor point rendered as a small triangle at the top-left (IN) or
 * top-right (OUT) corner of a {@link NodeView}'s title bar. Dragging from one flow
 * port to another wires a {@link io.github.jaymcole.housegraph.graph.FlowEdge},
 * letting a node be triggered directly instead of only via data edges.
 */
public class FlowPortView extends Polygon {

    public enum Direction {
        IN,
        OUT
    }

    private static final Color FILL = Color.web("#98c379");
    private static final Color INVALID_FILL = Color.web("#e06c75");
    private static final Color BASE_STROKE = Color.web("#282c34");
    private static final Color HOVER_STROKE = Color.web("#ffffff");
    private static final double BASE_STROKE_WIDTH = 1;
    private static final double HOVER_STROKE_WIDTH = 2.5;

    private final NodeView owner;
    private final Direction direction;
    private boolean highlighted = false;
    private boolean invalid = false;

    public FlowPortView(NodeView owner, Direction direction) {
        super(0.0, 0.0, 10.0, 5.0, 0.0, 10.0);
        this.owner = owner;
        this.direction = direction;

        setFill(FILL);
        setStroke(BASE_STROKE);
        setStrokeWidth(BASE_STROKE_WIDTH);
        setCursor(Cursor.CROSSHAIR);
        setOnMouseEntered(event -> setHighlighted(true));
        setOnMouseExited(event -> setHighlighted(false));
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
            setStrokeWidth(HOVER_STROKE_WIDTH);
        } else {
            setStroke(BASE_STROKE);
            setStrokeWidth(BASE_STROKE_WIDTH);
        }
    }

    public Point2D getCenterInContent(Group content) {
        Point2D scenePoint = localToScene(5, 5);
        return content.sceneToLocal(scenePoint);
    }

    public NodeView getOwner() {
        return owner;
    }

    public Direction getDirection() {
        return direction;
    }
}
