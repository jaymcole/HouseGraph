package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.ui.command.AddGroupCommand;
import io.github.jaymcole.housegraph.ui.command.AddNodeCommand;
import io.github.jaymcole.housegraph.ui.command.Command;
import io.github.jaymcole.housegraph.ui.command.CompositeCommand;
import io.github.jaymcole.housegraph.ui.command.CreateEdgeCommand;
import io.github.jaymcole.housegraph.ui.command.CreateFlowEdgeCommand;
import io.github.jaymcole.housegraph.ui.command.MoveNodesCommand;
import io.github.jaymcole.housegraph.ui.command.PasteCommand;
import io.github.jaymcole.housegraph.ui.command.RemoveGroupsCommand;
import io.github.jaymcole.housegraph.ui.command.RemoveNodesCommand;
import io.github.jaymcole.housegraph.ui.command.SetGroupCommand;
import io.github.jaymcole.housegraph.ui.command.SetWaypointsCommand;
import io.github.jaymcole.housegraph.ui.command.UndoManager;
import io.github.jaymcole.housegraph.ui.view.AbstractEdgeView;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.ui.view.ConnectionView;
import io.github.jaymcole.housegraph.ui.view.EdgeInteractionListener;
import io.github.jaymcole.housegraph.ui.view.EdgeView;
import io.github.jaymcole.housegraph.ui.view.FlowEdgeView;
import io.github.jaymcole.housegraph.ui.view.FlowPortView;
import io.github.jaymcole.housegraph.ui.view.GroupView;
import io.github.jaymcole.housegraph.ui.view.NodeView;
import io.github.jaymcole.housegraph.ui.view.PortView;
import io.github.jaymcole.housegraph.saveformat.CameraState;
import io.github.jaymcole.housegraph.saveformat.ClipboardDataEdge;
import io.github.jaymcole.housegraph.saveformat.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import io.github.jaymcole.housegraph.saveformat.NodeGroup;

import io.github.jaymcole.housegraph.modules.ModuleBinding;
import io.github.jaymcole.housegraph.modules.ModuleDirectory;

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
import io.github.jaymcole.housegraph.search.GraphSearch;
import io.github.jaymcole.housegraph.search.NodeDescriptor;
import io.github.jaymcole.housegraph.search.NodeSearchIndex;
import io.github.jaymcole.housegraph.search.SearchResult;
import javafx.application.Platform;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Affine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * node then carries any selected waypoints along with it, as one undo step; Ctrl/Cmd+F opens a
 * find bar in the top-right corner that rings every matching node on the canvas in yellow (see
 * {@link io.github.jaymcole.housegraph.search.GraphSearch GraphSearch}), and Escape closes it;
 * right-click opens a menu led by a ranked node search box, focused immediately; it shows
 * no results until you type, with the categorised "Add Node" menu kept below it for
 * browsing and an "Add Module…" row under that — modules are not node types, so they
 * cannot be menu entries; see {@link ModuleReferenceAction}. Delete/Backspace removes
 * the current selection; Ctrl/Cmd+A selects everything on the canvas; Ctrl/Cmd+C copies the selection and Ctrl/Cmd+V pastes it
 * at the cursor; Ctrl/Cmd+Z and Ctrl/Cmd+Shift+Z undo and redo (currently: adding a
 * node via the menu, and deleting nodes/connections - see {@link UndoManager}). Data edges are created by dragging from
 * one data port's circle to another; flow edges by dragging between the triangular
 * flow anchors at the top corners of each node.
 * <p>
 * Each of those editing commands, plus the zoom commands, is also a public method, because
 * the application's menu bar drives the same ones — see {@code ui/menu/MainMenuBar}.
 */
