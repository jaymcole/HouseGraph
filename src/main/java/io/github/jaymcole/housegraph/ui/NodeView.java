package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual representation of a {@link BaseNode}: a title bar (used to drag the node
 * around the canvas) plus a left column of input ports and a right column of
 * output ports.
 */
public class NodeView extends BorderPane {

    /** Lets the owning canvas coordinate selection and group-dragging across nodes. */
    public interface DragController {
        void onNodePressed(NodeView node);

        void onNodeDragged(double deltaContentX, double deltaContentY);
    }

    private final BaseNode node;
    private final Group content;
    private final DragController dragController;
    private final List<PortView> inputPorts = new ArrayList<>();
    private final List<PortView> outputPorts = new ArrayList<>();

    private Point2D lastDragContentPoint;
    private boolean selected = false;

    public NodeView(BaseNode node, Group content, DragController dragController) {
        this.node = node;
        this.content = content;
        this.dragController = dragController;

        setPrefWidth(190);
        applyBorderStyle();

        Label title = new Label(node.getName());
        title.setMaxWidth(Double.MAX_VALUE);
        title.setStyle("-fx-background-color: #4a4d4f; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6;");
        title.setCursor(Cursor.MOVE);
        setTop(title);

        VBox inputsBox = new VBox(8);
        inputsBox.setAlignment(Pos.TOP_LEFT);
        inputsBox.setPadding(new Insets(8, 0, 8, 0));

        VBox outputsBox = new VBox(8);
        outputsBox.setAlignment(Pos.TOP_RIGHT);
        outputsBox.setPadding(new Insets(8, 0, 8, 0));

        List<NodeVariable> inputs = node.getInputs();
        if (inputs != null) {
            for (NodeVariable<?> variable : inputs) {
                PortView port = new PortView(this, variable, PortView.Direction.INPUT);
                inputPorts.add(port);
                inputsBox.getChildren().add(port);
            }
        }

        List<NodeVariable> outputs = node.getOutputs();
        if (outputs != null) {
            for (NodeVariable<?> variable : outputs) {
                PortView port = new PortView(this, variable, PortView.Direction.OUTPUT);
                outputPorts.add(port);
                outputsBox.getChildren().add(port);
            }
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox body = new HBox(inputsBox, spacer, outputsBox);
        body.setSpacing(16);
        body.setPadding(new Insets(0, 10, 0, 10));
        setCenter(body);

        title.setOnMousePressed(this::handleDragStart);
        title.setOnMouseDragged(this::handleDragging);

        // Prevent clicks elsewhere on the node from panning/rubber-band-selecting the canvas underneath it.
        setOnMousePressed(Event::consume);
        setOnMouseDragged(Event::consume);
    }

    private void handleDragStart(MouseEvent event) {
        if (dragController != null) {
            dragController.onNodePressed(this);
        }
        lastDragContentPoint = content.sceneToLocal(event.getSceneX(), event.getSceneY());
        event.consume();
    }

    private void handleDragging(MouseEvent event) {
        Point2D now = content.sceneToLocal(event.getSceneX(), event.getSceneY());
        double deltaX = now.getX() - lastDragContentPoint.getX();
        double deltaY = now.getY() - lastDragContentPoint.getY();
        lastDragContentPoint = now;

        if (dragController != null) {
            dragController.onNodeDragged(deltaX, deltaY);
        } else {
            setLayoutX(getLayoutX() + deltaX);
            setLayoutY(getLayoutY() + deltaY);
        }
        event.consume();
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        applyBorderStyle();
    }

    public boolean isSelected() {
        return selected;
    }

    private void applyBorderStyle() {
        String borderColor = selected ? "#e5c07b" : "#555555";
        String borderWidth = selected ? "2" : "1";
        setStyle("-fx-background-color: #3c3f41; -fx-border-color: " + borderColor + "; -fx-border-width: " + borderWidth + ";");
    }

    public BaseNode getNode() {
        return node;
    }

    public List<PortView> getInputPorts() {
        return inputPorts;
    }

    public List<PortView> getOutputPorts() {
        return outputPorts;
    }
}
