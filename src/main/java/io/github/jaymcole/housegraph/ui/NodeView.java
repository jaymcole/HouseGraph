package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.annotations.Executable;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
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
 * around the canvas, and hosting the flow-in/flow-out anchors at its corners) plus a
 * left column of input ports and a right column of output ports.
 * <p>
 * The flow-in anchor only appears if the node's class is annotated
 * {@link Executable.ExecutableIn}, and the flow-out anchor only if annotated
 * {@link Executable.ExecutableOut}; a node with neither gets no flow anchors at all.
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

    /** Null if the node's class isn't annotated for that direction; see the class Javadoc. */
    private final FlowPortView flowInPort;
    private final FlowPortView flowOutPort;

    private Point2D lastDragContentPoint;
    private boolean selected = false;

    public NodeView(BaseNode node, Group content, DragController dragController) {
        this.node = node;
        this.content = content;
        this.dragController = dragController;

        // No fixed/minimum width: the node sizes to its actual content (title + ports).
        // A fixed width would leave slack whenever content is narrower than it, and
        // that slack has to render as a gap somewhere - between columns, or trailing
        // after the last one - neither of which is wanted.
        applyBorderStyle();

        Class<?> nodeClass = node.getClass();
        flowInPort = nodeClass.isAnnotationPresent(Executable.ExecutableIn.class)
                ? new FlowPortView(this, FlowPortView.Direction.IN) : null;
        flowOutPort = nodeClass.isAnnotationPresent(Executable.ExecutableOut.class)
                ? new FlowPortView(this, FlowPortView.Direction.OUT) : null;

        Label title = new Label(node.getName());
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);
        title.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        HBox.setHgrow(title, Priority.ALWAYS);

        HBox titleBar = new HBox(6);
        if (flowInPort != null) {
            titleBar.getChildren().add(flowInPort);
        }
        titleBar.getChildren().add(title);
        if (flowOutPort != null) {
            titleBar.getChildren().add(flowOutPort);
        }
        titleBar.setAlignment(Pos.CENTER);
        titleBar.setPadding(new Insets(6, 8, 6, 8));
        titleBar.setStyle("-fx-background-color: #4a4d4f;");
        titleBar.setCursor(Cursor.MOVE);
        setTop(titleBar);

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

        boolean hasInputs = !inputPorts.isEmpty();
        boolean hasOutputs = !outputPorts.isEmpty();

        Region body;
        if (hasInputs && !hasOutputs) {
            // No outputs to share the row with: let the input column use the full width.
            inputsBox.setPadding(new Insets(8, 10, 8, 10));
            body = inputsBox;
        } else if (hasOutputs && !hasInputs) {
            outputsBox.setPadding(new Insets(8, 10, 8, 10));
            body = outputsBox;
        } else {
            // A small fixed gap between the columns (matching PortView's own internal
            // circle/label/field spacing) rather than a greedy spacer Region. Any extra
            // width the row ends up with (e.g. the title bar needing more room than the
            // ports do) is instead given to the columns themselves via hgrow, so it
            // flows down into each PortView's own growable value field instead of
            // sitting as dead space - see PortView.createValueField().
            inputsBox.setMaxWidth(Double.MAX_VALUE);
            outputsBox.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(inputsBox, Priority.ALWAYS);
            HBox.setHgrow(outputsBox, Priority.ALWAYS);

            HBox row = new HBox(6, inputsBox, outputsBox);
            row.setPadding(new Insets(0, 10, 0, 10));
            body = row;
        }
        setCenter(body);

        if (node instanceof NodeContentProvider contentProvider) {
            Node customContent = contentProvider.createNodeContent();
            if (customContent instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
            BorderPane.setMargin(customContent, new Insets(0, 10, 10, 10));
            setBottom(customContent);
        }

        titleBar.setOnMousePressed(this::handleDragStart);
        titleBar.setOnMouseDragged(this::handleDragging);

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

    public FlowPortView getFlowInPort() {
        return flowInPort;
    }

    public FlowPortView getFlowOutPort() {
        return flowOutPort;
    }
}
