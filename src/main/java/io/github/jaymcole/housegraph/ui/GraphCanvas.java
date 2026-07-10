package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.ui.command.AddNodeCommand;
import io.github.jaymcole.housegraph.ui.command.CreateEdgeCommand;
import io.github.jaymcole.housegraph.ui.command.CreateFlowEdgeCommand;
import io.github.jaymcole.housegraph.ui.command.MoveNodesCommand;
import io.github.jaymcole.housegraph.ui.command.PasteCommand;
import io.github.jaymcole.housegraph.ui.command.RemoveNodesCommand;
import io.github.jaymcole.housegraph.ui.command.SetWaypointsCommand;
import io.github.jaymcole.housegraph.ui.command.UndoManager;
import io.github.jaymcole.housegraph.ui.view.AbstractEdgeView;
import io.github.jaymcole.housegraph.ui.view.ConnectionView;
import io.github.jaymcole.housegraph.ui.view.EdgeInteractionListener;
import io.github.jaymcole.housegraph.ui.view.EdgeView;
import io.github.jaymcole.housegraph.ui.view.FlowEdgeView;
import io.github.jaymcole.housegraph.ui.view.FlowPortView;
import io.github.jaymcole.housegraph.ui.view.NodeView;
import io.github.jaymcole.housegraph.ui.view.PortView;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardDataEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardNode;
import io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.GraphExecutionListener;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import javafx.application.Platform;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Affine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * An infinite, pannable, zoomable canvas that hosts {@link NodeView}s and the
 * {@link EdgeView}/{@link FlowEdgeView} connections between them.
 * <p>
 * Panning: middle-click-drag on empty canvas space. Zooming: mouse scroll, anchored to
 * the cursor. Left-click-drag on empty canvas space rubber-band-selects nodes/edges;
 * right-click opens the "Add Node" menu. Delete/Backspace removes the current
 * selection; Ctrl/Cmd+C and Ctrl/Cmd+V copy and paste it; Ctrl/Cmd+Z and
 * Ctrl/Cmd+Shift+Z undo and redo (currently: adding a node via the menu, and deleting
 * nodes/connections - see {@link UndoManager}). Data edges are created by dragging from
 * one data port's circle to another; flow edges by dragging between the triangular
 * flow anchors at the top corners of each node.
 */
public class GraphCanvas extends Pane implements NodeView.DragController, GraphExecutionListener, EdgeInteractionListener {

    private static final Logger log = Log.get(GraphCanvas.class);

    private static final KeyCodeCombination COPY_COMBO = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination PASTE_COMBO = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination UNDO_COMBO = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination REDO_COMBO =
            new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    private final NodeGraph graph;
    private final Group content = new Group();
    private final List<PortView> ports = new ArrayList<>();
    private final List<FlowPortView> flowPorts = new ArrayList<>();
    private final List<NodeView> nodeViews = new ArrayList<>();
    private final Map<BaseNode, NodeView> nodeViewByNode = new HashMap<>();
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

    private final ContextMenu contextMenu;
    private Point2D pendingDropPoint = Point2D.ZERO;

    private List<ClipboardNode> clipboardNodes = List.of();
    private List<ClipboardDataEdge> clipboardDataEdges = List.of();
    private List<ClipboardFlowEdge> clipboardFlowEdges = List.of();
    private int pasteOffsetStep = 0;

    private final UndoManager undoManager = new UndoManager();

    public GraphCanvas(NodeGraph graph) {
        this.graph = graph;
        setStyle("-fx-background-color: #1e1e1e;");
        getChildren().add(content);
        setFocusTraversable(true);
        // Lets this canvas flash a node/edge whenever the graph actually runs it,
        // without NodeGraph needing to know anything about JavaFX.
        graph.addExecutionListener(this);
        // NodeGraph runs triggers on a background thread so a slow node can't freeze
        // the UI; this is what makes its callbacks (onExecuted(), the listener above)
        // actually land back on the FX Application Thread instead of that background one.
        graph.setCallbackExecutor(Platform::runLater);
        // Built once and reused: the set of node types on the classpath doesn't change
        // during a run.
        contextMenu = buildContextMenu();
        // ContextMenu's built-in autoHide is focus-based and doesn't reliably fire for
        // clicks elsewhere in the same window, and NodeView/PortView consume their own
        // mouse-press events before they'd ever bubble up to this canvas's own handler.
        // A capturing-phase filter on the Scene sees every press first, before any
        // descendant gets a chance to consume it, so it's the one place that reliably
        // catches "clicked anywhere else" regardless of what was clicked.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (contextMenu.isShowing() && event.getButton() == MouseButton.PRIMARY) {
                        contextMenu.hide();
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
            } else if (COPY_COMBO.match(event)) {
                copySelection();
                event.consume();
            } else if (PASTE_COMBO.match(event)) {
                pasteClipboard();
                event.consume();
            } else if (REDO_COMBO.match(event)) {
                undoManager.redo();
                event.consume();
            } else if (UNDO_COMBO.match(event)) {
                undoManager.undo();
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
        BaseNode node = nodeView.getNode();
        graph.addNode(node);
        // Lets a node whose ports depend on its settings ask us to rebuild its view.
        // Keyed by the node so it stays valid across rebuilds (the view is looked up live).
        node.setPortsChangedListener(() -> rebuildNodeView(node));
        addNodeView(nodeView, contentX, contentY);
    }

