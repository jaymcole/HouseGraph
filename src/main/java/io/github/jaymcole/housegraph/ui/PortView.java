package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Visual connection point for a single {@link NodeVariable} on a {@link NodeView}.
 * Edges are created by dragging from one port's circle to another.
 */
public class PortView extends HBox {

    public enum Direction {
        INPUT,
        OUTPUT
    }

    private final NodeView owner;
    private final NodeVariable<?> variable;
    private final Direction direction;
    private final Circle circle = new Circle(6);

    public PortView(NodeView owner, NodeVariable<?> variable, Direction direction) {
        this.owner = owner;
        this.variable = variable;
        this.direction = direction;

        circle.setFill(Color.web("#61afef"));
        circle.setStroke(Color.web("#282c34"));
        circle.setStrokeWidth(1.5);
        circle.setCursor(Cursor.CROSSHAIR);

        Label label = new Label(variable.name);
        label.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 11px;");

        setSpacing(6);
        if (direction == Direction.INPUT) {
            setAlignment(Pos.CENTER_LEFT);
            getChildren().addAll(circle, label);
        } else {
            setAlignment(Pos.CENTER_RIGHT);
            getChildren().addAll(label, circle);
        }
    }

    public Point2D getCenterInContent(Group content) {
        Point2D scenePoint = circle.localToScene(circle.getCenterX(), circle.getCenterY());
        return content.sceneToLocal(scenePoint);
    }

    public NodeView getOwner() {
        return owner;
    }

    public NodeVariable<?> getVariable() {
        return variable;
    }

    public Direction getDirection() {
        return direction;
    }

    public Circle getCircle() {
        return circle;
    }
}
