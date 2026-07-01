package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
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
import java.util.TreeMap;

/**
 * An infinite, pannable, zoomable canvas that hosts {@link NodeView}s and the
 * {@link EdgeView}/{@link FlowEdgeView} connections between them.
 * <p>
 * Panning: right-click-drag on empty canvas space. Zooming: mouse scroll, anchored to
 * the cursor. Left-click-drag on empty canvas space rubber-band-selects nodes/edges;
 * Delete/Backspace removes the current selection. Data edges are created by dragging
 * from one data port's circle to another; flow edges by dragging between the triangular
 * flow anchors at the top corners of each node.
 */
public class GraphCanvas extends Pane implements NodeView.DragController {

    private final NodeGraph graph;
    private final Group content = new Group();
    private final List<PortView> ports = new ArrayList<>();
    private final List<FlowPortView> flowPorts = new ArrayList<>();
    private final List<NodeView> nodeViews = new ArrayList<>();
    private final Map<Edge, EdgeView> edgeViews = new HashMap<>();
    private final Map<FlowEdge, FlowEdgeView> flowEdgeViews = new HashMap<>();

    private final Set<NodeView> selectedNodes = new LinkedHashSet<>();
    private final Set<ConnectionView> selectedConnections = new LinkedHashSet<>();

    private double zoom = 1.0;
    private double translateX = 0;
    private double translateY = 0;
    private double lastDragSceneX;
    private double lastDragSceneY;
    private int nodePlacementCounter = 0;

    private PortView dragSourcePort;
    private PortView highlightedTargetPort;
    private CubicCurve dragLine;

    private FlowPortView dragSourceFlowPort;
    private FlowPortView highlightedTargetFlowPort;
    private CubicCurve flowDragLine;

    private Rectangle selectionRectangle;
    private Point2D selectionStartContent;
    private boolean rightDragOccurred;

    private final ContextMenu addNodeMenu;
    private Point2D pendingDropPoint = Point2D.ZERO;

