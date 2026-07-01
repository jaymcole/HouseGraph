package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.Graph;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Affine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An infinite, pannable, zoomable canvas that hosts {@link NodeView}s and the
 * {@link EdgeView}s connecting them.
 * <p>
 * Panning: right-click-drag on empty canvas space. Zooming: mouse scroll, anchored to
 * the cursor. Left-click-drag on empty canvas space rubber-band-selects nodes/edges;
 * Delete/Backspace removes the current selection. Edges are created by dragging from
 * one port's circle to another.
 */
public class GraphCanvas extends Pane implements NodeView.DragController {

    private final Group content = new Group();
    private final List<PortView> ports = new ArrayList<>();
    private final List<NodeView> nodeViews = new ArrayList<>();
    private final Map<Edge, EdgeView> edgeViews = new HashMap<>();

    private final Set<NodeView> selectedNodes = new LinkedHashSet<>();
    private final Set<EdgeView> selectedEdges = new LinkedHashSet<>();

    private double zoom = 1.0;
    private double translateX = 0;
    private double translateY = 0;
    private double lastDragSceneX;
    private double lastDragSceneY;
    private int nodePlacementCounter = 0;

    private PortView dragSourcePort;
    private CubicCurve dragLine;

    private Rectangle selectionRectangle;
    private Point2D selectionStartContent;

