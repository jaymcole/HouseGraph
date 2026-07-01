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

    private final NodeView owner;
    private final Direction direction;

    public FlowPortView(NodeView owner, Direction direction) {
        super(0.0, 0.0, 10.0, 5.0, 0.0, 10.0);
        this.owner = owner;
        this.direction = direction;

        setFill(Color.web("#98c379"));
        setStroke(Color.web("#282c34"));
        setStrokeWidth(1);
        setCursor(Cursor.CROSSHAIR);
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