    public GraphCanvas(NodeGraph graph) {
        this.graph = graph;
        setStyle("-fx-background-color: #1e1e1e;");
        getChildren().add(content);
        setFocusTraversable(true);
        // Built once and reused: the set of node types on the classpath doesn't change
        // during a run.
        addNodeMenu = buildAddNodeMenu();
        // ContextMenu's built-in autoHide is focus-based and doesn't reliably fire for
        // clicks elsewhere in the same window, and NodeView/PortView consume their own
        // mouse-press events before they'd ever bubble up to this canvas's own handler.
        // A capturing-phase filter on the Scene sees every press first, before any
        // descendant gets a chance to consume it, so it's the one place that reliably
        // catches "clicked anywhere else" regardless of what was clicked.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (addNodeMenu.isShowing() && event.getButton() == MouseButton.PRIMARY) {
                        addNodeMenu.hide();
                    }
                });
            }
        });

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
        setOnContextMenuRequested(this::handleContextMenuRequested);
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
        double x = 40 + nodePlacementCounter * 30;
        double y = 40 + nodePlacementCounter * 30;
        nodePlacementCounter++;
        addNode(nodeView, x, y);
    }

    /** Adds a node at an explicit position in content (canvas) coordinates, e.g. where a context menu was opened. */
    public void addNode(NodeView nodeView, double contentX, double contentY) {
        nodeView.setLayoutX(contentX);
        nodeView.setLayoutY(contentY);

        graph.addNode(nodeView.getNode());
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

        // Flow anchors are gated by @Executable.ExecutableIn/Out on the node's class,
        // so either (or both) may be absent for a given node.
        if (nodeView.getFlowInPort() != null) {
            flowPorts.add(nodeView.getFlowInPort());
            wireFlowPort(nodeView.getFlowInPort());
        }
        if (nodeView.getFlowOutPort() != null) {
            flowPorts.add(nodeView.getFlowOutPort());
            wireFlowPort(nodeView.getFlowOutPort());
        }
    }

    private void removeNode(NodeView nodeView) {
        graph.removeNode(nodeView.getNode());
        content.getChildren().remove(nodeView);
        nodeViews.remove(nodeView);
        ports.removeAll(nodeView.getInputPorts());
        ports.removeAll(nodeView.getOutputPorts());
        if (nodeView.getFlowInPort() != null) {
            flowPorts.remove(nodeView.getFlowInPort());
        }
        if (nodeView.getFlowOutPort() != null) {
            flowPorts.remove(nodeView.getFlowOutPort());
        }
    }

    // --- Data ports / edges -----------------------------------------------------

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

            // JavaFX doesn't fire hover events on other nodes while this circle holds
            // the mouse grab, so the drop-target highlight has to be driven manually.
            PortView candidate = findPortNear(end);
            PortView validCandidate = (candidate != null && isValidConnection(dragSourcePort, candidate)) ? candidate : null;
            if (validCandidate != highlightedTargetPort) {
                if (highlightedTargetPort != null) {
                    highlightedTargetPort.setHighlighted(false);
                }
                highlightedTargetPort = validCandidate;
                if (highlightedTargetPort != null) {
                    highlightedTargetPort.setHighlighted(true);
                }
            }
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
            if (highlightedTargetPort != null) {
                highlightedTargetPort.setHighlighted(false);
                highlightedTargetPort = null;
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
        return a.getOwner() != b.getOwner()
                && a.getDirection() != b.getDirection()
                && a.getVariable().type == b.getVariable().type;
    }

    private void createEdge(PortView a, PortView b) {
        PortView outputPort = a.getDirection() == PortView.Direction.OUTPUT ? a : b;
        PortView inputPort = a.getDirection() == PortView.Direction.OUTPUT ? b : a;

        // An input can only ever be fed by one edge; wiring a new one replaces the old.
        for (EdgeView existing : new ArrayList<>(edgeViews.values())) {
            if (existing.hasTarget(inputPort)) {
                existing.delete();
            }
        }

        Edge edge = new Edge(
                outputPort.getOwner().getNode(), outputPort.getVariable(),
                inputPort.getOwner().getNode(), inputPort.getVariable());
        graph.registerEdge(edge);
        outputPort.connect();
        inputPort.connect();

        EdgeView[] edgeViewRef = new EdgeView[1];
        EdgeView edgeView = new EdgeView(outputPort, inputPort, content, () -> {
            graph.removeEdge(edge);
            edgeViews.remove(edge);
            outputPort.disconnect();
            inputPort.disconnect();
            selectedConnections.remove(edgeViewRef[0]);
        });
        edgeViewRef[0] = edgeView;
        edgeViews.put(edge, edgeView);
        content.getChildren().add(edgeView);
    }

    // --- Flow ports / edges ------------------------------------------------------

    private void wireFlowPort(FlowPortView port) {
        port.setOnMousePressed(event -> {
            dragSourceFlowPort = port;
            flowDragLine = new CubicCurve();
            flowDragLine.setFill(null);
            flowDragLine.setStroke(Color.web("#98c379"));
            flowDragLine.setStrokeWidth(2);
            flowDragLine.setMouseTransparent(true);
            content.getChildren().add(flowDragLine);

            Point2D start = port.getCenterInContent(content);
            flowDragLine.setStartX(start.getX());
            flowDragLine.setStartY(start.getY());
            flowDragLine.setEndX(start.getX());
            flowDragLine.setEndY(start.getY());
            flowDragLine.setControlX1(start.getX());
            flowDragLine.setControlY1(start.getY());
            flowDragLine.setControlX2(start.getX());
            flowDragLine.setControlY2(start.getY());
            event.consume();
        });

        port.setOnMouseDragged(event -> {
            if (flowDragLine == null) {
                return;
            }
            Point2D start = dragSourceFlowPort.getCenterInContent(content);
            Point2D end = content.sceneToLocal(event.getSceneX(), event.getSceneY());
            flowDragLine.setStartX(start.getX());
            flowDragLine.setStartY(start.getY());
            flowDragLine.setEndX(end.getX());
            flowDragLine.setEndY(end.getY());
            double controlOffset = Math.max(50, Math.abs(end.getX() - start.getX()) / 2);
            flowDragLine.setControlX1(start.getX() + controlOffset);
            flowDragLine.setControlY1(start.getY());
            flowDragLine.setControlX2(end.getX() - controlOffset);
            flowDragLine.setControlY2(end.getY());

            // Same manual hit-test as data ports: no native hover events on other
            // nodes while this port holds the mouse grab.
            FlowPortView candidate = findFlowPortNear(end);
            FlowPortView validCandidate = (candidate != null && isValidFlowConnection(dragSourceFlowPort, candidate)) ? candidate : null;
            if (validCandidate != highlightedTargetFlowPort) {
                if (highlightedTargetFlowPort != null) {
                    highlightedTargetFlowPort.setHighlighted(false);
                }
                highlightedTargetFlowPort = validCandidate;
                if (highlightedTargetFlowPort != null) {
                    highlightedTargetFlowPort.setHighlighted(true);
                }
            }
            event.consume();
        });

        port.setOnMouseReleased(event -> {
            if (dragSourceFlowPort != null) {
                Point2D releasePoint = content.sceneToLocal(event.getSceneX(), event.getSceneY());
                FlowPortView target = findFlowPortNear(releasePoint);
                if (target != null && isValidFlowConnection(dragSourceFlowPort, target)) {
                    createFlowEdge(dragSourceFlowPort, target);
                }
            }
            if (highlightedTargetFlowPort != null) {
                highlightedTargetFlowPort.setHighlighted(false);
                highlightedTargetFlowPort = null;
            }
            content.getChildren().remove(flowDragLine);
            flowDragLine = null;
            dragSourceFlowPort = null;
            event.consume();
        });
    }

    private FlowPortView findFlowPortNear(Point2D point) {
        FlowPortView best = null;
        double bestDistance = 16;
        for (FlowPortView port : flowPorts) {
            double distance = port.getCenterInContent(content).distance(point);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = port;
            }
        }
        return best;
    }

    private boolean isValidFlowConnection(FlowPortView a, FlowPortView b) {
        return a.getOwner() != b.getOwner() && a.getDirection() != b.getDirection();
    }

    private void createFlowEdge(FlowPortView a, FlowPortView b) {
        FlowPortView outPort = a.getDirection() == FlowPortView.Direction.OUT ? a : b;
        FlowPortView inPort = a.getDirection() == FlowPortView.Direction.OUT ? b : a;

        // A flow-in can only ever be fed by one edge; wiring a new one replaces the old.
        for (FlowEdgeView existing : new ArrayList<>(flowEdgeViews.values())) {
            if (existing.hasTarget(inPort)) {
                existing.delete();
            }
        }

        FlowEdge flowEdge = new FlowEdge(outPort.getOwner().getNode(), inPort.getOwner().getNode());
        graph.registerFlowEdge(flowEdge);

        FlowEdgeView[] flowEdgeViewRef = new FlowEdgeView[1];
        FlowEdgeView flowEdgeView = new FlowEdgeView(outPort, inPort, content, () -> {
            graph.removeFlowEdge(flowEdge);
            flowEdgeViews.remove(flowEdge);
            selectedConnections.remove(flowEdgeViewRef[0]);
        });
        flowEdgeViewRef[0] = flowEdgeView;
        flowEdgeViews.put(flowEdge, flowEdgeView);
        content.getChildren().add(flowEdgeView);
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

    private void selectConnection(ConnectionView connection) {
        if (selectedConnections.add(connection)) {
            connection.setSelected(true);
        }
    }

    private void deselectConnection(ConnectionView connection) {
        if (selectedConnections.remove(connection)) {
            connection.setSelected(false);
        }
    }

    private void clearSelection() {
        for (NodeView node : new ArrayList<>(selectedNodes)) {
            deselectNode(node);
        }
        for (ConnectionView connection : new ArrayList<>(selectedConnections)) {
            deselectConnection(connection);
        }
    }

    private List<ConnectionView> allConnections() {
        List<ConnectionView> all = new ArrayList<>(edgeViews.values());
        all.addAll(flowEdgeViews.values());
        return all;
    }

    private void deleteSelected() {
        List<NodeView> nodesToDelete = new ArrayList<>(selectedNodes);
        List<ConnectionView> connectionsToDelete = new ArrayList<>(selectedConnections);

        for (NodeView node : nodesToDelete) {
            for (ConnectionView connection : allConnections()) {
                if (connection.touchesNode(node) && !connectionsToDelete.contains(connection)) {
                    connectionsToDelete.add(connection);
                }
            }
        }

        for (ConnectionView connection : connectionsToDelete) {
            connection.delete();
        }
        for (NodeView node : nodesToDelete) {
            removeNode(node);
        }

        selectedNodes.clear();
        selectedConnections.clear();
    }

    // --- Canvas panning (right-click) / rubber-band selection (left-click) --------

    private void handleCanvasPressed(MouseEvent event) {
        requestFocus();
        if (event.getButton() == MouseButton.SECONDARY) {
            rightDragOccurred = false;
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
            rightDragOccurred = true;
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

    // --- Add-node context menu ----------------------------------------------------

    private void handleContextMenuRequested(ContextMenuEvent event) {
        if (rightDragOccurred) {
            // The right-click was actually a pan gesture; don't also pop a menu.
            return;
        }
        pendingDropPoint = content.sceneToLocal(event.getSceneX(), event.getSceneY());
        addNodeMenu.hide();
        addNodeMenu.show(this, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    private ContextMenu buildAddNodeMenu() {
        ContextMenu menu = new ContextMenu();
        Map<String, Menu> categoryMenus = new TreeMap<>();

        for (NodeRegistry.Entry entry : NodeRegistry.discover()) {
            MenuItem item = new MenuItem(entry.displayName());
            item.setOnAction(event -> {
                BaseNode instance = NodeRegistry.instantiate(entry.nodeClass());
                if (instance != null) {
                    addNode(new NodeView(instance, content, this), pendingDropPoint.getX(), pendingDropPoint.getY());
                }
            });

            Menu categoryMenu = resolveCategoryMenu(menu, categoryMenus, entry.categoryPath());
            (categoryMenu == null ? menu.getItems() : categoryMenu.getItems()).add(item);
        }

        if (menu.getItems().isEmpty()) {
            MenuItem none = new MenuItem("(no node types found)");
            none.setDisable(true);
            menu.getItems().add(none);
        }
        return menu;
    }

    /** Finds (creating as needed) the Menu for a dot-separated category path, nesting under its parent categories. */
    private Menu resolveCategoryMenu(ContextMenu root, Map<String, Menu> categoryMenus, String categoryPath) {
        if (categoryPath.isEmpty()) {
            return null;
        }
        Menu existing = categoryMenus.get(categoryPath);
        if (existing != null) {
            return existing;
        }

        int lastDot = categoryPath.lastIndexOf('.');
        String parentPath = lastDot < 0 ? "" : categoryPath.substring(0, lastDot);
        String label = lastDot < 0 ? categoryPath : categoryPath.substring(lastDot + 1);

        Menu menu = new Menu(label);
        categoryMenus.put(categoryPath, menu);

        Menu parentMenu = resolveCategoryMenu(root, categoryMenus, parentPath);
        (parentMenu == null ? root.getItems() : parentMenu.getItems()).add(menu);
        return menu;
    }

    private void updateLiveSelection(Bounds rect) {
        for (NodeView node : nodeViews) {
            if (node.getBoundsInParent().intersects(rect)) {
                selectNode(node);
            } else {
                deselectNode(node);
            }
        }
        for (ConnectionView connection : allConnections()) {
            if (connection.getBoundsInParent().intersects(rect)) {
                selectConnection(connection);
            } else {
                deselectConnection(connection);
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