public class GraphCanvas extends Pane implements NodeView.DragController, GroupView.GroupController,
        GraphExecutionListener, EdgeInteractionListener {

    private static final Logger log = Log.get(GraphCanvas.class);

    private static final KeyCodeCombination COPY_COMBO = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination PASTE_COMBO = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination UNDO_COMBO = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination REDO_COMBO =
            new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCodeCombination SELECT_ALL_COMBO =
            new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination FIND_COMBO = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCodeCombination GROUP_COMBO = new KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN);

    /** Gap between the find bar and the canvas's top and right edges, in screen pixels. */
    private static final double FIND_BAR_MARGIN = 12;

    /** Extra offset per repeated paste at the same spot, so stacked pastes stay distinguishable. */
    private static final double PASTE_CASCADE_STEP = 20;
    /** Offset used when the pointer is off-canvas and a paste has no cursor to anchor to. */
    private static final double PASTE_FALLBACK_OFFSET = 30;

    /** Unzoomed and unpanned — what {@link #withComponentIsolated} renders at. */
    private static final CameraState IDENTITY_CAMERA = new CameraState(1.0, 0, 0);

    /** Breathing room left between a fitted group frame and the nodes it wraps. */
    private static final double GROUP_PADDING = 24;

    /** Extra room above those nodes, so the frame's title bar sits over canvas rather than over a node. */
    private static final double GROUP_TITLE_HEADROOM = 44;

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

    /**
     * The group frames on the canvas, in creation order — <em>not</em> paint order, which
     * {@link #restackGroups()} derives from their sizes. Keeping this list stable is what makes a
     * re-save of an unchanged canvas byte-identical even after a resize reorders the painting.
     */
    private final List<GroupView> groupViews = new ArrayList<>();

    private final Set<NodeView> selectedNodes = new LinkedHashSet<>();
    private final Set<GroupView> selectedGroups = new LinkedHashSet<>();
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

    /** The find-in-graph bar: floats over the top-right corner, hidden until Ctrl/Cmd+F. */
    private final HBox findBar;
    private final TextField graphSearchField = new TextField();
    private final Label graphSearchCount = new Label();
    /** What the find bar is currently highlighting; blank whenever it is closed or empty. */
    private GraphSearch.Query searchQuery = GraphSearch.compile("");

    private final MenuItem addModuleItem;
    private final MenuItem addGroupItem;
    private final NodeRegistry nodeRegistry;
    /** Resolves the modules a loaded graph references; {@link ModuleDirectory#EMPTY} when none was given. */
    private final ModuleDirectory modules;
    private final NodeSearchIndex nodeSearchIndex;
    private Menu addNodeMenu;
    private Point2D pendingDropPoint = Point2D.ZERO;

    private List<ClipboardNode> clipboardNodes = List.of();
    private List<ClipboardDataEdge> clipboardDataEdges = List.of();
    private List<ClipboardFlowEdge> clipboardFlowEdges = List.of();
    private List<NodeGroup> clipboardGroups = List.of();
    /** How many pastes have already landed at the current anchor - each one steps further, so repeats don't stack. */
    private int pasteOffsetStep = 0;
    /** Content-coordinate point the last paste anchored to, or null if it fell back to a fixed offset. */
    private Point2D lastPasteAnchor;

    private double cursorSceneX;
    private double cursorSceneY;
    private boolean cursorOverCanvas;

    private final UndoManager undoManager = new UndoManager();

    /**
     * A canvas with no module support: every {@code ModuleNode} it loads stays unresolved and the
     * context menu offers no way to add one. For a caller with no module library to hand.
     *
     * @param libraryNames maps a node library's id to its human name, for the node search box; see
     *                     {@link NodeSearchIndex}. May be null.
     */
    public GraphCanvas(NodeGraph graph, NodeRegistry nodeRegistry, Function<String, String> libraryNames) {
        this(graph, nodeRegistry, libraryNames, ModuleDirectory.EMPTY, null);
    }

    /**
     * @param libraryNames maps a node library's id to its human name, for the node search box; see
     *                     {@link NodeSearchIndex}. May be null.
     * @param modules      resolves the modules a loaded graph references, so a module node comes back
     *                     runnable rather than unresolved; {@link ModuleDirectory#EMPTY} for none
     * @param moduleAdder  asks the user which module to reference; null leaves the item out of the
     *                     context menu, which is what a canvas with no library should show
     */
    public GraphCanvas(NodeGraph graph, NodeRegistry nodeRegistry, Function<String, String> libraryNames,
                       ModuleDirectory modules, ModuleReferenceAction moduleAdder) {
        this.graph = graph;
        this.nodeRegistry = nodeRegistry;
        this.modules = modules == null ? ModuleDirectory.EMPTY : modules;
        this.nodeSearchIndex = new NodeSearchIndex(nodeRegistry, libraryNames);
        // Built once and kept, like the Add-Node menu: updateSearchResultsIn re-adds the same item
        // rather than a new one on every keystroke.
        this.addModuleItem = buildAddModuleItem(moduleAdder);
        this.addGroupItem = buildAddGroupItem();
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
        // A child of this Pane rather than of `content`, so it floats over the canvas at a fixed
        // corner instead of panning and zooming with the graph. layoutChildren places it.
        findBar = buildFindBar();
        getChildren().add(findBar);
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
            if (FIND_COMBO.match(event)) {
                openFind();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE && findBar.isVisible()) {
                closeFind();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
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
            } else if (GROUP_COMBO.match(event)) {
                groupSelection();
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
        // Re-arms the node's present(...) sink. Ordinarily the view's constructor already did it,
        // but undoing a delete re-adds the same NodeView, whose sink removeNodeView cleared.
        nodeView.attachPresentation();
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

        // A node that arrives while a find is open — pasted, undone back in, or its view rebuilt —
        // is judged against the live query, so the highlighting never describes a stale canvas.
        // Unconditionally, not only while a find is running: undoing a delete restores the very
        // same NodeView, and one deleted while highlighted is still carrying that mark, which
        // closeFind could not clear because the view had already left nodeViews. A blank query
        // matches nothing, so this is what clears it.
        nodeView.setSearchMatch(searchQuery.matches(nodeView.getNode()));
        if (!searchQuery.isBlank()) {
            updateFindCount();
        }
    }

    public void removeNode(NodeView nodeView) {
        graph.removeNode(nodeView.getNode());
        removeNodeView(nodeView);
    }

    /** The view half of removing a node — used on its own when rebuilding a view (the node stays in the graph, so onRemoved must not fire). */
    private void removeNodeView(NodeView nodeView) {
        nodeView.detachPresentation();
        content.getChildren().remove(nodeView);
        nodeViews.remove(nodeView);
        nodeViewByNode.remove(nodeView.getNode());
        ports.removeAll(nodeView.getInputPorts());
        ports.removeAll(nodeView.getOutputPorts());
        flowPorts.removeAll(nodeView.getFlowInPorts());
        flowPorts.removeAll(nodeView.getFlowOutPorts());
        if (!searchQuery.isBlank() && nodeView.isSearchMatch()) {
            updateFindCount();
        }
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
    private List<GroupView> dragGestureGroups;
    private List<NodeGroup> dragGestureGroupStart;
    /** The frame state a resize/rename/recolour started from, captured for undo. */
    private NodeGroup frameEditBefore;
    /** Which waypoints the gesture carries, by edge — the rubber-band's, plus any inside a dragged frame. */
    private Map<AbstractEdgeView, Set<Integer>> dragGestureWaypoints;
    private Map<AbstractEdgeView, List<Point2D>> dragGestureWaypointsBefore;

    @Override
    public void onNodePressed(NodeView node) {
        requestFocus();
        if (!selectedNodes.contains(node)) {
            clearSelection();
            selectNode(node);
        }
        // Dragging a node never moves a frame: containment runs one way, from the frame to what is
        // inside it, so the nodes are the whole gesture here.
        beginDragGesture(selectedNodes, List.of());
    }

    @Override
    public void onNodeDragged(double deltaContentX, double deltaContentY) {
        applyDragDelta(deltaContentX, deltaContentY);
    }

    @Override
    public void onNodeReleased() {
        endDragGesture();
    }

    // --- GroupView.GroupController (group frames reporting back to the canvas) -----

    /**
     * A frame's title bar was pressed. Selects it if it wasn't already, then works out the whole
     * gesture: <b>every frame the selected frames command</b>, and <b>every node any of those frames
     * commands</b>, alongside anything already selected in its own right.
     *
     * <p>Nesting needs no recursion. A node inside a sub-frame is inside the enclosing frame too, by
     * the same containment test, so one pass over the frames being dragged already reaches
     * everything at every depth.
     */
    @Override
    public void onGroupPressed(GroupView group) {
        requestFocus();
        if (!selectedGroups.contains(group)) {
            clearSelection();
            selectGroup(group);
        }

        Set<GroupView> frames = new LinkedHashSet<>(selectedGroups);
        for (GroupView selected : new ArrayList<>(selectedGroups)) {
            frames.addAll(groupsCommandedBy(selected));
        }
        Set<NodeView> nodes = new LinkedHashSet<>(selectedNodes);
        for (GroupView frame : frames) {
            nodes.addAll(nodesCommandedBy(frame));
        }
        beginDragGesture(nodes, frames);
    }

    @Override
    public void onGroupDragged(double deltaContentX, double deltaContentY) {
        applyDragDelta(deltaContentX, deltaContentY);
    }

    @Override
    public void onGroupReleased() {
        endDragGesture();
    }

    @Override
    public void onGroupFrameEditStarted(GroupView group) {
        frameEditBefore = group.getGroup();
    }

    /**
     * Takes keyboard focus back from a frame's inline title editor, so the canvas's own shortcuts
     * keep working after a rename or a resize. See {@link GroupView.GroupController#focusCanvas()}
     * for why the view cannot simply leave focus where it was.
     */
    @Override
    public void focusCanvas() {
        requestFocus();
    }

    /**
     * Records a resize, rename or recolour as one undo step. Restacking is unconditional rather than
     * only on a size change: it is a sort of a handful of views, and making it conditional would put
     * the paint order one missed case away from being wrong.
     */
    @Override
    public void onGroupFrameEdited(GroupView group) {
        if (frameEditBefore == null || frameEditBefore.equals(group.getGroup())) {
            frameEditBefore = null;
            return;
        }
        undoManager.record(new SetGroupCommand(this, group, frameEditBefore, group.getGroup()));
        frameEditBefore = null;
        restackGroups();
    }

    // --- Drag gestures (shared by node drags and frame drags) ---------------------

    /**
     * Captures everything a drag is about to move, before it moves. The movement itself is applied
     * live on every mouse-move for real-time feedback (see {@link #applyDragDelta}), so this is what
     * lets {@link #endDragGesture()} tell whether anything actually moved and record one undo step
     * for the lot.
     *
     * <p>The two entry points differ only in what they hand in: a node drag moves the selected nodes,
     * a frame drag moves the frames plus everything they command.
     */
    private void beginDragGesture(Collection<NodeView> nodes, Collection<GroupView> groups) {
        dragGestureNodes = new ArrayList<>(nodes);
        dragGestureStartX = new double[dragGestureNodes.size()];
        dragGestureStartY = new double[dragGestureNodes.size()];
        for (int i = 0; i < dragGestureNodes.size(); i++) {
            dragGestureStartX[i] = dragGestureNodes.get(i).getLayoutX();
            dragGestureStartY[i] = dragGestureNodes.get(i).getLayoutY();
        }

        dragGestureGroups = new ArrayList<>(groups);
        dragGestureGroupStart = new ArrayList<>(dragGestureGroups.size());
        for (GroupView frame : dragGestureGroups) {
            dragGestureGroupStart.add(frame.getGroup());
        }

        // Waypoints ride along from two directions: any the rubber-band caught alongside these
        // nodes, and any sitting inside a frame being dragged — a manually-routed edge inside a
        // frame keeps its shape when the frame moves, for the same reason it does when its nodes do.
        dragGestureWaypoints = new LinkedHashMap<>();
        for (Map.Entry<AbstractEdgeView, Set<Integer>> entry : selectedWaypoints.entrySet()) {
            dragGestureWaypoints.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        for (GroupView frame : dragGestureGroups) {
            Bounds frameBounds = boundsOf(frame);
            for (ConnectionView connection : allConnections()) {
                if (!(connection instanceof AbstractEdgeView edge)) {
                    continue;
                }
                List<Integer> inside = edge.waypointIndicesIn(frameBounds);
                if (!inside.isEmpty()) {
                    dragGestureWaypoints.computeIfAbsent(edge, key -> new LinkedHashSet<>()).addAll(inside);
                }
            }
        }
        dragGestureWaypointsBefore = new HashMap<>();
        for (AbstractEdgeView edge : dragGestureWaypoints.keySet()) {
            dragGestureWaypointsBefore.put(edge, edge.getWaypoints());
        }
    }

    /** Moves everything {@link #beginDragGesture} captured, live, as the pointer moves. */
    private void applyDragDelta(double deltaContentX, double deltaContentY) {
        if (dragGestureNodes == null) {
            return;
        }
        for (NodeView node : dragGestureNodes) {
            node.setLayoutX(node.getLayoutX() + deltaContentX);
            node.setLayoutY(node.getLayoutY() + deltaContentY);
        }
        for (GroupView frame : dragGestureGroups) {
            frame.setGroup(frame.getGroup().movedBy(deltaContentX, deltaContentY));
        }
        for (Map.Entry<AbstractEdgeView, Set<Integer>> entry : dragGestureWaypoints.entrySet()) {
            entry.getKey().translateWaypoints(entry.getValue(), deltaContentX, deltaContentY);
        }
    }

    /**
     * Ends a drag, turning whatever actually moved into a single undo step. A gesture that moved
     * nodes, frames and routing at once records one {@link CompositeCommand} covering all three, so
     * one undo puts the whole thing back.
     *
     * <p>A frame drag never changes a frame's size, so nothing here restacks.
     */
    private void endDragGesture() {
        if (dragGestureNodes == null) {
            return;
        }
        List<Command> moves = new ArrayList<>();

        double[] endX = new double[dragGestureNodes.size()];
        double[] endY = new double[dragGestureNodes.size()];
        boolean moved = false;
        for (int i = 0; i < dragGestureNodes.size(); i++) {
            endX[i] = dragGestureNodes.get(i).getLayoutX();
            endY[i] = dragGestureNodes.get(i).getLayoutY();
            moved |= endX[i] != dragGestureStartX[i] || endY[i] != dragGestureStartY[i];
        }
        if (moved) {
            moves.add(new MoveNodesCommand(dragGestureNodes, dragGestureStartX, dragGestureStartY, endX, endY));
        }
        for (int i = 0; i < dragGestureGroups.size(); i++) {
            GroupView frame = dragGestureGroups.get(i);
            NodeGroup before = dragGestureGroupStart.get(i);
            if (!before.equals(frame.getGroup())) {
                moves.add(new SetGroupCommand(this, frame, before, frame.getGroup()));
            }
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
        dragGestureGroups = null;
        dragGestureGroupStart = null;
        dragGestureWaypoints = null;
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

    private void selectGroup(GroupView group) {
        if (selectedGroups.add(group)) {
            group.setSelected(true);
        }
    }

    /** Public for the same reason {@link #deselectNode} is: a Command must be able to drop what it removes. */
    public void deselectGroup(GroupView group) {
        if (selectedGroups.remove(group)) {
            group.setSelected(false);
        }
    }

    // --- Group frames -------------------------------------------------------------

    /**
     * Puts a group frame on the canvas. Public because {@code AddGroupCommand} and
     * {@code RemoveGroupsCommand} drive it — an undone delete re-adds the very same
     * {@link GroupView}, which is what keeps a later redo pointed at the frame the user is looking
     * at rather than a lookalike.
     */
    public void addGroup(GroupView groupView) {
        content.getChildren().add(groupView);
        groupViews.add(groupView);
        groupView.setZoom(zoom);
        restackGroups();
    }

    /** Takes a group frame off the canvas, dropping it from the selection so nothing retains a stale reference. */
    public void removeGroup(GroupView groupView) {
        deselectGroup(groupView);
        content.getChildren().remove(groupView);
        groupViews.remove(groupView);
    }

    /**
     * Re-sorts the frames in the content group's child list: largest first, and all of them ahead of
     * every node and edge.
     *
     * <p>Child order <em>is</em> paint order in a JavaFX {@link Group}, so this is the whole of
     * "frames render behind the graph, and a smaller frame renders on top of a larger one". Sorting
     * over the whole set rather than only over nested pairs means two frames that merely overlap
     * stack predictably too — see {@link NodeGroup#LARGEST_FIRST}.
     *
     * <p>Called whenever the set of frames or any frame's size changes. Public because
     * {@code SetGroupCommand} has to restack when it undoes a resize.
     */
    public void restackGroups() {
        List<GroupView> ordered = new ArrayList<>(groupViews);
        ordered.sort(Comparator.comparing(GroupView::getGroup, NodeGroup.LARGEST_FIRST));
        // Removed before being re-inserted: a JavaFX child list rejects a duplicate, and this is the
        // one operation that would otherwise present one.
        content.getChildren().removeAll(ordered);
        content.getChildren().addAll(0, ordered);
    }

    /** A frame's rectangle as canvas-coordinate bounds, for a hit test against nodes, edges or a rubber band. */
    private static Bounds boundsOf(GroupView group) {
        NodeGroup frame = group.getGroup();
        return new BoundingBox(frame.x(), frame.y(), frame.width(), frame.height());
    }

    /** Every node view lying entirely within {@code group} — recomputed on demand; nothing is stored. */
    private List<NodeView> nodesCommandedBy(GroupView group) {
        List<NodeView> commanded = new ArrayList<>();
        for (NodeView nodeView : nodeViews) {
            if (group.commands(nodeView.getBoundsInParent())) {
                commanded.add(nodeView);
            }
        }
        return commanded;
    }

    /** Every frame {@code group} commands: strictly smaller, and wholly inside it. */
    private List<GroupView> groupsCommandedBy(GroupView group) {
        List<GroupView> commanded = new ArrayList<>();
        for (GroupView other : groupViews) {
            if (group.getGroup().commands(other.getGroup())) {
                commanded.add(other);
            }
        }
        return commanded;
    }

    /**
     * Adds a group frame: around the current selection when there is one, otherwise an empty frame at
     * {@code x},{@code y}. Undoable.
     *
     * <p>Wrapping the selection is the case worth having — a graph is usually labelled after it is
     * built, not before — so the frame is fitted to the selected nodes' bounding box with room above
     * for its title bar, which puts every one of them inside it from the moment it appears.
     */
    public void addGroupAt(double x, double y) {
        NodeGroup frame = selectedNodes.isEmpty() ? NodeGroup.at(x, y) : frameAround(selectedNodes);
        undoManager.execute(new AddGroupCommand(this, new GroupView(frame, content, this)));
    }

    /** Frames the current selection, if there is one. The menu bar's Edit ▸ Group Selection and Ctrl/Cmd+G. */
    public void groupSelection() {
        if (selectedNodes.isEmpty()) {
            return;
        }
        undoManager.execute(new AddGroupCommand(this, new GroupView(frameAround(selectedNodes), content, this)));
    }

    /** The rectangle that wraps these nodes with a margin — wider at the top, where the title bar sits. */
    private NodeGroup frameAround(Collection<NodeView> nodes) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (NodeView nodeView : nodes) {
            Bounds bounds = nodeView.getBoundsInParent();
            minX = Math.min(minX, bounds.getMinX());
            minY = Math.min(minY, bounds.getMinY());
            maxX = Math.max(maxX, bounds.getMaxX());
            maxY = Math.max(maxY, bounds.getMaxY());
        }
        return new NodeGroup("", minX - GROUP_PADDING, minY - GROUP_TITLE_HEADROOM,
                maxX - minX + 2 * GROUP_PADDING,
                maxY - minY + GROUP_TITLE_HEADROOM + GROUP_PADDING, NodeGroup.DEFAULT_COLOR);
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
        for (GroupView group : new ArrayList<>(selectedGroups)) {
            deselectGroup(group);
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

    /**
     * Undoable delete of the current selection: selected nodes, plus every connection touching one,
     * plus any standalone selected connection, plus any selected group frame.
     *
     * <p><b>A frame takes nothing with it.</b> It commands the nodes inside it for a move or a copy,
     * but a frame is a large target laid over real work and cascading a delete through it would put
     * an automation one mis-aimed keystroke from gone. Deleting the label around a group of nodes
     * leaves the nodes exactly where they were.
     */
    public void deleteSelected() {
        if (selectedNodes.isEmpty() && selectedConnections.isEmpty() && selectedGroups.isEmpty()) {
            return;
        }

        List<GroupView> groupsToDelete = new ArrayList<>(selectedGroups);
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

        List<Command> deletions = new ArrayList<>();
        if (!nodesToDelete.isEmpty() || !connectionsToDelete.isEmpty()) {
            deletions.add(new RemoveNodesCommand(this, nodesToDelete, connectionsToDelete));
        }
        if (!groupsToDelete.isEmpty()) {
            deletions.add(new RemoveGroupsCommand(this, groupsToDelete));
        }
        undoManager.execute(deletions.size() == 1 ? deletions.get(0) : new CompositeCommand(deletions));

        selectedNodes.clear();
        selectedGroups.clear();
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
        for (GroupView group : new ArrayList<>(groupViews)) {
            removeGroup(group);
        }
        selectedNodes.clear();
        selectedGroups.clear();
        selectedConnections.clear();
    }

    // --- Copy / paste / save-load snapshotting --------------------------------------

    /**
     * Captures a set of nodes plus any data/flow edges that run between two of them —
     * edges to a node outside the set aren't included, since the other endpoint isn't
     * part of the snapshot — and the group frames handed in alongside. Used for both copy
     * (a selection) and save-to-file (everything on the canvas).
     */
    private GraphSnapshot snapshotOf(Collection<NodeView> views, Collection<GroupView> groups) {
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

        List<NodeGroup> frames = new ArrayList<>();
        for (GroupView groupView : groups) {
            frames.add(groupView.getGroup());
        }

        return new GraphSnapshot(nodes, dataEdges, flowEdges, frames);
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
     * routing waypoints the snapshot saved and the group frames it captured (neither of which the
     * engine has a place for).
     */
    public PlacedGraph place(GraphSnapshot snapshot, Function<ClipboardNode, BaseNode> nodeFactory, double offsetX, double offsetY) {
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

        // Frames last, so restackGroups() sorts them behind nodes and edges that are already there.
        List<GroupView> placedGroups = new ArrayList<>();
        for (NodeGroup frame : snapshot.groups()) {
            GroupView groupView = new GroupView(frame.movedBy(offsetX, offsetY), content, this);
            addGroup(groupView);
            placedGroups.add(groupView);
        }

        return new PlacedGraph(placed, placedGroups);
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

    /**
     * Snapshots the current selection (a single selected node works too).
     *
     * <p>A selected group frame brings <b>everything it commands</b> with it, whether or not those
     * nodes were selected themselves — copying a labelled region has to copy the region, not an
     * empty rectangle. The edges between the nodes that result come along under the usual rule.
     */
    public void copySelection() {
        Set<NodeView> selection = new LinkedHashSet<>(selectedNodes);
        for (GroupView group : selectedGroups) {
            selection.addAll(nodesCommandedBy(group));
        }
        if (selection.isEmpty() && selectedGroups.isEmpty()) {
            return;
        }
        // A MissingNode is a preserved save-file blob, not a working node. Duplicating one would call
        // its no-arg constructor and yield an empty placeholder with nothing behind it, so copying it
        // has no value. Filtering here beats adding a duplicate-yourself hook to BaseNode that every
        // node author would then have to understand, for this one case.
        Set<NodeView> copyable = new LinkedHashSet<>();
        int skipped = 0;
        for (NodeView view : selection) {
            if (view.getNode() instanceof MissingNode) {
                skipped++;
            } else {
                copyable.add(view);
            }
        }
        if (skipped > 0) {
            log.info("Not copying {} placeholder node(s) for uninstalled types", skipped);
        }
        if (copyable.isEmpty() && selectedGroups.isEmpty()) {
            return;
        }
        GraphSnapshot snapshot = snapshotOf(copyable, selectedGroups);
        clipboardNodes = snapshot.nodes();
        clipboardDataEdges = snapshot.dataEdges();
        clipboardFlowEdges = snapshot.flowEdges();
        clipboardGroups = snapshot.groups();
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
        if (clipboardNodes.isEmpty() && clipboardGroups.isEmpty()) {
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
            // Over the frames as well as the nodes: a frame is drawn around what it holds, so its
            // corner is normally the top-left of the whole copied region, and aiming at the nodes
            // alone would land the frame off the pointer by its own margin.
            double minX = Math.min(clipboardNodes.stream().mapToDouble(ClipboardNode::x).min().orElse(Double.MAX_VALUE),
                    clipboardGroups.stream().mapToDouble(NodeGroup::x).min().orElse(Double.MAX_VALUE));
            double minY = Math.min(clipboardNodes.stream().mapToDouble(ClipboardNode::y).min().orElse(Double.MAX_VALUE),
                    clipboardGroups.stream().mapToDouble(NodeGroup::y).min().orElse(Double.MAX_VALUE));
            offsetX = anchor.getX() - minX + cascade;
            offsetY = anchor.getY() - minY + cascade;
        } else {
            offsetX = PASTE_FALLBACK_OFFSET + cascade;
            offsetY = PASTE_FALLBACK_OFFSET + cascade;
        }

        GraphSnapshot snapshot = new GraphSnapshot(clipboardNodes, clipboardDataEdges, clipboardFlowEdges, clipboardGroups);
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

    /** Clears the current selection and selects exactly what was just placed - e.g. what a paste selects afterward. */
    public void selectOnly(PlacedGraph placed) {
        clearSelection();
        for (NodeView nodeView : placed.nodes()) {
            selectNode(nodeView);
        }
        for (GroupView groupView : placed.groups()) {
            selectGroup(groupView);
        }
    }

    /** Everything currently on the canvas, in the same shape used for copy/paste — for save-to-file. */
    public GraphSnapshot snapshotAll() {
        return snapshotOf(nodeViews, groupViews);
    }

    /**
     * Replaces the canvas's entire contents with a snapshot (e.g. loaded from file).
     * Not itself undoable, and wipes prior undo history - loading a different graph is
     * a new-document boundary, not an edit you'd undo back through.
     * <p>
     * After the whole graph is in place — every node built and activated, every edge wired — two
     * passes run, in this order:
     * <ol>
     *   <li>every {@code ModuleNode} is resolved against the injected {@link ModuleDirectory}
     *       ({@link ModuleBinding}), because one that is never bound reports itself misconfigured and
     *       refuses to run;</li>
     *   <li>any {@link AutoStartable} node that was running when the graph was saved is resumed (its
     *       Start/Connect path re-run).</li>
     * </ol>
     * Binding comes first because a resumed node may pull a value straight through a module, and both
     * come after {@link #place} because binding rebuilds a node's ports when its module's interface
     * has changed — which drops and re-attaches its edges <em>by name</em>, and so needs those edges
     * to exist. Neither pass runs on plain node placement: paste and undo/redo never auto-start a
     * copied resource, and a rebuilt view would strand the {@code Command} holding the old one.
     */
    public void loadSnapshot(GraphSnapshot snapshot) {
        clearAll();
        List<NodeView> placed = place(snapshot, ClipboardNode::node, 0, 0).nodes();
        undoManager.clear();
        bindModules(placed);
        resumeRunningNodes(placed);
    }

    /**
     * Resolves every just-loaded module reference. The decision is entirely
     * {@link ModuleBinding}'s — this only supplies the nodes and re-reads the views afterwards,
     * since a rebuilt shape replaces the {@link NodeView} the load produced.
     */
    private void bindModules(List<NodeView> placed) {
        List<BaseNode> nodes = new ArrayList<>();
        for (NodeView nodeView : placed) {
            nodes.add(nodeView.getNode());
        }
        ModuleBinding.Result result = ModuleBinding.bindAll(nodes, modules);
        if (result.total() == 0) {
            return;
        }
        // A rebuild swaps the view out from under `placed`, so anything that still needs a view for
        // one of these nodes has to ask nodeViewByNode rather than trust the list.
        for (int i = 0; i < placed.size(); i++) {
            NodeView current = nodeViewByNode.get(placed.get(i).getNode());
            if (current != null) {
                placed.set(i, current);
            }
        }
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

    // --- Find in graph ---------------------------------------------------------------

    /**
     * The find bar: a text field and a match count, floating over the canvas's top-right corner.
     *
     * <p>It highlights rather than navigates. Every node whose text contains the query is ringed in
     * yellow and the count says how many there are — which is what makes an off-screen hit
     * discoverable — but nothing pans, selects or reorders, so a find never disturbs the layout or
     * the selection the user is in the middle of.
     */
    private HBox buildFindBar() {
        graphSearchField.setPromptText("Find in graph…");
        graphSearchField.setPrefColumnCount(16);
        // Styled dark to match the node chrome. A default TextField is white, and the find bar
        // floats directly over the canvas rather than inside a dialog or a menu popup, so an
        // unstyled one reads as a piece of another application dropped onto the graph.
        graphSearchField.setStyle("-fx-control-inner-background: #3c3f41; -fx-text-fill: #eeeeee; "
                + "-fx-prompt-text-fill: #999999; -fx-highlight-fill: #4a4d4f;");
        graphSearchField.textProperty().addListener((obs, oldText, newText) -> applyFindQuery(newText));
        // Escape closes from inside the field; the canvas's own handler covers the case where focus
        // has since moved back to it.
        graphSearchField.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                closeFind();
                event.consume();
            }
        });

        graphSearchCount.setStyle("-fx-text-fill: #bbbbbb;");

        HBox bar = new HBox(8, graphSearchField, graphSearchCount);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 6, 8));
        bar.setStyle("-fx-background-color: #2b2d2f; -fx-border-color: #555555; -fx-border-width: 1;");
        bar.setVisible(false);
        return bar;
    }

    /**
     * Region's layout pass sizes managed children but never moves them, so this is the only place
     * the find bar's position is set — and it has to be recomputed here rather than bound, because
     * it depends on the bar's own width, which changes as the match count text does.
     */
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        // Clamped, so a canvas narrower than the bar overflows off the right edge (where the
        // clip hides it) rather than sliding off the left, taking the text field with it.
        findBar.relocate(Math.max(FIND_BAR_MARGIN, getWidth() - findBar.getWidth() - FIND_BAR_MARGIN),
                FIND_BAR_MARGIN);
    }

    /**
     * Opens the find bar and focuses it, re-running whatever query it still holds.
     *
     * <p>Public because the menu bar's <b>Edit ▸ Find</b> drives the same command — see
     * {@code ui/menu/MainMenuBar}. Reopening keeps the previous query and selects it, so the
     * shortcut both repeats a search and starts a new one without a detour to clear the field.
     */
    public void openFind() {
        findBar.setVisible(true);
        applyFindQuery(graphSearchField.getText());
        graphSearchField.requestFocus();
        graphSearchField.selectAll();
    }

    /** Hides the find bar and drops every highlight, handing focus back to the canvas. */
    public void closeFind() {
        findBar.setVisible(false);
        // The query text is kept for the next Ctrl/Cmd+F; only the highlighting goes.
        searchQuery = GraphSearch.compile("");
        for (NodeView nodeView : nodeViews) {
            nodeView.setSearchMatch(false);
        }
        requestFocus();
    }

    /** Re-tests every node on the canvas against {@code query} and repaints the highlights. */
    private void applyFindQuery(String query) {
        searchQuery = GraphSearch.compile(query);
        for (NodeView nodeView : nodeViews) {
            nodeView.setSearchMatch(searchQuery.matches(nodeView.getNode()));
        }
        updateFindCount();
    }

    /**
     * Refreshes the match count from the marks already on the views, rather than re-running the
     * query. Adding or removing a node while the bar is open only has to test the one node that
     * changed, which is what keeps opening a 500-node graph with a find in progress from being
     * quadratic.
     */
    private void updateFindCount() {
        if (searchQuery.isBlank()) {
            graphSearchCount.setText("");
            return;
        }
        int matches = 0;
        for (NodeView nodeView : nodeViews) {
            if (nodeView.isSearchMatch()) {
                matches++;
            }
        }
        graphSearchCount.setText(switch (matches) {
            case 0 -> "no matches";
            case 1 -> "1 match";
            default -> matches + " matches";
        });
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
            // A selection turns the row into "wrap this", which is how a frame is usually made — a
            // graph gets labelled after it is built. With nothing selected it drops an empty frame.
            addGroupItem.setText(selectedNodes.isEmpty() ? "Add Group" : "Group Selection");
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
        if (addModuleItem != null) {
            items.add(addModuleItem);
        }
        items.add(new SeparatorMenuItem());
        items.add(addGroupItem);
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
            addNodeAt(instance, pendingDropPoint.getX(), pendingDropPoint.getY());
        }
    }

    /**
     * Puts an already-built node on the canvas at a content-space point, as one undoable step.
     *
     * <p>Public because a node the canvas cannot build itself still has to land the same way
     * everything else does — a {@code ModuleNode} is built by whoever resolved the module (see
     * {@link ModuleReferenceAction}), not from the node registry.
     *
     * @param node the node to add; it must not already be on a graph
     * @param x    content-space x
     * @param y    content-space y
     */
    public void addNodeAt(BaseNode node, double x, double y) {
        undoManager.execute(new AddNodeCommand(this, new NodeView(node, content, this), x, y));
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

    /**
     * The "Add Module…" row under the Add-Node menu, or null when this canvas was given no way to
     * choose one. The drop point is read when the item is clicked and closed over, not when the
     * chosen node comes back: the answer may arrive after a worker has been to the filesystem, and
     * the node belongs where the menu was opened.
     */
    /**
     * The context menu's group row. Built once and kept, like the Add-Node menu and the Add-Module
     * row, because {@link #updateSearchResultsIn} re-adds the same items on every keystroke. Its
     * label is set as the menu opens, since what it will do depends on whether anything is selected.
     */
    private MenuItem buildAddGroupItem() {
        MenuItem item = new MenuItem("Add Group");
        item.setOnAction(event -> addGroupAt(pendingDropPoint.getX(), pendingDropPoint.getY()));
        return item;
    }

    private MenuItem buildAddModuleItem(ModuleReferenceAction moduleAdder) {
        if (moduleAdder == null) {
            return null;
        }
        MenuItem item = new MenuItem("Add Module…");
        item.setOnAction(event -> {
            double x = pendingDropPoint.getX();
            double y = pendingDropPoint.getY();
            moduleAdder.chooseModule(node -> addNodeAt(node, x, y));
        });
        return item;
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
        // A frame is caught only when the band encloses it whole, where a node is caught on a mere
        // intersection. A frame is a large background region, so intersection would catch it from
        // any band drawn inside it - and rubber-banding a few nodes that happen to sit in a frame
        // must not pick up the frame, or the next drag would move everything else in it too.
        for (GroupView group : groupViews) {
            if (rect.contains(boundsOf(group))) {
                selectGroup(group);
            } else {
                deselectGroup(group);
            }
        }
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

    /** Whether anything — node, connection, group frame — is selected. */
    public boolean hasSelection() {
        return !selectedNodes.isEmpty() || !selectedConnections.isEmpty() || !selectedGroups.isEmpty();
    }

    /** Whether a previous copy left something {@link #pasteClipboard()} could place. */
    public boolean canPaste() {
        return !clipboardNodes.isEmpty() || !clipboardGroups.isEmpty();
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
        for (GroupView group : groupViews) {
            selectGroup(group);
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
        for (GroupView group : groupViews) {
            group.setZoom(zoom);
        }
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
     * and group frame fits with a margin, and centres it. Does nothing on an empty canvas, or before
     * the canvas has been laid out and so has no size to fit into.
     */
    public void zoomToFit() {
        if ((nodeViews.isEmpty() && groupViews.isEmpty()) || getWidth() <= 0 || getHeight() <= 0) {
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
     *       when the user hit Export would come out ringed in amber. <b>Find-in-graph highlights go
     *       with it</b>, for the same reason and in the same way — an open find bar is not part of
     *       the graph, so it must not be part of the picture.</li>
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
        List<GroupView> hiddenGroups = new ArrayList<>();
        List<AbstractEdgeView> hiddenEdges = new ArrayList<>();
        List<NodeView> wasSelected = new ArrayList<>(selectedNodes);
        List<GroupView> wasSelectedGroups = new ArrayList<>(selectedGroups);
        List<ConnectionView> wasSelectedConnections = new ArrayList<>(selectedConnections);
        List<NodeView> wasFindMatch = new ArrayList<>();
        CameraState wasCamera = getCameraState();

        try {
            clearSelection();
            for (NodeView view : nodeViews) {
                if (view.isSearchMatch()) {
                    view.setSearchMatch(false);
                    wasFindMatch.add(view);
                }
            }
            setCameraState(IDENTITY_CAMERA);

            for (NodeView view : nodeViews) {
                if (!component.contains(view.getNode())) {
                    view.setVisible(false);
                    hiddenNodes.add(view);
                }
            }
            // A frame is drawn only into the pictures of the components it actually holds something
            // of. It is not a node and so belongs to no component, and one laid across the canvas
            // would otherwise stretch every component's crop rectangle out to cover it.
            for (GroupView view : groupViews) {
                if (!holdsAnyOf(view, component)) {
                    view.setVisible(false);
                    hiddenGroups.add(view);
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
            for (GroupView view : hiddenGroups) {
                view.setVisible(true);
            }
            for (AbstractEdgeView view : hiddenEdges) {
                view.setVisible(true);
            }
            setCameraState(wasCamera);
            for (NodeView view : wasFindMatch) {
                view.setSearchMatch(true);
            }
            for (NodeView view : wasSelected) {
                selectNode(view);
            }
            for (GroupView view : wasSelectedGroups) {
                selectGroup(view);
            }
            for (ConnectionView connection : wasSelectedConnections) {
                selectConnection(connection);
            }
        }
    }

    /** Whether this frame commands at least one node of {@code component} — whether it belongs in that component's picture. */
    private boolean holdsAnyOf(GroupView group, Set<BaseNode> component) {
        for (NodeView nodeView : nodeViews) {
            if (component.contains(nodeView.getNode()) && group.commands(nodeView.getBoundsInParent())) {
                return true;
            }
        }
        return false;
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
