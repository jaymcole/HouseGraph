package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.ui.command.AddNodeCommand;
import io.github.jaymcole.housegraph.ui.command.Command;
import io.github.jaymcole.housegraph.ui.command.CompositeCommand;
import io.github.jaymcole.housegraph.ui.command.CreateEdgeCommand;
import io.github.jaymcole.housegraph.ui.command.CreateFlowEdgeCommand;
import io.github.jaymcole.housegraph.ui.command.MoveNodesCommand;
import io.github.jaymcole.housegraph.ui.command.PasteCommand;
import io.github.jaymcole.housegraph.ui.command.RemoveNodesCommand;
import io.github.jaymcole.housegraph.ui.command.SetWaypointsCommand;
import io.github.jaymcole.housegraph.ui.command.UndoManager;
import io.github.jaymcole.housegraph.ui.view.AbstractEdgeView;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
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

import io.github.jaymcole.housegraph.loader.GraphLoader;
import io.github.jaymcole.housegraph.loader.LoadedDataEdge;
import io.github.jaymcole.housegraph.loader.LoadedFlowEdge;
import io.github.jaymcole.housegraph.loader.LoadedGraph;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.GraphExecutionListener;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.nodes.MissingNode;
import io.github.jaymcole.housegraph.graph.TypeConverters;
import io.github.jaymcole.housegraph.graph.TypeConverters.ConversionSafety;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.search.NodeDescriptor;
import io.github.jaymcole.housegraph.search.NodeSearchIndex;
import io.github.jaymcole.housegraph.search.SearchResult;
import javafx.application.Platform;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
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
 * the cursor. Left-click-drag on empty canvas space rubber-band-selects nodes/edges,
 * including individual edge waypoint handles caught by the band — dragging a selected
 * node then carries any selected waypoints along with it, as one undo step; right-click
 * opens a menu led by a ranked node search box, focused immediately; it shows
 * no results until you type, with the categorised "Add Node" menu kept below it for
 * browsing. Delete/Backspace removes the current selection; Ctrl/Cmd+A selects
 * everything on the canvas; Ctrl/Cmd+C copies the selection and Ctrl/Cmd+V pastes it
 * at the cursor; Ctrl/Cmd+Z and Ctrl/Cmd+Shift+Z undo and redo (currently: adding a
 * node via the menu, and deleting nodes/connections - see {@link UndoManager}). Data edges are created by dragging from
 * one data port's circle to another; flow edges by dragging between the triangular
 * flow anchors at the top corners of each node.
 * <p>
 * Each of those editing commands, plus the zoom commands, is also a public method, because
 * the application's menu bar drives the same ones — see {@code ui/menu/MainMenuBar}.
 */
public class GraphCanvas extends Pane implements NodeView.DragController, GraphExecutionListener, EdgeInteractionListener {

    private static final Logger log = Log.get(GraphCanvas.class);

