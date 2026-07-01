package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.Graph;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.transform.Affine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An infinite, pannable, zoomable canvas that hosts {@link NodeView}s and the
 * {@link EdgeView}s connecting them.
 * <p>
 * Panning: click-drag on empty canvas space. Zooming: mouse scroll, anchored to
 * the cursor. Edges are created by dragging from one port's circle to another.
 */
public class GraphCanvas extends Pane {

    private final Group content = new Group();
    private final List<PortView> ports = new ArrayList<>();
    private final Map<Edge, EdgeView> edgeViews = new HashMap<>();

    private double zoom = 1.0;
    private double translateX = 0;
    private double translateY = 0;
    private double lastDragSceneX;
    private double lastDragSceneY;
    private int nodePlacementCounter = 0;

    private PortView dragSourcePort;
    private CubicCurve dragLine;

    public GraphCanvas() {
        setStyle("-fx-background-color: #1e1e1e;");
        getChildren().add(content);

        setOnScroll(this::handleZoom);
        setOnMousePressed(this::handlePanStart);
        setOnMouseDragged(this::handlePanDrag);

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
        ports.addAll(nodeView.getInputPorts());
        ports.addAll(nodeView.getOutputPorts());

        for (PortView port : nodeView.getInputPorts()) {
            wirePort(port);
        }
        for (PortView port : nodeView.getOutputPorts()) {
            wirePort(port);
        }
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

        EdgeView edgeView = new EdgeView(outputPort, inputPort, content, () -> {
            Graph.removeEdge(edge);
            edgeViews.remove(edge);
        });
        edgeViews.put(edge, edgeView);
        content.getChildren().add(edgeView);
    }

    private void handlePanStart(javafx.scene.input.MouseEvent event) {
        lastDragSceneX = event.getSceneX();
        lastDragSceneY = event.getSceneY();
    }

    private void handlePanDrag(javafx.scene.input.MouseEvent event) {
        translateX += event.getSceneX() - lastDragSceneX;
        translateY += event.getSceneY() - lastDragSceneY;
        lastDragSceneX = event.getSceneX();
        lastDragSceneY = event.getSceneY();
        updateTransform();
    }

    private void handleZoom(javafx.scene.input.ScrollEvent event) {
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