    /** The view/wiring half of adding a node — used on its own when a node's view is rebuilt in place (the node stays in the graph). */
    private void addNodeView(NodeView nodeView, double contentX, double contentY) {
        nodeView.setLayoutX(contentX);
        nodeView.setLayoutY(contentY);

        content.getChildren().add(nodeView);
        nodeViews.add(nodeView);
        nodeViewByNode.put(nodeView.getNode(), nodeView);
        ports.addAll(nodeView.getInputPorts());
        ports.addAll(nodeView.getOutputPorts());

        for (PortView port : nodeView.getInputPorts()) {
            wirePort(port);
        }
        for (PortView port : nodeView.getOutputPorts()) {
            wirePort(port);
        }

        // A node exposes zero or more flow anchors per side (see NodeView); wire each.
        for (FlowPortView flowPort : nodeView.getFlowInPorts()) {
            flowPorts.add(flowPort);
            wireFlowPort(flowPort);
        }
        for (FlowPortView flowPort : nodeView.getFlowOutPorts()) {
            flowPorts.add(flowPort);
            wireFlowPort(flowPort);
        }
    }

    public void removeNode(NodeView nodeView) {
        graph.removeNode(nodeView.getNode());
        removeNodeView(nodeView);
    }

    /** The view half of removing a node — used on its own when rebuilding a view (the node stays in the graph, so onRemoved must not fire). */
    private void removeNodeView(NodeView nodeView) {
        content.getChildren().remove(nodeView);
        nodeViews.remove(nodeView);
        nodeViewByNode.remove(nodeView.getNode());
        ports.removeAll(nodeView.getInputPorts());
        ports.removeAll(nodeView.getOutputPorts());
        flowPorts.removeAll(nodeView.getFlowInPorts());
        flowPorts.removeAll(nodeView.getFlowOutPorts());
    }

    /**
     * Rebuilds a node's on-canvas view after its ports changed ({@link BaseNode#reconfigure()}
     * already ran). Edges touching the node are captured, dropped, and re-created against
     * the new view: data edges reconnect by port name, flow edges by position; an edge to
     * a port that no longer exists is quietly dropped. Manual routing (waypoints) is kept.
     */
    private void rebuildNodeView(BaseNode node) {
        NodeView oldView = nodeViewByNode.get(node);
        // Skip if the node has left the graph. A ports-changed request can arrive late -
        // e.g. a decomposer's edge-removed hook is dispatched asynchronously and only runs
        // after the graph was torn down on app close - by which point rebuilding its view
        // (and trying to re-wire edges to its now-unregistered neighbours) is both pointless
        // and would throw.
        if (oldView == null || !graph.getNodes().contains(node)) {
            return;
        }
        double x = oldView.getLayoutX();
        double y = oldView.getLayoutY();

        List<CapturedRebuildEdge> capturedData = new ArrayList<>();
        List<CapturedRebuildFlowEdge> capturedFlow = new ArrayList<>();
        for (EdgeView edgeView : new ArrayList<>(edgeViews.values())) {
            captureForRebuild(edgeView, oldView, capturedData);
        }
        for (FlowEdgeView flowEdgeView : new ArrayList<>(flowEdgeViews.values())) {
            captureForRebuild(flowEdgeView, oldView, capturedFlow);
        }

        for (CapturedRebuildEdge captured : capturedData) {
            captured.view().delete();
        }
        for (CapturedRebuildFlowEdge captured : capturedFlow) {
            captured.view().delete();
        }

        deselectNode(oldView);
        removeNodeView(oldView);

        NodeView newView = new NodeView(node, content, this);
        addNodeView(newView, x, y);
        forceLayout();

        for (CapturedRebuildEdge captured : capturedData) {
            PortView port = findPortByName(newView, captured.nodeIsSource(), captured.portName());
            if (port == null) {
                continue; // that option/port is gone now
            }
            PortView source = captured.nodeIsSource() ? port : captured.otherPort();
            PortView target = captured.nodeIsSource() ? captured.otherPort() : port;
            try {
                createEdge(source, target).setWaypoints(captured.waypoints());
            } catch (RuntimeException e) {
                // e.g. the port's type changed and no longer matches - drop just this edge.
                log.warn("Could not reconnect edge after rebuild: {}", e.getMessage());
            }
        }
        for (CapturedRebuildFlowEdge captured : capturedFlow) {
            List<FlowPortView> flowPortViews = captured.nodeIsSource() ? newView.getFlowOutPorts() : newView.getFlowInPorts();
            if (captured.portIndex() < 0 || captured.portIndex() >= flowPortViews.size()) {
                continue;
            }
            FlowPortView port = flowPortViews.get(captured.portIndex());
            FlowPortView source = captured.nodeIsSource() ? port : captured.otherPort();
            FlowPortView target = captured.nodeIsSource() ? captured.otherPort() : port;
            try {
                createFlowEdge(source, target).setWaypoints(captured.waypoints());
            } catch (RuntimeException e) {
                // The other endpoint may no longer be in the graph (e.g. removed as part of
                // the same teardown) - drop just this edge, same as the data-edge reconnect.
                log.warn("Could not reconnect flow edge after rebuild: {}", e.getMessage());
            }
        }
    }