    private static final KeyCodeCombination COPY_COMBO = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination PASTE_COMBO = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination UNDO_COMBO = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination REDO_COMBO =
            new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCodeCombination SELECT_ALL_COMBO =
            new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);

    /** Extra offset per repeated paste at the same spot, so stacked pastes stay distinguishable. */
    private static final double PASTE_CASCADE_STEP = 20;
    /** Offset used when the pointer is off-canvas and a paste has no cursor to anchor to. */
    private static final double PASTE_FALLBACK_OFFSET = 30;

    /** Unzoomed and unpanned — what {@link #withComponentIsolated} renders at. */
    private static final CameraState IDENTITY_CAMERA = new CameraState(1.0, 0, 0);

    /** Zoom limits, shared by scroll zoom, the menu's zoom commands and a camera restored from a save file. */
    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 3.0;
    /** One zoom step: what a scroll notch applies, and what one Zoom In / Zoom Out applies. */
    private static final double ZOOM_STEP = 1.1;
    /** Blank canvas left around the graph by {@link #zoomToFit}, in unzoomed pixels. */
    private static final double FIT_MARGIN = 40;

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
    /** Edge waypoints currently rubber-band-selected, so they translate along with a node drag. */
    private final Map<AbstractEdgeView, Set<Integer>> selectedWaypoints = new HashMap<>();

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
    private final TextField nodeSearchField;
    private final NodeRegistry nodeRegistry;
    private final NodeSearchIndex nodeSearchIndex;
    private Menu addNodeMenu;
    private Point2D pendingDropPoint = Point2D.ZERO;

    private List<ClipboardNode> clipboardNodes = List.of();
    private List<ClipboardDataEdge> clipboardDataEdges = List.of();
    private List<ClipboardFlowEdge> clipboardFlowEdges = List.of();
    /** How many pastes have already landed at the current anchor - each one steps further, so repeats don't stack. */
    private int pasteOffsetStep = 0;
    /** Content-coordinate point the last paste anchored to, or null if it fell back to a fixed offset. */
    private Point2D lastPasteAnchor;

    private double cursorSceneX;
    private double cursorSceneY;
    private boolean cursorOverCanvas;

    private final UndoManager undoManager = new UndoManager();

    /**
     * @param libraryNames maps a node library's id to its human name, for the node search box; see
     *                     {@link NodeSearchIndex}. May be null.
     */
    public GraphCanvas(NodeGraph graph, NodeRegistry nodeRegistry, Function<String, String> libraryNames) {
        this.graph = graph;
        this.nodeRegistry = nodeRegistry;
        this.nodeSearchIndex = new NodeSearchIndex(nodeRegistry, libraryNames);
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
        // The menu object is built once and kept, but its contents are not fixed for the life of
        // the session: installing or removing a node library changes the set of node types, and
        // reloadNodeTypes() refills it in place.
        addNodeMenu = buildAddNodeMenu();
        nodeSearchField = new TextField();
        nodeSearchField.setPromptText("Search nodes…");
        // Rebuilds the result rows below the search field on every keystroke. The field's own
        // CustomMenuItem is never recreated (see buildContextMenu), so retyping never steals focus
        // from itself the way rebuilding the whole menu each keystroke would.
        nodeSearchField.textProperty().addListener((obs, oldText, newText) -> updateSearchResults(newText));
        nodeSearchField.addEventHandler(KeyEvent.KEY_PRESSED, this::handleSearchFieldKeyPressed);
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

        // Where the cursor is, tracked for paste. Filters rather than handlers: the pointer
        // spends most of its time over a NodeView or a port, whose own handlers consume the
        // event before it could ever bubble back up to this canvas.
        addEventFilter(MouseEvent.MOUSE_MOVED, this::recordCursorPosition);
        addEventFilter(MouseEvent.MOUSE_DRAGGED, this::recordCursorPosition);
        addEventFilter(MouseEvent.MOUSE_PRESSED, this::recordCursorPosition);
        // Fires only when the pointer leaves this Pane entirely - moving onto a child sends
        // MOUSE_EXITED_TARGET instead, which this deliberately doesn't listen for.
        addEventFilter(MouseEvent.MOUSE_EXITED, event -> cursorOverCanvas = false);

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
            } else if (SELECT_ALL_COMBO.match(event)) {
                selectAll();
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
                    candidate.setDragCandidateSafety(connectionSafety(port, candidate));
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
                candidate.setDragCandidateSafety(null);
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
        return connectionSafety(a, b) != ConversionSafety.INCOMPATIBLE;
    }

    /**
     * How faithful (or impossible) a data connection between two ports would be, used both to gate a
     * drag and to colour candidate anchors. A same-owner / same-direction pairing can never be an
     * edge, so it is {@link ConversionSafety#INCOMPATIBLE}. Otherwise the source's value flows into
     * the input, so the safety is {@code TypeConverters.classify(output type, input type)} — exact
     * matches and an {@code Object} input read {@code SAFE}, a hidden converter contributes its own
     * level, and an unbridgeable pair is {@code INCOMPATIBLE}. Mirrors {@code NodeGraph.attachEdge}.
     */
    private ConversionSafety connectionSafety(PortView a, PortView b) {
        if (a.getOwner() == b.getOwner() || a.getDirection() == b.getDirection()) {
            return ConversionSafety.INCOMPATIBLE;
        }
        PortView output = a.getDirection() == PortView.Direction.OUTPUT ? a : b;
        PortView input = output == a ? b : a;
        return TypeConverters.classify(output.getVariable().type, input.getVariable().type);
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

        // Before registering, not after: an input can only ever be fed by one edge, and the graph
        // refuses a second one, so the edge being replaced has to leave the model first.
        detachEdgeViewsTargeting(inputPort);

        Edge edge = new Edge(
                outputPort.getOwner().getNode(), outputPort.getVariable(),
                inputPort.getOwner().getNode(), inputPort.getVariable());
        graph.registerEdge(edge);
        return attachEdgeView(edge, outputPort, inputPort);
    }

    /**
     * Builds the view for an {@link Edge} that is <em>already</em> registered on the graph, and
     * connects the ports it runs between. The view half of {@link #createEdge}, on its own, for the
     * one caller that wires the model itself: {@link GraphLoader}, whose edges arrive pre-registered
     * (see {@link #place}). Registering here as well would wire every loaded edge twice.
     */
    private EdgeView attachEdgeView(Edge edge, PortView outputPort, PortView inputPort) {
        detachEdgeViewsTargeting(inputPort);
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

    /** Deletes whatever edge currently feeds {@code inputPort} — model and view both, via the view's delete callback. */
    private void detachEdgeViewsTargeting(PortView inputPort) {
        for (EdgeView existing : new ArrayList<>(edgeViews.values())) {
            if (existing.hasTarget(inputPort)) {
                existing.delete();
            }
        }
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

    /**
     * Whether a drag from {@code a} to {@code b} may become a flow edge: opposite directions on
     * different nodes, and not a pair these two ports are already joined by. A flow-in port accepts
     * any number of edges (see {@link NodeGraph}), so the only thing left to rule out is wiring the
     * very same pair twice, which would add a second identical edge that changes nothing but does
     * inflate a flow join's arrival count. Mirrors {@link #findFlowEdge}, the same way the data-port
     * drag check mirrors {@code NodeGraph.attachEdge}.
     */
    private boolean isValidFlowConnection(FlowPortView a, FlowPortView b) {
        return a.getOwner() != b.getOwner()
                && a.getDirection() != b.getDirection()
                && findFlowEdge(a, b) == null;
    }

    /**
     * The live flow edge joining this exact pair of ports, or null. Direction-agnostic in its
     * arguments, like {@link #createFlowEdge}. This is a pair lookup, not a "what feeds this port"
     * one: several edges may legitimately target a single flow-in port.
     */
    private FlowEdgeView findFlowEdge(FlowPortView a, FlowPortView b) {
        FlowPortView outPort = a.getDirection() == FlowPort.Direction.OUT ? a : b;
        FlowPortView inPort = a.getDirection() == FlowPort.Direction.OUT ? b : a;
        for (FlowEdgeView flowEdgeView : flowEdgeViews.values()) {
            if (flowEdgeView.getSourcePort() == outPort && flowEdgeView.getTargetPort() == inPort) {
                return flowEdgeView;
            }
        }
        return null;
    }

    /**
     * Wires {@code a} to {@code b} as a flow edge, in either argument order, and returns its view.
     * <p>
     * A flow-in port may be fed by several edges — two triggers wired into one Start port, either
     * one firing it — so a new edge is added <em>alongside</em> whatever already feeds the port
     * rather than replacing it. (A data input keeps its one-source restriction: its value has to
     * come from somewhere unambiguous, which a valueless flow port has no equivalent of.) Wiring the
     * identical pair twice is still pointless, so an existing edge between exactly these two ports
     * is returned as-is rather than duplicated; the drag-time {@link #isValidFlowConnection} check
     * already refuses that gesture, leaving this for the load/paste/rebuild paths.
     */
    public FlowEdgeView createFlowEdge(FlowPortView a, FlowPortView b) {
        FlowPortView outPort = a.getDirection() == FlowPort.Direction.OUT ? a : b;
        FlowPortView inPort = a.getDirection() == FlowPort.Direction.OUT ? b : a;

        FlowEdgeView duplicate = findFlowEdge(outPort, inPort);
        if (duplicate != null) {
            return duplicate;
        }

        FlowEdge flowEdge = new FlowEdge(
                outPort.getOwner().getNode(), outPort.getFlowPort(),
                inPort.getOwner().getNode(), inPort.getFlowPort());
        graph.registerFlowEdge(flowEdge);
        return attachFlowEdgeView(flowEdge, outPort, inPort);
    }

    /**
     * Builds the view for a {@link FlowEdge} already registered on the graph — the flow counterpart
     * of {@link #attachEdgeView}, and for the same caller.
     */
    private FlowEdgeView attachFlowEdgeView(FlowEdge flowEdge, FlowPortView outPort, FlowPortView inPort) {
        FlowEdgeView existing = flowEdgeViews.get(flowEdge);
        if (existing != null) {
            // The loader reuses one FlowEdge for a pair saved twice; so does its view.
            return existing;
        }

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
    private Map<AbstractEdgeView, List<Point2D>> dragGestureWaypointsBefore;

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

        // Any waypoints picked up by a rubber-band alongside these nodes ride along with
        // the drag too; snapshot their "before" routes the same way, for undo.
        dragGestureWaypointsBefore = new HashMap<>();
        for (Map.Entry<AbstractEdgeView, Set<Integer>> entry : selectedWaypoints.entrySet()) {
            dragGestureWaypointsBefore.put(entry.getKey(), entry.getKey().getWaypoints());
        }
    }

    @Override
    public void onNodeDragged(double deltaContentX, double deltaContentY) {
        for (NodeView node : selectedNodes) {
            node.setLayoutX(node.getLayoutX() + deltaContentX);
            node.setLayoutY(node.getLayoutY() + deltaContentY);
        }
        for (Map.Entry<AbstractEdgeView, Set<Integer>> entry : selectedWaypoints.entrySet()) {
            entry.getKey().translateWaypoints(entry.getValue(), deltaContentX, deltaContentY);
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
        List<Command> moves = new ArrayList<>();
        if (moved) {
            moves.add(new MoveNodesCommand(dragGestureNodes, dragGestureStartX, dragGestureStartY, endX, endY));
        }
        for (Map.Entry<AbstractEdgeView, List<Point2D>> entry : dragGestureWaypointsBefore.entrySet()) {
            List<Point2D> before = entry.getValue();
            List<Point2D> after = entry.getKey().getWaypoints();
            if (!before.equals(after)) {
                moves.add(new SetWaypointsCommand(entry.getKey(), before, after));
            }
        }
        if (moves.size() == 1) {
            undoManager.record(moves.get(0));
        } else if (moves.size() > 1) {
            undoManager.record(new CompositeCommand(moves));
        }
        dragGestureNodes = null;
        dragGestureWaypointsBefore = null;
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
        // A structural edit (a waypoint added or removed) shifts indices, so any
        // rubber-band waypoint selection on this edge no longer points at the right
        // points - drop it rather than risk it going stale.
        if (before.size() != after.size() && selectedWaypoints.remove(edge) != null) {
            edge.clearWaypointSelection();
        }
    }

    /**
     * Package-visible (not just private) so a Command can make sure whatever it removes
     * doesn't linger in the selection. Also drops any rubber-band-selected waypoints on
     * this connection — safe even though {@link #updateLiveSelection} calls this every
     * frame the curve drifts out of the rubber-band rect, because that same call
     * re-selects any waypoints still inside the rect right after (see there).
     */
    public void deselectConnection(ConnectionView connection) {
        if (selectedConnections.remove(connection)) {
            connection.setSelected(false);
        }
        if (connection instanceof AbstractEdgeView edgeView && selectedWaypoints.remove(edgeView) != null) {
            edgeView.clearWaypointSelection();
        }
    }

    private void clearSelection() {
        for (NodeView node : new ArrayList<>(selectedNodes)) {
            deselectNode(node);
        }
        for (ConnectionView connection : new ArrayList<>(selectedConnections)) {
            deselectConnection(connection);
        }
        // A connection can hold selected waypoints without the connection itself being
        // selected (a rubber-band that caught an anchor but not the curve), so this needs
        // its own sweep rather than riding along with the loop above.
        for (AbstractEdgeView edge : new ArrayList<>(selectedWaypoints.keySet())) {
            edge.clearWaypointSelection();
        }
        selectedWaypoints.clear();
    }

    private List<ConnectionView> allConnections() {
        List<ConnectionView> all = new ArrayList<>(edgeViews.values());
        all.addAll(flowEdgeViews.values());
        return all;
    }

    /** Undoable delete of the current selection: selected nodes, plus every connection touching one, plus any standalone selected connection. */
    public void deleteSelected() {
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
     * <p>
     * The graph half of the work — building the nodes, registering them, resolving every saved
     * edge by index and wiring it — belongs to {@link GraphLoader} and happens with no canvas
     * involved; see {@code docs/engine/architecture.md}. What is left here is the view: a
     * {@link NodeView} per node, and an edge view per edge the loader resolved, carrying the
     * routing waypoints the snapshot saved (which the engine has no place for).
     */
    public List<NodeView> place(GraphSnapshot snapshot, Function<ClipboardNode, BaseNode> nodeFactory, double offsetX, double offsetY) {
        // Only the real views, so callers that select/undo the result never see a null. The loader
        // keeps the index-aligned list, holes included, and resolves the saved edges against it.
        List<NodeView> placed = new ArrayList<>();

        LoadedGraph loaded = GraphLoader.load(snapshot, nodeFactory, graph, (entry, node) -> {
            // Called before the node joins the graph, which is where NodeGraph.addNode fires
            // onActivated() - so the view, and with it createNodeContent(), is built and on the
            // canvas by the time the node activates, as it was when this method added nodes itself.
            NodeView nodeView = new NodeView(node, content, this);
            addNodeView(nodeView, entry.x() + offsetX, entry.y() + offsetY);
            placed.add(nodeView);
        });

        // A pass of its own, after every node is activated: a node that reconfigures its own ports
        // from onActivated() would otherwise rebuild the view we are still holding here.
        for (NodeView nodeView : placed) {
            BaseNode node = nodeView.getNode();
            node.setPortsChangedListener(() -> rebuildNodeView(node));
        }

        forceLayout();

        // Each edge view is built in isolation, mirroring the loader's per-edge isolation on the
        // model side: a port a view somehow cannot offer costs that one edge its curve, nothing more.
        for (LoadedDataEdge loadedEdge : loaded.dataEdges()) {
            try {
                showDataEdge(loadedEdge, offsetX, offsetY);
            } catch (RuntimeException e) {
                log.warn("Skipping data edge whose view could not be built: {}", e.toString());
            }
        }

        for (LoadedFlowEdge loadedEdge : loaded.flowEdges()) {
            try {
                showFlowEdge(loadedEdge, offsetX, offsetY);
            } catch (RuntimeException e) {
                log.warn("Skipping flow edge whose view could not be built: {}", e.toString());
            }
        }

        return placed;
    }

    /**
     * Draws one edge {@link GraphLoader} already wired, and applies the routing the snapshot saved
     * for it. Ports are matched by identity against the node's own views rather than re-resolved by
     * index, so the port order a {@link NodeView} happens to render in cannot rewire a saved graph.
     */
    private void showDataEdge(LoadedDataEdge loaded, double offsetX, double offsetY) {
        Edge edge = loaded.edge();
        PortView sourcePort = portFor(edge.getSourceNode(), edge.getSourceVariable(), PortView.Direction.OUTPUT);
        PortView targetPort = portFor(edge.getTargetNode(), edge.getTargetVariable(), PortView.Direction.INPUT);
        if (sourcePort == null || targetPort == null) {
            log.warn("No port view for loaded data edge between {} and {}",
                    edge.getSourceNode().getName(), edge.getTargetNode().getName());
            return;
        }
        attachEdgeView(edge, sourcePort, targetPort)
                .setWaypoints(offsetPoints(loaded.entry().waypoints(), offsetX, offsetY));
    }

    /** Flow-edge counterpart of {@link #showDataEdge}. */
    private void showFlowEdge(LoadedFlowEdge loaded, double offsetX, double offsetY) {
        FlowEdge flowEdge = loaded.edge();
        FlowPortView sourcePort = flowPortFor(flowEdge.getSourceNode(), flowEdge.getSourcePort(), FlowPort.Direction.OUT);
        FlowPortView targetPort = flowPortFor(flowEdge.getTargetNode(), flowEdge.getTargetPort(), FlowPort.Direction.IN);
        if (sourcePort == null || targetPort == null) {
            log.warn("No flow port view for loaded flow edge between {} and {}",
                    flowEdge.getSourceNode().getName(), flowEdge.getTargetNode().getName());
            return;
        }
        attachFlowEdgeView(flowEdge, sourcePort, targetPort)
                .setWaypoints(offsetPoints(loaded.entry().waypoints(), offsetX, offsetY));
    }

    /** The view of one of a node's data ports, or null if the node isn't on this canvas (or its view has no such port). */
    private PortView portFor(BaseNode node, NodeVariable variable, PortView.Direction direction) {
        NodeView nodeView = nodeViewByNode.get(node);
        if (nodeView == null) {
            return null;
        }
        for (PortView port : direction == PortView.Direction.OUTPUT ? nodeView.getOutputPorts() : nodeView.getInputPorts()) {
            if (port.getVariable() == variable) {
                return port;
            }
        }
        return null;
    }

    /** Flow counterpart of {@link #portFor}. */
    private FlowPortView flowPortFor(BaseNode node, FlowPort flowPort, FlowPort.Direction direction) {
        NodeView nodeView = nodeViewByNode.get(node);
        if (nodeView == null) {
            return null;
        }
        for (FlowPortView port : direction == FlowPort.Direction.OUT ? nodeView.getFlowOutPorts() : nodeView.getFlowInPorts()) {
            if (port.getFlowPort() == flowPort) {
                return port;
            }
        }
        return null;
    }

    /** Shifts each waypoint by the paste offset, so a pasted edge's routing lands relative to its pasted nodes (offset is 0 for save/load). */
    private static List<Point2D> offsetPoints(List<Point2D> points, double offsetX, double offsetY) {
        List<Point2D> shifted = new ArrayList<>(points.size());
        for (Point2D point : points) {
            shifted.add(new Point2D(point.getX() + offsetX, point.getY() + offsetY));
        }
        return shifted;
    }

    /** Snapshots the currently selected nodes (works for a single selected node too). */
    public void copySelection() {
        if (selectedNodes.isEmpty()) {
            return;
        }
        // A MissingNode is a preserved save-file blob, not a working node. Duplicating one would call
        // its no-arg constructor and yield an empty placeholder with nothing behind it, so copying it
        // has no value. Filtering here beats adding a duplicate-yourself hook to BaseNode that every
        // node author would then have to understand, for this one case.
        Set<NodeView> copyable = new LinkedHashSet<>();
        int skipped = 0;
        for (NodeView view : selectedNodes) {
            if (view.getNode() instanceof MissingNode) {
                skipped++;
            } else {
                copyable.add(view);
            }
        }
        if (skipped > 0) {
            log.info("Not copying {} placeholder node(s) for uninstalled types", skipped);
        }
        if (copyable.isEmpty()) {
            return;
        }
        GraphSnapshot snapshot = snapshotOf(copyable);
        clipboardNodes = snapshot.nodes();
        clipboardDataEdges = snapshot.dataEdges();
        clipboardFlowEdges = snapshot.flowEdges();
        pasteOffsetStep = 0;
        lastPasteAnchor = null;
    }

    /**
     * Duplicates whatever's on the clipboard (fresh {@link BaseNode} instances, via
     * {@link NodeRegistry#duplicate}) at the cursor: the copied nodes keep their layout
     * relative to each other, and the top-left corner of that group lands under the pointer.
     * With the pointer off the canvas (say the paste came straight after a copy done from the
     * keyboard) there is nowhere to aim, so it falls back to a fixed offset from the copy
     * position - which still keeps the paste off the top of its source. Pasting repeatedly
     * without moving the pointer steps each copy further, so they don't stack. The pasted
     * nodes become the new selection.
     */
    public void pasteClipboard() {
        if (clipboardNodes.isEmpty()) {
            return;
        }
        Point2D anchor = cursorContentPoint();
        if (anchor != null && !anchor.equals(lastPasteAnchor)) {
            pasteOffsetStep = 0;
        }
        double cascade = pasteOffsetStep * PASTE_CASCADE_STEP;
        pasteOffsetStep++;
        lastPasteAnchor = anchor;

        double offsetX;
        double offsetY;
        if (anchor != null) {
            double minX = clipboardNodes.stream().mapToDouble(ClipboardNode::x).min().orElse(0);
            double minY = clipboardNodes.stream().mapToDouble(ClipboardNode::y).min().orElse(0);
            offsetX = anchor.getX() - minX + cascade;
            offsetY = anchor.getY() - minY + cascade;
        } else {
            offsetX = PASTE_FALLBACK_OFFSET + cascade;
            offsetY = PASTE_FALLBACK_OFFSET + cascade;
        }

        GraphSnapshot snapshot = new GraphSnapshot(clipboardNodes, clipboardDataEdges, clipboardFlowEdges);
        undoManager.execute(new PasteCommand(this, snapshot, offsetX, offsetY));
    }

    private void recordCursorPosition(MouseEvent event) {
        cursorSceneX = event.getSceneX();
        cursorSceneY = event.getSceneY();
        cursorOverCanvas = true;
    }

    /** Where the pointer is in content (canvas) coordinates, or null if it isn't over the canvas. */
    private Point2D cursorContentPoint() {
        return cursorOverCanvas ? content.sceneToLocal(cursorSceneX, cursorSceneY) : null;
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
     * <p>
     * After the whole graph is in place — every node built and activated, every edge wired —
     * any {@link AutoStartable} node that was running when the graph was saved is resumed (its
     * Start/Connect path re-run). This happens here, not on plain node placement, so it fires
     * only on a load: paste and undo/redo never auto-start a copied resource.
     */
    public void loadSnapshot(GraphSnapshot snapshot) {
        clearAll();
        List<NodeView> placed = place(snapshot, ClipboardNode::node, 0, 0);
        undoManager.clear();
        resumeRunningNodes(placed);
    }

    /**
     * Resumes any just-loaded node that was running when its graph was saved. Runs after
     * {@link #place} so the node's {@code onActivated()} and all its incoming edges are already
     * in place — a node that pulls an input at Start (the web server's {@code Store}) sees its
     * wiring. Each node decides whether to actually start via {@link AutoStartable#autoStartIfWasRunning()}.
     */
    private void resumeRunningNodes(List<NodeView> placed) {
        for (NodeView nodeView : placed) {
            if (nodeView.getNode() instanceof AutoStartable autoStartable) {
                autoStartable.autoStartIfWasRunning();
            }
        }
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
     * Root right-click menu: a ranked node search box, its live results, then the categorised
     * "Add Node" menu underneath for browsing by folder. The search field's {@link CustomMenuItem}
     * is created once and kept at index 0 for the life of the menu — {@link #updateSearchResults}
     * only ever replaces the items after it, never that one, because rebuilding it would tear the
     * live {@link TextField} out of the scene graph and drop its focus on every keystroke.
     */
    private ContextMenu buildContextMenu() {
        ContextMenu menu = new ContextMenu();
        CustomMenuItem searchItem = new CustomMenuItem(nodeSearchField);
        // Otherwise clicking into the field to position the caret closes the menu.
        searchItem.setHideOnClick(false);
        menu.getItems().add(searchItem);

        // Reset to an empty query (and so no result rows) each time the menu is (re)opened, and
        // hand keyboard focus straight to the search field so typing works immediately.
        menu.setOnShowing(event -> {
            nodeSearchField.clear();
            updateSearchResults("");
        });
        menu.setOnShown(event -> Platform.runLater(nodeSearchField::requestFocus));

        updateSearchResultsIn(menu, "");
        return menu;
    }

    /** The registry backing this canvas's Add-Node menu; also what save/load resolves node types through. */
    public NodeRegistry getNodeRegistry() {
        return nodeRegistry;
    }

    /**
     * Rebuilds the node search index and the Add-Node menu from the node registry. Call after a
     * node library is installed, updated, removed, enabled or disabled — the set of node types is
     * no longer fixed for the life of the session, which is the only reason this method exists.
     */
    public void reloadNodeTypes() {
        nodeSearchIndex.invalidate();
        addNodeMenu = buildAddNodeMenu();
        updateSearchResults(nodeSearchField.getText());
    }

    /**
     * Re-ranks the search results shown below the search field against {@code query}, keeping the
     * search field itself and the trailing Add-Node menu in place.
     */
    private void updateSearchResults(String query) {
        updateSearchResultsIn(contextMenu, query);
    }

    /**
     * A blank query shows no result rows at all — an empty query is a browse-everything result in
     * {@link NodeSearchIndex}, and with a large or multi-library registry that turns the menu into
     * a scrollable wall of nodes on every right-click. Typing is what asks for results.
     */
    private void updateSearchResultsIn(ContextMenu menu, String query) {
        List<MenuItem> items = new ArrayList<>();
        items.add(menu.getItems().get(0));
        if (!query.isBlank()) {
            List<SearchResult> results = nodeSearchIndex.search(query);
            for (SearchResult result : results) {
                items.add(buildSearchResultItem(result.node()));
            }
            if (results.isEmpty()) {
                MenuItem none = new MenuItem("(no matching nodes)");
                none.setDisable(true);
                items.add(none);
            }
        }
        items.add(new SeparatorMenuItem());
        items.add(addNodeMenu);
        menu.getItems().setAll(items);
    }

    private MenuItem buildSearchResultItem(NodeDescriptor descriptor) {
        MenuItem item = new MenuItem(descriptor.displayName());
        item.setOnAction(event -> addNodeFromRegistry(descriptor.nodeClass()));
        return item;
    }

    /** Enter adds the top-ranked result and closes the menu; Escape just closes it. */
    private void handleSearchFieldKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            String query = nodeSearchField.getText();
            // A blank query has no result rows shown, so Enter on one must not silently add
            // whatever the browse-everything result would have ranked first.
            if (!query.isBlank()) {
                List<SearchResult> results = nodeSearchIndex.search(query);
                if (!results.isEmpty()) {
                    addNodeFromRegistry(results.get(0).node().nodeClass());
                }
            }
            contextMenu.hide();
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            contextMenu.hide();
            event.consume();
        }
    }

    private void addNodeFromRegistry(Class<? extends BaseNode> nodeClass) {
        BaseNode instance = NodeRegistry.instantiate(nodeClass);
        if (instance != null) {
            NodeView nodeView = new NodeView(instance, content, this);
            undoManager.execute(new AddNodeCommand(this, nodeView, pendingDropPoint.getX(), pendingDropPoint.getY()));
        }
    }

    /**
     * How many nodes currently on the canvas come from a given node library. Updating, disabling or
     * removing a library while any of its nodes are live needs a restart rather than a hot reload:
     * Java can't unload a class while instances exist, so those nodes would stay bound to the old
     * loader's {@code Class} objects and the same type would exist twice.
     *
     * @param pluginId the library id
     * @return the number of live nodes from it
     */
    public int countLiveNodesFrom(String pluginId) {
        int count = 0;
        for (NodeView nodeView : nodeViews) {
            if (nodeRegistry.pluginIdOf(nodeView.getNode().getClass()).equals(pluginId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether any node from **any** node library (as opposed to a built-in) is currently live on the
     * canvas. This is the gate for a node-library hot reload, not {@link #countLiveNodesFrom}: the
     * shared {@code PluginLoader} and {@code NodeRegistry} are rebuilt for every enabled library at
     * once, so a reload triggered by a change to one library still re-scans every other library's
     * classes too. A node from an unrelated library left live through that reload would end up bound
     * to a now-discarded {@code Class} object, which is exactly the failure a hot reload must avoid.
     *
     * @return true if at least one live node is not from {@link NodeRegistry#CORE_PLUGIN_ID}
     */
    public boolean hasLiveLibraryNodes() {
        for (NodeView nodeView : nodeViews) {
            if (!nodeRegistry.pluginIdOf(nodeView.getNode().getClass()).equals(NodeRegistry.CORE_PLUGIN_ID)) {
                return true;
            }
        }
        return false;
    }

    private Menu buildAddNodeMenu() {
        Menu menu = new Menu("Add Node");
        Map<String, Menu> categoryMenus = new TreeMap<>();

        for (NodeRegistry.Entry entry : nodeRegistry.discover()) {
            MenuItem item = new MenuItem(entry.displayName());
            item.setOnAction(event -> addNodeFromRegistry(entry.nodeClass()));

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
            // Recomputed from scratch against the current rect every call, so it doesn't
            // matter that deselectConnection() above may have just cleared this edge's
            // waypoint selection - a waypoint still (or newly) inside the rect goes right
            // back in, independently of whether the curve itself is caught by the band.
            if (connection instanceof AbstractEdgeView edgeView) {
                updateWaypointSelection(edgeView, rect);
            }
        }
    }

    /** Reconciles one edge's selected-waypoint indices against the live rubber-band rect. */
    private void updateWaypointSelection(AbstractEdgeView edge, Bounds rect) {
        Set<Integer> hits = new LinkedHashSet<>(edge.waypointIndicesIn(rect));
        Set<Integer> current = selectedWaypoints.getOrDefault(edge, Set.of());
        int waypointCount = edge.getWaypoints().size();
        for (int i = 0; i < waypointCount; i++) {
            boolean shouldSelect = hits.contains(i);
            if (shouldSelect != current.contains(i)) {
                edge.setWaypointSelected(i, shouldSelect);
            }
        }
        if (hits.isEmpty()) {
            selectedWaypoints.remove(edge);
        } else {
            selectedWaypoints.put(edge, hits);
        }
    }

    // --- Editor commands (the menu bar and the canvas shortcuts share these) ----

    /**
     * Undoes the last edit. Public because the Edit menu drives the same history the canvas's own
     * Ctrl/Cmd+Z does — there is one {@link UndoManager} per canvas and no second path into it.
     */
    public void undo() {
        undoManager.undo();
    }

    /** Redoes the last undone edit. */
    public void redo() {
        undoManager.redo();
    }

    /** Whether {@link #undo()} would do anything — for greying the menu item out. */
    public boolean canUndo() {
        return undoManager.canUndo();
    }

    /** Whether {@link #redo()} would do anything — for greying the menu item out. */
    public boolean canRedo() {
        return undoManager.canRedo();
    }

    /** Whether anything — node, connection or both — is selected. */
    public boolean hasSelection() {
        return !selectedNodes.isEmpty() || !selectedConnections.isEmpty();
    }

    /** Whether a previous copy left something {@link #pasteClipboard()} could place. */
    public boolean canPaste() {
        return !clipboardNodes.isEmpty();
    }

    /**
     * Selects every node and every connection on the canvas.
     *
     * <p>Connections are included, not just nodes, so Select All followed by Delete empties the
     * canvas in one step even when an edge's endpoints were themselves already gone.
     */
    public void selectAll() {
        clearSelection();
        for (NodeView node : nodeViews) {
            selectNode(node);
        }
        for (ConnectionView connection : allConnections()) {
            selectConnection(connection);
        }
        requestFocus();
    }

    /**
     * Empties the canvas and drops the undo history — the new-document boundary
     * {@link #loadSnapshot} crosses too, without a file behind it. Every node is removed properly,
     * so a live resource node shuts down rather than being abandoned.
     *
     * <p>Not undoable, and it does not prompt: the caller owns the "are you sure" (see {@code App}).
     */
    public void clearGraph() {
        clearAll();
        undoManager.clear();
        nodePlacementCounter = 0;
    }

    // --- Zoom -----------------------------------------------------------------

    private void handleZoom(ScrollEvent event) {
        double factor = event.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP;
        double newZoom = clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);

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

    /** One step in, anchored to the middle of the viewport rather than to the pointer. */
    public void zoomIn() {
        zoomAboutViewportCentre(zoom * ZOOM_STEP);
    }

    /** One step out, anchored to the middle of the viewport. */
    public void zoomOut() {
        zoomAboutViewportCentre(zoom / ZOOM_STEP);
    }

    /** Back to 1:1, keeping whatever is in the middle of the viewport in the middle. */
    public void resetZoom() {
        zoomAboutViewportCentre(1.0);
    }

    /**
     * Frames the whole graph: picks the largest zoom (within the usual limits) at which every node
     * fits with a margin, and centres it. Does nothing on an empty canvas, or before the canvas has
     * been laid out and so has no size to fit into.
     */
    public void zoomToFit() {
        if (nodeViews.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        // The bounds are pre-transform — the pan/zoom lives on `content` itself — so this is the
        // graph's own coordinate space, which is exactly what the new camera has to be derived in.
        Bounds bounds = content.getBoundsInLocal();
        if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            return;
        }
        double scale = clamp(Math.min((getWidth() - 2 * FIT_MARGIN) / bounds.getWidth(),
                (getHeight() - 2 * FIT_MARGIN) / bounds.getHeight()), MIN_ZOOM, MAX_ZOOM);
        zoom = scale;
        translateX = (getWidth() - bounds.getWidth() * scale) / 2 - bounds.getMinX() * scale;
        translateY = (getHeight() - bounds.getHeight() * scale) / 2 - bounds.getMinY() * scale;
        updateTransform();
    }

    /**
     * Sets the zoom while holding the content point currently at the viewport's centre in place —
     * the counterpart of {@link #handleZoom}, which holds the point under the pointer instead. A
     * menu command has no pointer position worth anchoring to.
     */
    private void zoomAboutViewportCentre(double requestedZoom) {
        double newZoom = clamp(requestedZoom, MIN_ZOOM, MAX_ZOOM);
        Point2D centre = content.parentToLocal(getWidth() / 2, getHeight() / 2);
        translateX += (zoom - newZoom) * centre.getX();
        translateY += (zoom - newZoom) * centre.getY();
        zoom = newZoom;
        updateTransform();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The graph this canvas hosts. Read-only use — mutate through the canvas so the views stay in step. */
    public NodeGraph getGraph() {
        return graph;
    }

    /**
     * Runs {@code renderer} against the content group with only {@code component}'s nodes and edges
     * visible, and puts the canvas back exactly as it was afterwards.
     *
     * <p>This is what {@code ui/export} renders one picture per distinct graph through, and each of
     * the three things it changes is load-bearing:
     *
     * <ul>
     *   <li><b>Non-member views are hidden</b>, not merely cropped around. Nothing constrains two
     *       disjoint components to occupy separate regions of the canvas — a user may lay one out
     *       right through the middle of another — so a crop to the component's bounding box would
     *       pull foreign nodes into its picture. Hiding also shrinks {@link Group#getLayoutBounds()}
     *       to just what remains, which is what gives the renderer its crop rectangle for free.</li>
     *   <li><b>The selection is cleared</b>, because {@code NodeView}'s selection border is a child
     *       of the node and would otherwise render into the image: whatever happened to be selected
     *       when the user hit Export would come out ringed in amber.</li>
     *   <li><b>Pan/zoom is reset to 1:1</b>, so the render doesn't depend on where the user had
     *       scrolled to, and so the content group's local coordinates and its parent's coincide —
     *       which is what lets the renderer derive its viewport from {@code getLayoutBounds()}.</li>
     * </ul>
     *
     * <p>All three are restored in a {@code finally}, so a renderer that throws still leaves a
     * usable canvas rather than a half-hidden one.
     *
     * <p>FX thread only.
     *
     * @param component the nodes to leave visible, typically one {@code GraphComponents} component
     * @param renderer  receives the content group; its return value is passed straight back
     */
    public <T> T withComponentIsolated(Set<BaseNode> component, Function<Group, T> renderer) {
        List<NodeView> hiddenNodes = new ArrayList<>();
        List<AbstractEdgeView> hiddenEdges = new ArrayList<>();
        List<NodeView> wasSelected = new ArrayList<>(selectedNodes);
        List<ConnectionView> wasSelectedConnections = new ArrayList<>(selectedConnections);
        CameraState wasCamera = getCameraState();

        try {
            clearSelection();
            setCameraState(IDENTITY_CAMERA);

            for (NodeView view : nodeViews) {
                if (!component.contains(view.getNode())) {
                    view.setVisible(false);
                    hiddenNodes.add(view);
                }
            }
            // Testing the source node alone is enough: a component is maximal, so an edge with one
            // endpoint inside it has both inside it.
            for (Map.Entry<Edge, EdgeView> entry : edgeViews.entrySet()) {
                if (!component.contains(entry.getKey().getSourceNode())) {
                    entry.getValue().setVisible(false);
                    hiddenEdges.add(entry.getValue());
                }
            }
            for (Map.Entry<FlowEdge, FlowEdgeView> entry : flowEdgeViews.entrySet()) {
                if (!component.contains(entry.getKey().getSourceNode())) {
                    entry.getValue().setVisible(false);
                    hiddenEdges.add(entry.getValue());
                }
            }

            // Force the pass that reflects the visibility changes in the group's bounds, rather
            // than leaving the renderer to measure a stale rectangle.
            content.applyCss();
            content.layout();

            return renderer.apply(content);
        } finally {
            for (NodeView view : hiddenNodes) {
                view.setVisible(true);
            }
            for (AbstractEdgeView view : hiddenEdges) {
                view.setVisible(true);
            }
            setCameraState(wasCamera);
            for (NodeView view : wasSelected) {
                selectNode(view);
            }
            for (ConnectionView connection : wasSelectedConnections) {
                selectConnection(connection);
            }
        }
    }

    /** The canvas's current pan/zoom, for a save file to carry alongside the graph (see {@code GraphFileIO}). */
    public CameraState getCameraState() {
        return new CameraState(zoom, translateX, translateY);
    }

    /** Restores a previously captured pan/zoom, e.g. on loading a save file. Zoom is clamped to the usual scroll-zoom range. */
    public void setCameraState(CameraState state) {
        zoom = clamp(state.zoom(), MIN_ZOOM, MAX_ZOOM);
        translateX = state.translateX();
        translateY = state.translateY();
        updateTransform();
    }
}
