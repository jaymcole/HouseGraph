package io.github.jaymcole.housegraph.ui.view;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ExecutionPolicy;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual representation of a {@link BaseNode}: a title bar (used to drag the node
 * around the canvas, and hosting the flow-in/flow-out anchors at its corners) plus a
 * left column of input ports and a right column of output ports.
 * <p>
 * Flow anchors come straight from the node's {@link BaseNode#getFlowInputs()} /
 * {@link BaseNode#getFlowOutputs()} — a node with no flow ports gets no anchors, and a
 * node exposing several out-ports (e.g. a branch/decider) gets one anchor each.
 */
public class NodeView extends BorderPane {

    /** Lets the owning canvas coordinate selection and group-dragging across nodes. */
    public interface DragController {
        void onNodePressed(NodeView node);

        void onNodeDragged(double deltaContentX, double deltaContentY);

        /** The drag gesture (mouse button released) finished - a good point to record it as one undo step. */
        void onNodeReleased();
    }

    private final BaseNode node;
    private final Group content;
    private final DragController dragController;
    private final List<PortView> inputPorts = new ArrayList<>();
    private final List<PortView> outputPorts = new ArrayList<>();

    /** One anchor per model flow port; either list may be empty. See the class Javadoc. */
    private final List<FlowPortView> flowInPorts = new ArrayList<>();
    private final List<FlowPortView> flowOutPorts = new ArrayList<>();

    private static final Color SELECTED_BORDER_COLOR = Color.web("#e5c07b");
    private static final Color PULSE_BORDER_COLOR = Color.web("#61dafb");
    private static final Color MISCONFIGURED_BORDER_COLOR = Color.web("#e06c75");
    private static final double SELECTED_BORDER_WIDTH = 2;
    private static final double PULSE_BORDER_WIDTH = 3;
    private static final double MISCONFIGURED_BORDER_WIDTH = 2;
    private static final Color PROCESSING_STRIPE_COLOR = Color.web("#e5a561");
    private static final Duration PULSE_DURATION = Duration.millis(400);
    private static final double PROCESSING_STRIPE_WIDTH = 4;
    private static final double PROCESSING_DASH_LENGTH = 10;
    private static final double PROCESSING_GAP_LENGTH = 8;
    private static final double PROCESSING_CYCLE_LENGTH = PROCESSING_DASH_LENGTH + PROCESSING_GAP_LENGTH;

    private final Rectangle validationBorder;
    private final Rectangle highlightBorder;
    private final Rectangle processingStripes;
    private final Timeline processingAnimation;

    /** Explains, on hover, which required inputs are unsatisfied while the node is misconfigured. */
    private final Tooltip validationTooltip = new Tooltip();

    /** Sits beside the title, holding the glyph for the node's current {@link ExecutionPolicy}. */
    private final StackPane policyIcon = new StackPane();
    private final Tooltip policyTooltip = new Tooltip();

    private Point2D lastDragContentPoint;
    private boolean selected = false;
    private boolean processing = false;
    private PauseTransition pulseRevert;

    public NodeView(BaseNode node, Group content, DragController dragController) {
        this.node = node;
        this.content = content;
        this.dragController = dragController;

        // No fixed/minimum width: the node sizes to its actual content (title + ports).
        // A fixed width would leave slack whenever content is narrower than it, and
        // that slack has to render as a gap somewhere - between columns, or trailing
        // after the last one - neither of which is wanted.
        //
        // The structural border is a constant 1px and never changes. A Region's border
        // width feeds into its insets, so widening it on selection used to push the
        // title bar and ports inward and grow the whole node by a couple of pixels.
        // Selection and pulse emphasis are instead drawn by the highlightBorder
        // overlay below, which layout ignores entirely.
        setStyle("-fx-background-color: #3c3f41; -fx-border-color: #555555; -fx-border-width: 1;");

        for (FlowPort flowPort : node.getFlowInputs()) {
            flowInPorts.add(new FlowPortView(this, flowPort));
        }
        for (FlowPort flowPort : node.getFlowOutputs()) {
            flowOutPorts.add(new FlowPortView(this, flowPort));
        }

        Label title = new Label(node.getName());
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);
        title.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        HBox.setHgrow(title, Priority.ALWAYS);

        // The title bar carries only the label-less "default" flow anchors (the common
        // one-per-side case) at its corners. Named ports - e.g. a decider's True/False -
        // are laid out down the port columns below instead, where there's room for a label.
        HBox titleBar = new HBox(6);
        for (FlowPortView flowInPort : flowInPorts) {
            if (flowInPort.getFlowPort().name.isBlank()) {
                titleBar.getChildren().add(flowInPort);
            }
        }
        // A small glyph for the execution policy, tucked just left of the title — for any node the
        // policy applies to, i.e. any that participates in flow. That's an execution entry point
        // (where the policy gates the whole run) or a mid-cascade flow node (where it gates that
        // node's own process() re-entry; see NodeGraph.ReentryGate). A pure data node — a constant,
        // a converter with no flow ports — is never triggered, so it gets no glyph and no policy
        // menu. Refreshed whenever the policy changes.
        boolean showsPolicy = participatesInFlow();
        if (showsPolicy) {
            Tooltip.install(policyIcon, policyTooltip);
            refreshPolicyIcon();
            titleBar.getChildren().add(policyIcon);
        }
        titleBar.getChildren().add(title);
        for (FlowPortView flowOutPort : flowOutPorts) {
            if (flowOutPort.getFlowPort().name.isBlank()) {
                titleBar.getChildren().add(flowOutPort);
            }
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

        // Named flow ports sit in the port columns below any data ports on the same
        // side: flow-in on the left, flow-out (a decider's branches) on the right.
        for (FlowPortView flowInPort : flowInPorts) {
            if (!flowInPort.getFlowPort().name.isBlank()) {
                inputsBox.getChildren().add(labeledFlowAnchor(flowInPort, false));
            }
        }
        for (FlowPortView flowOutPort : flowOutPorts) {
            if (!flowOutPort.getFlowPort().name.isBlank()) {
                outputsBox.getChildren().add(labeledFlowAnchor(flowOutPort, true));
            }
        }

        // Based on actual column contents (which now include named flow ports), not
        // just the data ports, so a node with only flow-out branches still gets a column.
        boolean hasInputs = !inputsBox.getChildren().isEmpty();
        boolean hasOutputs = !outputsBox.getChildren().isEmpty();

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
        titleBar.setOnMouseReleased(this::handleDragEnd);

        // Prevent clicks elsewhere on the node from panning/rubber-band-selecting the canvas underneath it.
        setOnMousePressed(Event::consume);
        setOnMouseDragged(Event::consume);

        // Right-click the node for its per-node settings (execution policy, concurrency limit and
        // timeout for any node that participates in flow; which inputs are required, for any node
        // with inputs). Nodes with none of these (a constant, a resource with no inputs) get no menu
        // and the event falls through to the canvas's add-node menu, as before.
        if (participatesInFlow() || !node.getInputs().isEmpty()) {
            setOnContextMenuRequested(this::showContextMenu);
        }

        // Emphasis overlay for the selected and pulse states: an unmanaged, mouse-
        // transparent rectangle stretched over the whole node, stroked on the inside
        // so it sits exactly where the CSS border does. Unmanaged children don't
        // participate in layout and an INSIDE stroke never extends a shape's bounds,
        // so this border can be any width (or later carry glows, gradients, animated
        // strokes...) without the node moving or resizing by a single pixel.
        // Persistent red border marking a misconfigured node (a required input with no value
        // source). Built like the selection overlay — unmanaged, mouse-transparent, INSIDE
        // stroke — and added first so the selection/pulse border paints on top of it; the red
        // port circles (see PortView.setMissingRequired) keep the problem visible even then.
        validationBorder = new Rectangle();
        validationBorder.setFill(null);
        validationBorder.setStroke(MISCONFIGURED_BORDER_COLOR);
        validationBorder.setStrokeWidth(MISCONFIGURED_BORDER_WIDTH);
        validationBorder.setStrokeType(StrokeType.INSIDE);
        validationBorder.widthProperty().bind(widthProperty());
        validationBorder.heightProperty().bind(heightProperty());
        validationBorder.setMouseTransparent(true);
        validationBorder.setVisible(false);
        validationBorder.setManaged(false);
        getChildren().add(validationBorder);

        highlightBorder = new Rectangle();
        highlightBorder.setFill(null);
        highlightBorder.setStrokeType(StrokeType.INSIDE);
        highlightBorder.widthProperty().bind(widthProperty());
        highlightBorder.heightProperty().bind(heightProperty());
        highlightBorder.setMouseTransparent(true);
        highlightBorder.setVisible(false);
        highlightBorder.setManaged(false);
        getChildren().add(highlightBorder);

        // "Marching ants" overlay for the processing state: a dashed rectangle drawn
        // over the whole node, with its dash offset animated so the stripes appear to
        // crawl around the border. The gaps between orange dashes just show whatever's
        // underneath (the node's own dark background/border), which is what gives the
        // alternating orange/dark candy-cane look without needing a second shape.
        // Added last (BorderPane extends Pane, so extra children beyond the five named
        // slots are allowed) so it paints on top of the title bar, body, and everything else.
        processingStripes = new Rectangle();
        processingStripes.setFill(null);
        processingStripes.setStroke(PROCESSING_STRIPE_COLOR);
        processingStripes.setStrokeWidth(PROCESSING_STRIPE_WIDTH);
        processingStripes.getStrokeDashArray().setAll(PROCESSING_DASH_LENGTH, PROCESSING_GAP_LENGTH);
        processingStripes.widthProperty().bind(widthProperty());
        processingStripes.heightProperty().bind(heightProperty());
        processingStripes.setMouseTransparent(true);
        processingStripes.setVisible(false);
        processingStripes.setManaged(false);
        getChildren().add(processingStripes);

        processingAnimation = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(processingStripes.strokeDashOffsetProperty(), 0)),
                new KeyFrame(Duration.seconds(0.6), new KeyValue(processingStripes.strokeDashOffsetProperty(), PROCESSING_CYCLE_LENGTH)));
        processingAnimation.setCycleCount(Timeline.INDEFINITE);

        // Reflect the node's initial configured state (a fresh node with unwired required
        // inputs shows red at once, before any edge is drawn).
        refreshValidation();
    }

    /**
     * Wraps a named flow anchor with its label for the port columns, matching a
     * {@link PortView}'s look: label beside the anchor, anchor on the outer edge
     * ({@code outputSide} puts it on the right). The anchor itself stays the drag
     * target — the label is just decoration.
     */
    private Node labeledFlowAnchor(FlowPortView anchor, boolean outputSide) {
        Label label = new Label(anchor.getFlowPort().name);
        label.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 11px;");
        HBox row = new HBox(6);
        row.setAlignment(outputSide ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        if (outputSide) {
            row.getChildren().addAll(label, anchor);
        } else {
            row.getChildren().addAll(anchor, label);
        }
        return row;
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

    private void handleDragEnd(MouseEvent event) {
        if (dragController != null) {
            dragController.onNodeReleased();
        }
        event.consume();
    }

    /** Builds and shows the node's right-click menu fresh each time, so it reflects the node's current state. */
    private void showContextMenu(ContextMenuEvent event) {
        ContextMenu menu = new ContextMenu();
        if (participatesInFlow()) {
            // Execution policy gates re-entry (a whole run at an entry node, or this node's own
            // process() mid-cascade); concurrency/timeout are per-node throughput controls. All
            // three are meaningful for any node that participates in flow - a trigger, an LLM/camera
            // node, a transform - not just entry points.
            menu.getItems().add(buildExecutionPolicyMenu());
            menu.getItems().add(buildConcurrencyMenu());
            menu.getItems().add(buildTimeoutMenu());
        }
        if (!node.getInputs().isEmpty()) {
            menu.getItems().add(buildRequiredInputsMenu());
        }
        if (menu.getItems().isEmpty()) {
            return;
        }
        menu.show(this, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    private boolean participatesInFlow() {
        return !node.getFlowInputs().isEmpty() || !node.getFlowOutputs().isEmpty();
    }

    /**
     * A submenu of mutually-exclusive execution policies (see {@link ExecutionPolicy}), with the
     * node's current one pre-selected and a glyph beside each. Selecting one applies it and
     * refreshes the title glyph.
     */
    private Menu buildExecutionPolicyMenu() {
        Menu policyMenu = new Menu("Execution Policy");
        ToggleGroup group = new ToggleGroup();
        for (ExecutionPolicy policy : ExecutionPolicy.values()) {
            RadioMenuItem item = new RadioMenuItem(ExecutionPolicyIcons.label(policy));
            item.setGraphic(ExecutionPolicyIcons.create(policy));
            item.setToggleGroup(group);
            item.setSelected(node.getExecutionPolicy() == policy);
            item.setOnAction(event -> {
                node.setExecutionPolicy(policy);
                refreshPolicyIcon();
            });
            policyMenu.getItems().add(item);
        }
        return policyMenu;
    }

    /** Swaps in the glyph and tooltip for the node's current policy; called at build time and after a change. */
    private void refreshPolicyIcon() {
        ExecutionPolicy policy = node.getExecutionPolicy();
        policyIcon.getChildren().setAll(ExecutionPolicyIcons.create(policy));
        policyTooltip.setText(ExecutionPolicyIcons.label(policy));
    }

    /** Caps how many runs may execute this node at once ({@link io.github.jaymcole.housegraph.graph.BaseNode#getMaxConcurrency}). */
    private Menu buildConcurrencyMenu() {
        Menu menu = new Menu("Concurrency limit");
        ToggleGroup group = new ToggleGroup();
        int current = node.getMaxConcurrency();
        for (int option : new int[]{0, 1, 2, 3, 4, 8}) {
            RadioMenuItem item = new RadioMenuItem(option == 0 ? "Unlimited" : String.valueOf(option));
            item.setToggleGroup(group);
            item.setSelected(current == option);
            item.setOnAction(event -> node.setMaxConcurrency(option));
            menu.getItems().add(item);
        }
        return menu;
    }

    /** Bounds how long this node's process() may run before being aborted ({@link io.github.jaymcole.housegraph.graph.BaseNode#getTimeoutMillis}). */
    private Menu buildTimeoutMenu() {
        Menu menu = new Menu("Process timeout");
        ToggleGroup group = new ToggleGroup();
        long current = node.getTimeoutMillis();
        long[] optionsMillis = {0, 1_000, 5_000, 15_000, 30_000, 60_000};
        String[] labels = {"None", "1s", "5s", "15s", "30s", "1m"};
        for (int i = 0; i < optionsMillis.length; i++) {
            long option = optionsMillis[i];
            RadioMenuItem item = new RadioMenuItem(labels[i]);
            item.setToggleGroup(group);
            item.setSelected(current == option);
            item.setOnAction(event -> node.setTimeoutMillis(option));
            menu.getItems().add(item);
        }
        return menu;
    }

    /**
     * A checklist of this node's inputs, each toggling its {@link NodeVariable#setRequired(boolean)
     * required} flag — the user's way to say "this input must be wired (or given a value)". Toggling
     * re-runs {@link #refreshValidation()} so the red border/port indicator updates immediately; the
     * choice is persisted (see {@code GraphFileIO}). Like the policy/concurrency/timeout menus, this
     * mutates the model directly rather than through the undo stack, matching those controls.
     */
    private Menu buildRequiredInputsMenu() {
        Menu menu = new Menu("Required inputs");
        for (NodeVariable<?> input : node.getInputs()) {
            CheckMenuItem item = new CheckMenuItem(input.name);
            item.setSelected(input.isRequired());
            item.setOnAction(event -> {
                input.setRequired(item.isSelected());
                refreshValidation();
            });
            menu.getItems().add(item);
        }
        return menu;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        applyHighlight();
    }

    public boolean isSelected() {
        return selected;
    }

    private void applyHighlight() {
        if (selected) {
            showHighlightBorder(SELECTED_BORDER_COLOR, SELECTED_BORDER_WIDTH);
        } else {
            highlightBorder.setVisible(false);
        }
    }

    private void showHighlightBorder(Color color, double width) {
        highlightBorder.setStroke(color);
        highlightBorder.setStrokeWidth(width);
        highlightBorder.setVisible(true);
    }

    /** Briefly flashes the border to show this node was just triggered, then reverts to its normal (selected or not) style. */
    public void pulse() {
        showHighlightBorder(PULSE_BORDER_COLOR, PULSE_BORDER_WIDTH);
        if (pulseRevert != null) {
            pulseRevert.stop();
        }
        pulseRevert = new PauseTransition(PULSE_DURATION);
        pulseRevert.setOnFinished(event -> applyHighlight());
        pulseRevert.play();
    }

    /** Shows (or hides) the animated candy-cane-striped overlay while this node's process() is actually running. */
    public void setProcessing(boolean processing) {
        this.processing = processing;
        processingStripes.setVisible(processing);
        processingStripes.setManaged(processing);
        if (processing) {
            processingAnimation.play();
        } else {
            processingAnimation.stop();
        }
    }

    public boolean isProcessing() {
        return processing;
    }

    /**
     * Recomputes this node's misconfigured state and updates its indicators: the red node border, a
     * hover tooltip naming the unsatisfied required inputs, and a thin red border around each
     * unsatisfied input port. Called by {@link GraphCanvas} whenever the node's wiring changes (an edge added
     * or removed, the node added/rebuilt) and by {@link PortView} when a manual value is committed.
     * Pure view refresh — the truth lives in {@link BaseNode#getUnsatisfiedRequiredInputs()}.
     */
    public void refreshValidation() {
        List<NodeVariable> missing = node.getUnsatisfiedRequiredInputs();
        boolean misconfigured = !missing.isEmpty();

        validationBorder.setVisible(misconfigured);
        if (misconfigured) {
            List<String> names = new ArrayList<>();
            for (NodeVariable<?> variable : missing) {
                names.add(variable.name);
            }
            validationTooltip.setText("Missing required input(s): " + String.join(", ", names));
            Tooltip.install(this, validationTooltip);
        } else {
            Tooltip.uninstall(this, validationTooltip);
        }

        for (PortView port : inputPorts) {
            port.setMissingRequired(missing.contains(port.getVariable()));
        }
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

    public List<FlowPortView> getFlowInPorts() {
        return flowInPorts;
    }

    public List<FlowPortView> getFlowOutPorts() {
        return flowOutPorts;
    }
}