    private void captureForRebuild(EdgeView edgeView, NodeView node, List<CapturedRebuildEdge> out) {
        PortView source = edgeView.getSourcePort();
        PortView target = edgeView.getTargetPort();
        if (source.getOwner() == node) {
            out.add(new CapturedRebuildEdge(edgeView, target, true, source.getVariable().name, edgeView.getWaypoints()));
        } else if (target.getOwner() == node) {
            out.add(new CapturedRebuildEdge(edgeView, source, false, target.getVariable().name, edgeView.getWaypoints()));
        }
    }

    private void captureForRebuild(FlowEdgeView flowEdgeView, NodeView node, List<CapturedRebuildFlowEdge> out) {
        FlowPortView source = flowEdgeView.getSourcePort();
        FlowPortView target = flowEdgeView.getTargetPort();
        if (source.getOwner() == node) {
            out.add(new CapturedRebuildFlowEdge(flowEdgeView, target, true,
                    node.getFlowOutPorts().indexOf(source), flowEdgeView.getWaypoints()));
        } else if (target.getOwner() == node) {
            out.add(new CapturedRebuildFlowEdge(flowEdgeView, source, false,
                    node.getFlowInPorts().indexOf(target), flowEdgeView.getWaypoints()));
        }
    }

    private static PortView findPortByName(NodeView view, boolean output, String name) {
        for (PortView port : output ? view.getOutputPorts() : view.getInputPorts()) {
            if (port.getVariable().name.equals(name)) {
                return port;
            }
        }
        return null;
    }

    /** A data edge captured before a node's view rebuild: the other (stable) endpoint, plus how to find this node's end again by name. */
    private record CapturedRebuildEdge(EdgeView view, PortView otherPort, boolean nodeIsSource, String portName,
                                       List<Point2D> waypoints) {
    }

