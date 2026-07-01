package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Visual connection point for a single {@link NodeVariable} on a {@link NodeView}.
 * Edges are created by dragging from one port's circle to another. When a variable is
 * marked {@code manuallyEditable} (and of a supported literal type), an inline field
 * lets the user type a value directly onto it instead of/alongside wiring an edge.
 */
public class PortView extends HBox {

    public enum Direction {
        INPUT,
        OUTPUT
    }

    private static final double RADIUS = 6;
    private static final Color FILL = Color.web("#61afef");
    private static final Color BASE_STROKE = Color.web("#282c34");
    private static final Color HOVER_STROKE = Color.web("#ffffff");
    private static final double BASE_STROKE_WIDTH = 1.5;
    private static final double HOVER_STROKE_WIDTH = 3;

    private final NodeView owner;
    private final NodeVariable<?> variable;
    private final Direction direction;
    private final Circle circle = new Circle(RADIUS);
    private final TextField valueField;

    private int connectionCount = 0;

    public PortView(NodeView owner, NodeVariable<?> variable, Direction direction) {
        this.owner = owner;
        this.variable = variable;
        this.direction = direction;

        circle.setFill(FILL);
        circle.setStroke(BASE_STROKE);
        circle.setStrokeWidth(BASE_STROKE_WIDTH);
        circle.setCursor(Cursor.CROSSHAIR);
        circle.setOnMouseEntered(event -> setHighlighted(true));
        circle.setOnMouseExited(event -> setHighlighted(false));

        Label label = new Label(variable.name);
        label.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 11px;");

        valueField = isEditable(variable) ? createValueField() : null;

        setSpacing(6);
        if (direction == Direction.INPUT) {
            setAlignment(Pos.CENTER_LEFT);
            getChildren().addAll(circle, label);
            if (valueField != null) {
                getChildren().add(valueField);
            }
        } else {
            setAlignment(Pos.CENTER_RIGHT);
            if (valueField != null) {
                getChildren().add(valueField);
            }
            getChildren().addAll(label, circle);
        }

        updateFieldVisibility();
    }

    private static boolean isEditable(NodeVariable<?> variable) {
        return variable.manuallyEditable && variable.type == Float.class;
    }

    private TextField createValueField() {
        TextField field = new TextField();
        field.setPrefWidth(50);
        field.setPromptText("0.0");
        field.setStyle("-fx-font-size: 10px; -fx-padding: 1 3 1 3;");

        Object currentValue = variable.getValue();
        if (currentValue != null) {
            field.setText(String.valueOf(currentValue));
        }

        field.setOnAction(event -> commitValue());
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitValue();
            }
        });
        return field;
    }

    @SuppressWarnings("unchecked")
    private void commitValue() {
        String text = valueField.getText();
        if (text == null || text.isBlank()) {
            ((NodeVariable<Object>) variable).setValue(null);
            return;
        }
        try {
            Float parsed = Float.parseFloat(text);
            ((NodeVariable<Object>) variable).setValue(parsed);
        } catch (NumberFormatException e) {
            Object currentValue = variable.getValue();
            valueField.setText(currentValue == null ? "" : String.valueOf(currentValue));
        }
    }

    /** Marks this port as connected to one more edge, hiding the manual-entry field. */
    public void connect() {
        connectionCount++;
        updateFieldVisibility();
    }

    /** Marks this port as disconnected from one edge; re-shows the manual-entry field once none remain. */
    public void disconnect() {
        connectionCount = Math.max(0, connectionCount - 1);
        updateFieldVisibility();
    }

    private void updateFieldVisibility() {
        if (valueField == null) {
            return;
        }
        // Outputs still make sense to edit manually even when wired (e.g. overriding a
        // constant that also feeds an edge); only inputs hide the field once connected,
        // since a connected input's value comes from upstream instead.
        boolean showField = direction == Direction.OUTPUT || connectionCount == 0;
        valueField.setVisible(showField);
        valueField.setManaged(showField);
    }

    /**
     * Highlights (or un-highlights) this port's border. Driven both by normal mouse
     * hover and, since JavaFX doesn't fire hover events on nodes other than the one
     * that captured a mouse press, by {@link GraphCanvas} manually hit-testing the
     * cursor position while an edge drag is in progress.
     */
    public void setHighlighted(boolean highlighted) {
        circle.setStroke(highlighted ? HOVER_STROKE : BASE_STROKE);
        circle.setStrokeWidth(highlighted ? HOVER_STROKE_WIDTH : BASE_STROKE_WIDTH);
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
