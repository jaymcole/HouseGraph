package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeType;

/**
 * Visual connection point for a single {@link NodeVariable} on a {@link NodeView}.
 * Edges are created by dragging from one port's circle to another. When a variable is
 * marked {@code manuallyEditable} and its type is registered in {@link ValueEditors},
 * an inline field lets the user type a value directly onto it instead of/alongside
 * wiring an edge.
 */
public class PortView extends HBox implements EdgeAnchor {

    public enum Direction {
        INPUT,
        OUTPUT
    }

    private static final double RADIUS = 6;
    private static final Color FILL = Color.web("#61afef");
    private static final Color INVALID_FILL = Color.web("#e06c75");
    private static final Color BASE_STROKE = Color.web("#282c34");
    private static final Color HOVER_STROKE = Color.web("#ffffff");
    private static final double BASE_STROKE_WIDTH = 1.5;
    private static final double HOVER_STROKE_WIDTH = 3;

    private final NodeView owner;
    private final NodeVariable<?> variable;
    private final Direction direction;
    private final Circle circle = new Circle(RADIUS);
    private final Label label;
    private final TextField valueField;

    private int connectionCount = 0;
    private boolean highlighted = false;
    private boolean invalid = false;
    private boolean editing = false;

    public PortView(NodeView owner, NodeVariable<?> variable, Direction direction) {
        this.owner = owner;
        this.variable = variable;
        this.direction = direction;

        circle.setFill(FILL);
        circle.setStroke(BASE_STROKE);
        circle.setStrokeWidth(BASE_STROKE_WIDTH);
        // The default CENTERED stroke bleeds outward and counts toward layout bounds,
        // so the thicker hover stroke used to nudge this whole row sideways. An INSIDE
        // stroke keeps the bounds pinned to the geometry no matter how wide it gets;
        // the hover ring now grows into the fill instead of outward.
        circle.setStrokeType(StrokeType.INSIDE);
        circle.setCursor(Cursor.CROSSHAIR);
        circle.setOnMouseEntered(event -> setHighlighted(true));
        circle.setOnMouseExited(event -> setHighlighted(false));
        installTypeTooltip();

        label = new Label(variable.name);
        label.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 11px;");

        valueField = isEditable(variable) ? createValueField() : null;

        // For editable ports the label doubles as a click target that swaps itself out
        // for the inline field, so hint that it's interactive.
        if (valueField != null) {
            label.setCursor(Cursor.TEXT);
            label.setOnMouseClicked(event -> startEditing());
        }

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
        return variable.manuallyEditable && ValueEditors.isEditable(variable.type);
    }

    private TextField createValueField() {
        TextField field = new TextField();
        field.setPrefWidth(50);
        field.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setPromptText("0.0");
        field.setStyle("-fx-font-size: 10px; -fx-padding: 1 3 1 3;");

        Object currentValue = variable.getValue();
        if (currentValue != null) {
            field.setText(formatValue(currentValue));
        }

        field.setOnAction(event -> commitValue());
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                editing = false;
                commitValue();
                // An empty field collapses back to just the label; a field with a value
                // stays visible so the value isn't hidden away behind a label click.
                updateFieldVisibility();
            }
        });
        return field;
    }

    /**
     * Reveals the inline field (if this port has one and can currently show it) in place
     * of the label and moves the caret into it. Triggered by clicking the label.
     */
    private void startEditing() {
        if (valueField == null || !fieldAllowed()) {
            return;
        }
        editing = true;
        updateFieldVisibility();
        valueField.requestFocus();
    }

    @SuppressWarnings("unchecked")
    private void commitValue() {
        String text = valueField.getText();
        if (text == null || text.isBlank()) {
            ((NodeVariable<Object>) variable).setValue(null);
            return;
        }
        try {
            Object parsed = ValueEditors.editorFor(variable.type).parse(text);
            ((NodeVariable<Object>) variable).setValue(parsed);
        } catch (RuntimeException e) {
            Object currentValue = variable.getValue();
            valueField.setText(currentValue == null ? "" : formatValue(currentValue));
        }
    }

    @SuppressWarnings("unchecked")
    private String formatValue(Object value) {
        ValueEditors.Editor<Object> editor = (ValueEditors.Editor<Object>) ValueEditors.editorFor(variable.type);
        return editor != null ? editor.format(value) : String.valueOf(value);
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

    /**
     * Whether this port is allowed to show its inline field at all. Outputs still make
     * sense to edit manually even when wired (e.g. overriding a constant that also feeds
     * an edge); only inputs hide the field once connected, since a connected input's
     * value comes from upstream instead.
     */
    private boolean fieldAllowed() {
        return direction == Direction.OUTPUT || connectionCount == 0;
    }

    private void updateFieldVisibility() {
        if (valueField == null) {
            return;
        }
        // The field only replaces the label while it's being edited or already holds a
        // value; otherwise the port shows just its name label until the user clicks it.
        boolean hasValue = !valueField.getText().isBlank();
        boolean showField = fieldAllowed() && (editing || hasValue);
        valueField.setVisible(showField);
        valueField.setManaged(showField);

        // The name label and the value field compete for the same cramped row, so only
        // one is ever shown at a time.
        label.setVisible(!showField);
        label.setManaged(!showField);
    }

    /**
     * Highlights (or un-highlights) this port's border. Driven both by normal mouse
     * hover and, since JavaFX doesn't fire hover events on nodes other than the one
     * that captured a mouse press, by {@link GraphCanvas} manually hit-testing the
     * cursor position while an edge drag is in progress.
     */
    public void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
        applyVisualState();
    }

    /**
     * Marks (or unmarks) this port as an invalid target for the edge currently being
     * dragged (wrong type, direction, or owner) — set on every other port for the
     * duration of a drag by {@link GraphCanvas}, so the whole set of valid/invalid
     * targets is visible at a glance rather than only on hover. Takes priority over
     * the hover highlight, since an invalid port can't become a valid one just by
     * being under the cursor.
     */
    public void setInvalid(boolean invalid) {
        this.invalid = invalid;
        applyVisualState();
    }

    /**
     * Shows the port's data type on hover over the anchor circle: what an OUTPUT port
     * produces or what an INPUT port expects to be fed. Type names are the plain
     * {@link Class#getSimpleName() simple name} (e.g. {@code String}, {@code Float}),
     * which is what matters when deciding whether two ports can be wired together.
     */
    private void installTypeTooltip() {
        String verb = direction == Direction.OUTPUT ? "Outputs" : "Expects";
        Tooltip tooltip = new Tooltip(verb + ": " + variable.type.getSimpleName());
        tooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(circle, tooltip);
    }

    private void applyVisualState() {
        circle.setFill(invalid ? INVALID_FILL : FILL);
        if (highlighted) {
            circle.setStroke(HOVER_STROKE);
            circle.setStrokeWidth(HOVER_STROKE_WIDTH);
        } else {
            circle.setStroke(BASE_STROKE);
            circle.setStrokeWidth(BASE_STROKE_WIDTH);
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