    private record CapturedRebuildFlowEdge(FlowEdgeView view, FlowPortView otherPort, boolean nodeIsSource, int portIndex,
                                           List<Point2D> waypoints) {
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

            for (PortView candidate : ports) {
                if (candidate != port) {
                    candidate.setInvalid(!isValidConnection(port, candidate));
                }
            }
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
                    undoManager.execute(new CreateEdgeCommand(this, dragSourcePort, target));
                }
            }
            if (highlightedTargetPort != null) {
                highlightedTargetPort.setHighlighted(false);
                highlightedTargetPort = null;
            }
            for (PortView candidate : ports) {
                candidate.setInvalid(false);
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
        if (a.getOwner() == b.getOwner() || a.getDirection() == b.getDirection()) {
            return false;
        }
        // A source's value flows into the input, so the input's type only has to be
        // assignable from the source's - exact matches still pass, and an Object input
        // (e.g. the decomposer's) accepts anything. Mirrors NodeGraph.attachEdge.
        PortView output = a.getDirection() == PortView.Direction.OUTPUT ? a : b;
        PortView input = output == a ? b : a;
        return input.getVariable().type.isAssignableFrom(output.getVariable().type);
    }

    /** The live edge currently feeding a given input port, if any - e.g. so CreateEdgeCommand can capture what it's about to replace. */
    public EdgeView findEdgeViewTargeting(PortView port) {
        for (EdgeView edgeView : edgeViews.values()) {
            if (edgeView.hasTarget(port)) {
                return edgeView;
            }
        }
        return null;
    }

    public EdgeView createEdge(PortView a, PortView b) {
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
        // Wiring an input can satisfy a required input; re-evaluate the target node's status.
        inputPort.getOwner().refreshValidation();

        EdgeView[] edgeViewRef = new EdgeView[1];
        EdgeView edgeView = new EdgeView(outputPort, inputPort, content,
                this,
                () -> {
                    graph.removeEdge(edge);
                    edgeViews.remove(edge);
                    outputPort.disconnect();
                    inputPort.disconnect();
                    // Removing the edge may leave a required input unsatisfied again.
                    inputPort.getOwner().refreshValidation();
                    selectedConnections.remove(edgeViewRef[0]);
                });
        edgeViewRef[0] = edgeView;
        edgeViews.put(edge, edgeView);
        content.getChildren().add(edgeView);
        return edgeView;
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

            for (FlowPortView candidate : flowPorts) {
                if (candidate != port) {
                    candidate.setInvalid(!isValidFlowConnection(port, candidate));
                }
            }
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
                    undoManager.execute(new CreateFlowEdgeCommand(this, dragSourceFlowPort, target));
                }
            }
            if (highlightedTargetFlowPort != null) {
                highlightedTargetFlowPort.setHighlighted(false);
                highlightedTargetFlowPort = null;
            }
            for (FlowPortView candidate : flowPorts) {
                candidate.setInvalid(false);
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

    /** The live flow edge currently feeding a given flow-in port, if any - see findEdgeViewTargeting(). */
    public FlowEdgeView findFlowEdgeViewTargeting(FlowPortView port) {
        for (FlowEdgeView flowEdgeView : flowEdgeViews.values()) {
            if (flowEdgeView.hasTarget(port)) {
                return flowEdgeView;
            }
        }
        return null;
    }

    public FlowEdgeView createFlowEdge(FlowPortView a, FlowPortView b) {
        FlowPortView outPort = a.getDirection() == FlowPort.Direction.OUT ? a : b;
        FlowPortView inPort = a.getDirection() == FlowPort.Direction.OUT ? b : a;

        // A flow-in can only ever be fed by one edge; wiring a new one replaces the old.
        for (FlowEdgeView existing : new ArrayList<>(flowEdgeViews.values())) {
            if (existing.hasTarget(inPort)) {
                existing.delete();
            }
        }

        FlowEdge flowEdge = new FlowEdge(
                outPort.getOwner().getNode(), outPort.getFlowPort(),
                inPort.getOwner().getNode(), inPort.getFlowPort());
        graph.registerFlowEdge(flowEdge);

        FlowEdgeView[] flowEdgeViewRef = new FlowEdgeView[1];
        FlowEdgeView flowEdgeView = new FlowEdgeView(outPort, inPort, content,
                this,
                () -> {
                    graph.removeFlowEdge(flowEdge);
                    flowEdgeViews.remove(flowEdge);
                    selectedConnections.remove(flowEdgeViewRef[0]);
                });
        flowEdgeViewRef[0] = flowEdgeView;
        flowEdgeViews.put(flowEdge, flowEdgeView);
        content.getChildren().add(flowEdgeView);
        return flowEdgeView;
    }

    // --- Execution animations ----------------------------------------------------

    @Override
    public void onNodeStarted(BaseNode node) {
        NodeView nodeView = nodeViewByNode.get(node);
        if (nodeView != null) {
            nodeView.setProcessing(true);
        }
    }

    @Override
    public void onNodeExecuted(BaseNode node) {
        NodeView nodeView = nodeViewByNode.get(node);
        if (nodeView != null) {
            nodeView.setProcessing(false);
            nodeView.pulse();
        }
    }

    @Override
    public void onDataEdgeTraversed(Edge edge) {
        EdgeView edgeView = edgeViews.get(edge);
        if (edgeView != null) {
            edgeView.pulse();
        }
    }

    @Override
    public void onFlowEdgeTraversed(FlowEdge edge) {
        FlowEdgeView flowEdgeView = flowEdgeViews.get(edge);
        if (flowEdgeView != null) {
            flowEdgeView.pulse();
        }
    }

    // --- Node drag / selection ---------------------------------------------------

    private List<NodeView> dragGestureNodes;
    private double[] dragGestureStartX;
    private double[] dragGestureStartY;

    @Override
    public void onNodePressed(NodeView node) {
        requestFocus();
        if (!selectedNodes.contains(node)) {
            clearSelection();
            selectNode(node);
        }

        // Snapshot positions now, before any movement, so onNodeReleased() can tell
        // whether this gesture actually moved anything and record one undo step for
        // the whole group if so - onNodeDragged() below already applies the movement
        // live, for real-time visual feedback while dragging.
        dragGestureNodes = new ArrayList<>(selectedNodes);
        dragGestureStartX = new double[dragGestureNodes.size()];
        dragGestureStartY = new double[dragGestureNodes.size()];
        for (int i = 0; i < dragGestureNodes.size(); i++) {
            dragGestureStartX[i] = dragGestureNodes.get(i).getLayoutX();
            dragGestureStartY[i] = dragGestureNodes.get(i).getLayoutY();
        }
    }

    @Override
    public void onNodeDragged(double deltaContentX, double deltaContentY) {
        for (NodeView node : selectedNodes) {
            node.setLayoutX(node.getLayoutX() + deltaContentX);
            node.setLayoutY(node.getLayoutY() + deltaContentY);
        }
    }

    @Override
    public void onNodeReleased() {
        if (dragGestureNodes == null) {
            return;
        }
        double[] endX = new double[dragGestureNodes.size()];
        double[] endY = new double[dragGestureNodes.size()];
        boolean moved = false;
        for (int i = 0; i < dragGestureNodes.size(); i++) {
            endX[i] = dragGestureNodes.get(i).getLayoutX();
            endY[i] = dragGestureNodes.get(i).getLayoutY();
            moved |= endX[i] != dragGestureStartX[i] || endY[i] != dragGestureStartY[i];
        }
        if (moved) {
            undoManager.record(new MoveNodesCommand(dragGestureNodes, dragGestureStartX, dragGestureStartY, endX, endY));
        }
        dragGestureNodes = null;
    }

    private void selectNode(NodeView node) {
        if (selectedNodes.add(node)) {
            node.setSelected(true);
        }
    }

    /** Package-visible (not just private) so a Command can make sure whatever it removes doesn't linger in the selection. */
    public void deselectNode(NodeView node) {
        if (selectedNodes.remove(node)) {
            node.setSelected(false);
        }
    }

    private void selectConnection(ConnectionView connection) {
        if (selectedConnections.add(connection)) {
            connection.setSelected(true);
        }
    }

    /**
     * Selects just this connection, replacing the current selection - what a single
     * click on an edge does. Also takes keyboard focus so it can then be deleted with
     * Delete/Backspace (the edge's own delete button is gone).
     */
    void selectOnlyConnection(ConnectionView connection) {
        requestFocus();
        clearSelection();
        selectConnection(connection);
    }

    // --- EdgeInteractionListener (edges reporting back to the canvas) --------------

    @Override
    public void selectEdge(AbstractEdgeView edge) {
        selectOnlyConnection(edge);
    }

    @Override
    public void waypointsChanged(AbstractEdgeView edge, List<Point2D> before, List<Point2D> after) {
        // The edge already applied the change live, so just record it as an undo step.
        undoManager.record(new SetWaypointsCommand(edge, before, after));
    }

    /** Package-visible (not just private) so a Command can make sure whatever it removes doesn't linger in the selection. */
    public void deselectConnection(ConnectionView connection) {
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

    /** Undoable delete of the current selection: selected nodes, plus every connection touching one, plus any standalone selected connection. */
    private void deleteSelected() {
        if (selectedNodes.isEmpty() && selectedConnections.isEmpty()) {
            return;
        }

        List<NodeView> nodesToDelete = new ArrayList<>(selectedNodes);
        // Start from selectedConnections (not just what touches a selected node) so a
        // connection selected on its own - e.g. rubber-banding over just an edge,
        // without either endpoint node - actually gets deleted too.
        List<ConnectionView> connectionsToDelete = new ArrayList<>(selectedConnections);
        for (NodeView node : nodesToDelete) {
            for (ConnectionView connection : allConnections()) {
                if (connection.touchesNode(node) && !connectionsToDelete.contains(connection)) {
                    connectionsToDelete.add(connection);
                }
            }
        }

        undoManager.execute(new RemoveNodesCommand(this, nodesToDelete, connectionsToDelete));

        selectedNodes.clear();
        selectedConnections.clear();
    }

    /**
     * Deletes a set of nodes plus any connection touching one of them (cascading, so
     * no orphaned edges are left behind), deselecting everything it removes so
     * selectedNodes/selectedConnections never retain a stale reference to something no
     * longer on the canvas. Not itself undoable - used directly to fully reset the
     * canvas, and by PasteCommand.undo() (removing a paste needs exactly this same
     * cascade, and re-recording it as its own undo step would be wrong).
     */
    public void deleteNodes(Collection<NodeView> nodesToDelete) {
        List<NodeView> nodes = new ArrayList<>(nodesToDelete);
        List<ConnectionView> connectionsToDelete = new ArrayList<>();

        for (NodeView node : nodes) {
            for (ConnectionView connection : allConnections()) {
                if (connection.touchesNode(node) && !connectionsToDelete.contains(connection)) {
                    connectionsToDelete.add(connection);
                }
            }
        }

        for (ConnectionView connection : connectionsToDelete) {
            deselectConnection(connection);
            connection.delete();
        }
        for (NodeView node : nodes) {
            deselectNode(node);
            removeNode(node);
        }
    }

    /** Removes everything from the canvas, e.g. before loading a graph from file. */
    private void clearAll() {
        deleteNodes(nodeViews);
        selectedNodes.clear();
        selectedConnections.clear();
    }

    // --- Copy / paste / save-load snapshotting --------------------------------------

    /**
     * Captures a set of nodes plus any data/flow edges that run between two of them —
     * edges to a node outside the set aren't included, since the other endpoint isn't
     * part of the snapshot. Used for both copy (a selection) and save-to-file (every
     * node on the canvas).
     */
    private GraphSnapshot snapshotOf(Collection<NodeView> views) {
        List<NodeView> ordered = new ArrayList<>(views);
        Map<BaseNode, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            indexOf.put(ordered.get(i).getNode(), i);
        }

        List<ClipboardNode> nodes = new ArrayList<>();
        for (NodeView nodeView : ordered) {
            nodes.add(new ClipboardNode(nodeView.getNode(), nodeView.getLayoutX(), nodeView.getLayoutY()));
        }

        List<ClipboardDataEdge> dataEdges = new ArrayList<>();
        for (Map.Entry<Edge, EdgeView> entry : edgeViews.entrySet()) {
            Edge edge = entry.getKey();
            Integer sourceIndex = indexOf.get(edge.getSourceNode());
            Integer targetIndex = indexOf.get(edge.getTargetNode());
            if (sourceIndex == null || targetIndex == null) {
                continue;
            }
            int sourceVarIndex = edge.getSourceNode().getOutputs().indexOf(edge.getSourceVariable());
            int targetVarIndex = edge.getTargetNode().getInputs().indexOf(edge.getTargetVariable());
            dataEdges.add(new ClipboardDataEdge(sourceIndex, sourceVarIndex, targetIndex, targetVarIndex,
                    entry.getValue().getWaypoints()));
        }

        List<ClipboardFlowEdge> flowEdges = new ArrayList<>();
        for (Map.Entry<FlowEdge, FlowEdgeView> entry : flowEdgeViews.entrySet()) {
            FlowEdge flowEdge = entry.getKey();
            Integer sourceIndex = indexOf.get(flowEdge.getSourceNode());
            Integer targetIndex = indexOf.get(flowEdge.getTargetNode());
            if (sourceIndex == null || targetIndex == null) {
                continue;
            }
            int sourcePortIndex = flowEdge.getSourceNode().getFlowOutputs().indexOf(flowEdge.getSourcePort());
            int targetPortIndex = flowEdge.getTargetNode().getFlowInputs().indexOf(flowEdge.getTargetPort());
            flowEdges.add(new ClipboardFlowEdge(sourceIndex, sourcePortIndex, targetIndex, targetPortIndex,
                    entry.getValue().getWaypoints()));
        }

        return new GraphSnapshot(nodes, dataEdges, flowEdges);
    }

    /**
     * Forces an immediate CSS + layout pass instead of waiting for the next pulse.
     * A brand-new (or just re-added) NodeView's ports don't have accurate on-screen
     * positions until this happens, but EdgeView/FlowEdgeView compute their path
     * immediately in their constructor via each port's localToScene() - so anything
     * that adds nodes and then immediately wires edges between them needs this first,
     * or the edges render in the wrong place until something else triggers a layout.
     */
    public void forceLayout() {
        content.applyCss();
        content.layout();
    }

    /**
     * Places a snapshot's nodes onto the canvas (each one built from {@code nodeFactory},
     * offset from its captured position) and reconnects the internal edges. Shared by
     * paste (factory duplicates the clipboard's live node instances) and load-from-file
     * (factory just returns the already-freshly-built node parsed from JSON).
     */
    public List<NodeView> place(GraphSnapshot snapshot, Function<ClipboardNode, BaseNode> nodeFactory, double offsetX, double offsetY) {
        List<NodeView> placed = new ArrayList<>();
        // Index-aligned with snapshot.nodes() - and therefore with the node indices saved edges
        // reference. A node the factory can't build (an unknown type, a duplicate that failed)
        // leaves a null slot here rather than being dropped, so every later node keeps its original
        // index and edges still resolve to the node they were wired to. `placed` (returned) holds
        // only the real views, so callers that select/undo the result never see a null.
        List<NodeView> byIndex = new ArrayList<>();
        for (ClipboardNode entry : snapshot.nodes()) {
            BaseNode node = nodeFactory.apply(entry);
            if (node == null) {
                byIndex.add(null);
                continue;
            }
            NodeView nodeView = new NodeView(node, content, this);
            addNode(nodeView, entry.x() + offsetX, entry.y() + offsetY);
            placed.add(nodeView);
            byIndex.add(nodeView);
        }

        forceLayout();

        // Reconnect each edge independently. A save file can outlive the node contract it was
        // written against - a node type that dropped an output, or an unknown node type that
        // loaded as a null slot above - which leaves stale edge endpoints. Each edge is
        // reconnected in isolation so one unresolvable endpoint drops only that edge; it must
        // never abort the loop and cost the user every remaining edge.
        for (ClipboardDataEdge dataEdge : snapshot.dataEdges()) {
            try {
                reconnectDataEdge(byIndex, dataEdge, offsetX, offsetY);
            } catch (RuntimeException e) {
                log.warn("Skipping data edge that failed to reconnect: {}", e.toString());
            }
        }

        for (ClipboardFlowEdge flowEdge : snapshot.flowEdges()) {
            try {
                reconnectFlowEdge(byIndex, flowEdge, offsetX, offsetY);
            } catch (RuntimeException e) {
                log.warn("Skipping flow edge that failed to reconnect: {}", e.toString());
            }
        }

        return placed;
    }

    /**
     * Reconnects one saved data edge, or skips it with a warning if either endpoint no longer
     * resolves (a node index past the loaded node count, or a port index the node no longer has).
     * Skipping rather than throwing is what keeps one stale edge from aborting the whole reconnect
     * pass - see {@link #place}.
     */
    private void reconnectDataEdge(List<NodeView> nodesByIndex, ClipboardDataEdge edge, double offsetX, double offsetY) {
        NodeView sourceView = nodeAt(nodesByIndex, edge.sourceNodeIndex());
        NodeView targetView = nodeAt(nodesByIndex, edge.targetNodeIndex());
        if (sourceView == null || targetView == null) {
            log.warn("Skipping data edge to unresolved node (source={}, target={}, node count={})",
                    edge.sourceNodeIndex(), edge.targetNodeIndex(), nodesByIndex.size());
            return;
        }
        PortView sourcePort = portAt(sourceView.getOutputPorts(), edge.sourceVariableIndex());
        PortView targetPort = portAt(targetView.getInputPorts(), edge.targetVariableIndex());
        if (sourcePort == null || targetPort == null) {
            log.warn("Skipping data edge with out-of-range port index (sourceVar={}, targetVar={}) between nodes {} and {}",
                    edge.sourceVariableIndex(), edge.targetVariableIndex(), edge.sourceNodeIndex(), edge.targetNodeIndex());
            return;
        }
        EdgeView edgeView = createEdge(sourcePort, targetPort);
        edgeView.setWaypoints(offsetPoints(edge.waypoints(), offsetX, offsetY));
    }

    /** Flow-edge counterpart of {@link #reconnectDataEdge}: reconnects one saved flow edge, or skips it with a warning if an endpoint no longer resolves. */
    private void reconnectFlowEdge(List<NodeView> nodesByIndex, ClipboardFlowEdge edge, double offsetX, double offsetY) {
        NodeView sourceView = nodeAt(nodesByIndex, edge.sourceNodeIndex());
        NodeView targetView = nodeAt(nodesByIndex, edge.targetNodeIndex());
        if (sourceView == null || targetView == null) {
            log.warn("Skipping flow edge to unresolved node (source={}, target={}, node count={})",
                    edge.sourceNodeIndex(), edge.targetNodeIndex(), nodesByIndex.size());
            return;
        }
        FlowPortView sourcePort = flowPortAt(sourceView.getFlowOutPorts(), edge.sourcePortIndex());
        FlowPortView targetPort = flowPortAt(targetView.getFlowInPorts(), edge.targetPortIndex());
        if (sourcePort == null || targetPort == null) {
            log.warn("Skipping flow edge with out-of-range port index (sourcePort={}, targetPort={}) between nodes {} and {}",
                    edge.sourcePortIndex(), edge.targetPortIndex(), edge.sourceNodeIndex(), edge.targetNodeIndex());
            return;
        }
        FlowEdgeView flowEdgeView = createFlowEdge(sourcePort, targetPort);
        flowEdgeView.setWaypoints(offsetPoints(edge.waypoints(), offsetX, offsetY));
    }

    /**
     * The node at {@code index}, or null if it doesn't resolve - either the index is out of range,
     * or the slot is a null placeholder left by a node that couldn't be built (see {@link #place}).
     * Both mean "a save file's edge references a node that's no longer there", so both skip the edge.
     */
    private static NodeView nodeAt(List<NodeView> nodes, int index) {
        return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
    }

    /** The port at {@code index}, or null if out of range (a save file's edge referencing a port the node no longer has). */
    private static PortView portAt(List<PortView> ports, int index) {
        return index >= 0 && index < ports.size() ? ports.get(index) : null;
    }

    /** Shifts each waypoint by the paste offset, so a pasted edge's routing lands relative to its pasted nodes (offset is 0 for save/load). */
    private static List<Point2D> offsetPoints(List<Point2D> points, double offsetX, double offsetY) {
        List<Point2D> shifted = new ArrayList<>(points.size());
        for (Point2D point : points) {
            shifted.add(new Point2D(point.getX() + offsetX, point.getY() + offsetY));
        }
        return shifted;
    }

    /** The flow port at {@code index}, or null if out of range (e.g. a save file from when the node had a different port count). */
    private static FlowPortView flowPortAt(List<FlowPortView> ports, int index) {
        return index >= 0 && index < ports.size() ? ports.get(index) : null;
    }

    /** Snapshots the currently selected nodes (works for a single selected node too). */
    private void copySelection() {
        if (selectedNodes.isEmpty()) {
            return;
        }
        GraphSnapshot snapshot = snapshotOf(selectedNodes);
        clipboardNodes = snapshot.nodes();
        clipboardDataEdges = snapshot.dataEdges();
        clipboardFlowEdges = snapshot.flowEdges();
        pasteOffsetStep = 0;
    }

    /**
     * Duplicates whatever's on the clipboard (fresh {@link BaseNode} instances, via
     * {@link NodeRegistry#duplicate}), offset from the original copy position so pastes
     * don't land exactly on top of their source. Repeated pastes without an intervening
     * copy step further each time. The pasted nodes become the new selection.
     */
    private void pasteClipboard() {
        if (clipboardNodes.isEmpty()) {
            return;
        }
        double offset = 30 + pasteOffsetStep * 20;
        pasteOffsetStep++;

        GraphSnapshot snapshot = new GraphSnapshot(clipboardNodes, clipboardDataEdges, clipboardFlowEdges);
        undoManager.execute(new PasteCommand(this, snapshot, offset, offset));
    }

    /** Clears the current selection and selects exactly the given nodes - e.g. what a paste selects afterward. */
    public void selectOnly(Collection<NodeView> nodes) {
        clearSelection();
        for (NodeView nodeView : nodes) {
            selectNode(nodeView);
        }
    }

    /** Everything currently on the canvas, in the same shape used for copy/paste — for save-to-file. */
    public GraphSnapshot snapshotAll() {
        return snapshotOf(nodeViews);
    }

    /**
     * Replaces the canvas's entire contents with a snapshot (e.g. loaded from file).
     * Not itself undoable, and wipes prior undo history - loading a different graph is
     * a new-document boundary, not an edit you'd undo back through.
     */
    public void loadSnapshot(GraphSnapshot snapshot) {
        clearAll();
        place(snapshot, ClipboardNode::node, 0, 0);
        undoManager.clear();
    }

    // --- Canvas panning (middle-click) / rubber-band selection (left-click) --------

    private void handleCanvasPressed(MouseEvent event) {
        requestFocus();
        if (event.getButton() == MouseButton.MIDDLE) {
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
        if (event.getButton() == MouseButton.MIDDLE) {
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

    // --- Right-click context menu ----------------------------------------------------

    private void handleContextMenuRequested(ContextMenuEvent event) {
        pendingDropPoint = content.sceneToLocal(event.getSceneX(), event.getSceneY());
        contextMenu.hide();
        contextMenu.show(this, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    /**
     * Root right-click menu. Node types live under a single "Add Node" submenu rather
     * than at the top level, so other unrelated actions can be added alongside it here
     * later without getting mixed in among the (potentially long) list of node types.
     */
    private ContextMenu buildContextMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getItems().add(buildAddNodeMenu());
        return menu;
    }

    private Menu buildAddNodeMenu() {
        Menu addNodeMenu = new Menu("Add Node");
        Map<String, Menu> categoryMenus = new TreeMap<>();

        for (NodeRegistry.Entry entry : NodeRegistry.discover()) {
            MenuItem item = new MenuItem(entry.displayName());
            item.setOnAction(event -> {
                BaseNode instance = NodeRegistry.instantiate(entry.nodeClass());
                if (instance != null) {
                    NodeView nodeView = new NodeView(instance, content, this);
                    undoManager.execute(new AddNodeCommand(this, nodeView, pendingDropPoint.getX(), pendingDropPoint.getY()));
                }
            });

            Menu categoryMenu = resolveCategoryMenu(addNodeMenu, categoryMenus, entry.categoryPath());
            (categoryMenu == null ? addNodeMenu.getItems() : categoryMenu.getItems()).add(item);
        }

        if (addNodeMenu.getItems().isEmpty()) {
            MenuItem none = new MenuItem("(no node types found)");
            none.setDisable(true);
            addNodeMenu.getItems().add(none);
        }
        return addNodeMenu;
    }

    /** Finds (creating as needed) the Menu for a dot-separated category path, nesting under its parent categories. */
    private Menu resolveCategoryMenu(Menu root, Map<String, Menu> categoryMenus, String categoryPath) {
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
            if (connection.intersects(rect)) {
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