    public GraphCanvas() {
        setStyle("-fx-background-color: #1e1e1e;");
        getChildren().add(content);
        setFocusTraversable(true);

        // Without a clip, nodes dragged near the edge render outside this Pane's own
        // bounds and can paint over sibling UI (e.g. a toolbar placed above the canvas).
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        setOnScroll(this::handleZoom);
        setOnMousePressed(this::handleCanvasPressed);
        setOnMouseDragged(this::handleCanvasDragged);
        setOnMouseReleased(this::handleCanvasReleased);
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                deleteSelected();
                event.consume();
            }
        });

        updateTransform();
    }

    public Group getContent() {
        return content;
    }

    public void addNode(NodeView nodeView) {
        nodeView.setLayoutX(40 + nodePlacementCounter * 30);
        nodeView.setLayoutY(40 + nodePlacementCounter * 30);
        nodePlacementCounter++;

        content.getChildren().add(nodeView);
        nodeViews.add(nodeView);
        ports.addAll(nodeView.getInputPorts());
        ports.addAll(nodeView.getOutputPorts());

        for (PortView port : nodeView.getInputPorts()) {
            wirePort(port);
        }
        for (PortView port : nodeView.getOutputPorts()) {
            wirePort(port);
        }
    }

    private void removeNode(NodeView nodeView) {
        content.getChildren().remove(nodeView);
        nodeViews.remove(nodeView);
        ports.removeAll(nodeView.getInputPorts());
        ports.removeAll(nodeView.getOutputPorts());
    }

    private void wirePort(PortView port) {
        port.getCircle().setOnMousePressed(event -> {
            dragSourcePort = port;
            dragLine = new CubicCurve();
            dragLine.setFill(null);
            dragLine.setStroke(Color.web("#61afef"));
            dragLine.setStrokeWidth(2);
            dragLine.setMouseTransparent(true);
            content.getChildren().add(dragLine);

            Point2D start = port.getCenterInContent(content);
            dragLine.setStartX(start.getX());
            dragLine.setStartY(start.getY());
            dragLine.setEndX(start.getX());
            dragLine.setEndY(start.getY());
            dragLine.setControlX1(start.getX());
            dragLine.setControlY1(start.getY());
            dragLine.setControlX2(start.getX());
            dragLine.setControlY2(start.getY());
            event.consume();
        });

        port.getCircle().setOnMouseDragged(event -> {
            if (dragLine == null) {
                return;
            }
            Point2D start = dragSourcePort.getCenterInContent(content);
            Point2D end = content.sceneToLocal(event.getSceneX(), event.getSceneY());
            dragLine.setStartX(start.getX());
            dragLine.setStartY(start.getY());
            dragLine.setEndX(end.getX());
            dragLine.setEndY(end.getY());
            double controlOffset = Math.max(50, Math.abs(end.getX() - start.getX()) / 2);
            dragLine.setControlX1(start.getX() + controlOffset);
            dragLine.setControlY1(start.getY());
            dragLine.setControlX2(end.getX() - controlOffset);
            dragLine.setControlY2(end.getY());
            event.consume();
        });

        port.getCircle().setOnMouseReleased(event -> {
            if (dragSourcePort != null) {
                Point2D releasePoint = content.sceneToLocal(event.getSceneX(), event.getSceneY());
                PortView target = findPortNear(releasePoint);
                if (target != null && isValidConnection(dragSourcePort, target)) {
                    createEdge(dragSourcePort, target);
                }
            }
            content.getChildren().remove(dragLine);
            dragLine = null;
            dragSourcePort = null;
            event.consume();
        });
    }

    private PortView findPortNear(Point2D point) {
        PortView best = null;
        double bestDistance = 16;
        for (PortView port : ports) {
            double distance = port.getCenterInContent(content).distance(point);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = port;
            }
        }
        return best;
    }

    private boolean isValidConnection(PortView a, PortView b) {
        return a.getOwner() != b.getOwner() && a.getDirection() != b.getDirection();
    }

    private void createEdge(PortView a, PortView b) {
        PortView outputPort = a.getDirection() == PortView.Direction.OUTPUT ? a : b;
        PortView inputPort = a.getDirection() == PortView.Direction.OUTPUT ? b : a;

        Edge edge = new Edge(
                outputPort.getOwner().getNode(), outputPort.getVariable(),
                inputPort.getOwner().getNode(), inputPort.getVariable());
        Graph.registerEdge(edge);
        outputPort.connect();
        inputPort.connect();

        EdgeView[] edgeViewRef = new EdgeView[1];
        EdgeView edgeView = new EdgeView(outputPort, inputPort, content, () -> {
            Graph.removeEdge(edge);
            edgeViews.remove(edge);
            outputPort.disconnect();
            inputPort.disconnect();
            selectedEdges.remove(edgeViewRef[0]);
        });
        edgeViewRef[0] = edgeView;
        edgeViews.put(edge, edgeView);
        content.getChildren().add(edgeView);
    }

    // --- Node drag / selection ---------------------------------------------------

    @Override
    public void onNodePressed(NodeView node) {
        requestFocus();
        if (!selectedNodes.contains(node)) {
            clearSelection();
            selectNode(node);
        }
    }

    @Override
    public void onNodeDragged(double deltaContentX, double deltaContentY) {
        for (NodeView node : selectedNodes) {
            node.setLayoutX(node.getLayoutX() + deltaContentX);
            node.setLayoutY(node.getLayoutY() + deltaContentY);
        }
    }

    private void selectNode(NodeView node) {
        if (selectedNodes.add(node)) {
            node.setSelected(true);
        }
    }

    private void deselectNode(NodeView node) {
        if (selectedNodes.remove(node)) {
            node.setSelected(false);
        }
    }

    private void selectEdge(EdgeView edge) {
        if (selectedEdges.add(edge)) {
            edge.setSelected(true);
        }
    }

    private void deselectEdge(EdgeView edge) {
        if (selectedEdges.remove(edge)) {
            edge.setSelected(false);
        }
    }

    private void clearSelection() {
        for (NodeView node : new ArrayList<>(selectedNodes)) {
            deselectNode(node);
        }
        for (EdgeView edge : new ArrayList<>(selectedEdges)) {
            deselectEdge(edge);
        }
    }

    private void deleteSelected() {
        List<NodeView> nodesToDelete = new ArrayList<>(selectedNodes);
        List<EdgeView> edgesToDelete = new ArrayList<>(selectedEdges);

        for (NodeView node : nodesToDelete) {
            for (EdgeView edgeView : edgeViews.values()) {
                if (edgeView.touchesNode(node) && !edgesToDelete.contains(edgeView)) {
                    edgesToDelete.add(edgeView);
                }
            }
        }

        for (EdgeView edgeView : edgesToDelete) {
            edgeView.delete();
        }
        for (NodeView node : nodesToDelete) {
            removeNode(node);
        }

        selectedNodes.clear();
        selectedEdges.clear();
    }

    // --- Canvas panning (right-click) / rubber-band selection (left-click) --------

    private void handleCanvasPressed(MouseEvent event) {
        requestFocus();
        if (event.getButton() == MouseButton.SECONDARY) {
            lastDragSceneX = event.getSceneX();
            lastDragSceneY = event.getSceneY();
        } else if (event.getButton() == MouseButton.PRIMARY) {
            clearSelection();
            selectionStartContent = content.sceneToLocal(event.getSceneX(), event.getSceneY());
            selectionRectangle = new Rectangle(selectionStartContent.getX(), selectionStartContent.getY(), 0, 0);
            selectionRectangle.setFill(Color.web("#61afef", 0.15));
            selectionRectangle.setStroke(Color.web("#61afef"));
            selectionRectangle.setStrokeWidth(1);
            selectionRectangle.setMouseTransparent(true);
            content.getChildren().add(selectionRectangle);
        }
        event.consume();
    }

    private void handleCanvasDragged(MouseEvent event) {
        if (event.getButton() == MouseButton.SECONDARY) {
            translateX += event.getSceneX() - lastDragSceneX;
            translateY += event.getSceneY() - lastDragSceneY;
            lastDragSceneX = event.getSceneX();
            lastDragSceneY = event.getSceneY();
            updateTransform();
        } else if (event.getButton() == MouseButton.PRIMARY && selectionRectangle != null) {
            Point2D now = content.sceneToLocal(event.getSceneX(), event.getSceneY());
            double x = Math.min(selectionStartContent.getX(), now.getX());
            double y = Math.min(selectionStartContent.getY(), now.getY());
            double w = Math.abs(now.getX() - selectionStartContent.getX());
            double h = Math.abs(now.getY() - selectionStartContent.getY());
            selectionRectangle.setX(x);
            selectionRectangle.setY(y);
            selectionRectangle.setWidth(w);
            selectionRectangle.setHeight(h);
            updateLiveSelection(new BoundingBox(x, y, w, h));
        }
        event.consume();
    }

    private void handleCanvasReleased(MouseEvent event) {
        if (selectionRectangle != null) {
            content.getChildren().remove(selectionRectangle);
            selectionRectangle = null;
        }
    }

    private void updateLiveSelection(Bounds rect) {
        for (NodeView node : nodeViews) {
            if (node.getBoundsInParent().intersects(rect)) {
                selectNode(node);
            } else {
                deselectNode(node);
            }
        }
        for (EdgeView edge : edgeViews.values()) {
            if (edge.getBoundsInParent().intersects(rect)) {
                selectEdge(edge);
            } else {
                deselectEdge(edge);
            }
        }
    }

    // --- Zoom -----------------------------------------------------------------

    private void handleZoom(ScrollEvent event) {
        double factor = event.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
        double newZoom = clamp(zoom * factor, 0.2, 3.0);

        Point2D beforeLocal = content.sceneToLocal(event.getSceneX(), event.getSceneY());
        translateX += (zoom - newZoom) * beforeLocal.getX();
        translateY += (zoom - newZoom) * beforeLocal.getY();
        zoom = newZoom;

        updateTransform();
        event.consume();
    }

    private void updateTransform() {
        content.getTransforms().setAll(new Affine(zoom, 0, translateX, 0, zoom, translateY));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
