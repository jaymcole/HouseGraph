package io.github.jaymcole.housegraph.ui.view;

import io.github.jaymcole.housegraph.saveformat.NodeGroup;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-canvas view of a {@link NodeGroup}: a translucent labelled rectangle drawn behind the
 * graph, which carries whatever sits inside it when it is moved.
 *
 * <h2>The body is mouse-transparent; the chrome is not</h2>
 * A frame is a large background region, and a large background region that swallows clicks would
 * make the canvas inside it unusable — no rubber band, no click-through to what is behind. So the
 * fill and the border take no input at all. The two pieces of chrome do: the <b>title bar</b> at the
 * top-left is the drag handle (and the label, and the right-click target), and the four <b>corner
 * grips</b> resize the frame. That is the same division {@link NodeView} makes, where the title bar
 * drags and the body does not.
 *
 * <p>The title bar is inset by one grip width so the top-left grip stays reachable beside it, and is
 * capped so it never grows over the top-right one.
 *
 * <h2>Resizing does not move anything</h2>
 * A drag on the title bar moves the frame and everything it commands. A drag on a grip changes the
 * rectangle only — which is the whole point, since the rectangle is what decides membership: growing
 * a frame over a node is how that node joins it, and shrinking off one is how it leaves.
 *
 * <h2>What this class does not decide</h2>
 * It owns its own rectangle and nothing else. Which nodes ride along with a move, what a gesture
 * costs on the undo stack, and where this frame sits in the paint order are all
 * {@code GraphCanvas}'s, reached through {@link GroupController} — the same shape as
 * {@link NodeView.DragController}. See {@code docs/engine/ui-layer.md}.
 */
public class GroupView extends Region {

    /** Lets the owning canvas apply a frame's gestures to everything the frame commands, and record them. */
    public interface GroupController {

        /** A drag on the title bar is starting: select the frame and work out what rides along. */
        void onGroupPressed(GroupView group);

        /** The title-bar drag moved by this much, in content coordinates. Already applied to this frame. */
        void onGroupDragged(double deltaContentX, double deltaContentY);

        /** The title-bar drag finished — a good point to record it as one undo step. */
        void onGroupReleased();

        /**
         * A change to this frame alone — a resize, a rename, a recolour — is about to be applied, so
         * the canvas can capture the "before" state for undo.
         */
        void onGroupFrameEditStarted(GroupView group);

        /** That change is complete and applied: record it, and restack if the frame's size changed. */
        void onGroupFrameEdited(GroupView group);
    }

    /** Which corner a resize grip sits in, and therefore which edges its drag moves. */
    private enum Corner {
        NORTH_WEST(true, true, Cursor.NW_RESIZE),
        NORTH_EAST(false, true, Cursor.NE_RESIZE),
        SOUTH_WEST(true, false, Cursor.SW_RESIZE),
        SOUTH_EAST(false, false, Cursor.SE_RESIZE);

        final boolean movesLeftEdge;
        final boolean movesTopEdge;
        final Cursor cursor;

        Corner(boolean movesLeftEdge, boolean movesTopEdge, Cursor cursor) {
            this.movesLeftEdge = movesLeftEdge;
            this.movesTopEdge = movesTopEdge;
            this.cursor = cursor;
        }
    }

    /** The palette offered in the right-click menu: enough to tell regions apart, few enough to pick from. */
    private static final String[][] COLOR_CHOICES = {
            {"Blue", "#61afef"},
            {"Green", "#98c379"},
            {"Amber", "#e5c07b"},
            {"Red", "#e06c75"},
            {"Purple", "#c678dd"},
            {"Cyan", "#56b6c2"},
            {"Grey", "#8a9199"},
    };

    private static final double GRIP_SIZE = 14;
    private static final double BORDER_WIDTH = 2;
    private static final double FILL_OPACITY = 0.10;
    private static final double BORDER_OPACITY = 0.55;
    private static final double GRIP_RESTING_OPACITY = 0.35;
    private static final Color SELECTED_BORDER_COLOR = Color.web("#e5c07b");
    private static final double SELECTED_BORDER_WIDTH = 2;

    private final Group content;
    private final GroupController controller;

    private final Rectangle background = new Rectangle();
    private final Rectangle selectionBorder = new Rectangle();
    private final HBox titleBar = new HBox();
    private final Label titleLabel = new Label();
    private final TextField titleField = new TextField();
    private final List<StackPane> grips = new ArrayList<>();

    private NodeGroup group;
    private boolean selected;

    private Point2D lastDragContentPoint;
    /** The rectangle a grip drag started from, so every step of it is measured against one fixed origin. */
    private NodeGroup resizeOrigin;

    /**
     * @param group      the frame to draw
     * @param content    the canvas's content group, for turning scene coordinates into canvas ones
     * @param controller the canvas; may not be null, since a frame with no canvas has nothing to carry
     */
    public GroupView(NodeGroup group, Group content, GroupController controller) {
        this.content = content;
        this.controller = controller;

        background.setMouseTransparent(true);
        background.setArcWidth(10);
        background.setArcHeight(10);
        background.setStrokeType(StrokeType.INSIDE);
        background.setStrokeWidth(BORDER_WIDTH);

        // Same overlay discipline as NodeView's: unmanaged, mouse-transparent, stroked INSIDE, so
        // showing it can never move or resize the frame under it.
        selectionBorder.setFill(null);
        selectionBorder.setStroke(SELECTED_BORDER_COLOR);
        selectionBorder.setStrokeWidth(SELECTED_BORDER_WIDTH);
        selectionBorder.setStrokeType(StrokeType.INSIDE);
        selectionBorder.setArcWidth(10);
        selectionBorder.setArcHeight(10);
        selectionBorder.setMouseTransparent(true);
        selectionBorder.setManaged(false);
        selectionBorder.setVisible(false);

        buildTitleBar();
        for (Corner corner : Corner.values()) {
            grips.add(buildGrip(corner));
        }

        getChildren().add(background);
        getChildren().add(titleBar);
        getChildren().addAll(grips);
        getChildren().add(selectionBorder);

        setGroup(group);
    }

    private void buildTitleBar() {
        titleLabel.setStyle("-fx-text-fill: #eeeeee; -fx-font-weight: bold;");
        titleField.setStyle("-fx-control-inner-background: #3c3f41; -fx-text-fill: #eeeeee; "
                + "-fx-prompt-text-fill: #999999; -fx-background-radius: 2;");
        titleField.setPromptText("Group name");
        titleField.setPrefColumnCount(12);
        titleField.setVisible(false);
        titleField.setManaged(false);

        titleBar.getChildren().addAll(titleLabel, titleField);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(3, 8, 3, 8));
        titleBar.setMinWidth(Region.USE_PREF_SIZE);
        titleBar.setCursor(Cursor.MOVE);

        titleBar.setOnMousePressed(this::handleDragStart);
        titleBar.setOnMouseDragged(this::handleDragging);
        titleBar.setOnMouseReleased(this::handleDragEnd);
        titleBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                beginRename();
                event.consume();
            }
        });
        titleBar.setOnContextMenuRequested(this::showContextMenu);

        // Enter commits, Escape abandons; anything that takes focus away commits too, so clicking
        // off a half-typed name keeps it rather than silently discarding the edit.
        titleField.setOnAction(event -> commitRename());
        titleField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                endRename();
                event.consume();
            }
        });
        titleField.focusedProperty().addListener((obs, was, now) -> {
            if (was && !now && titleField.isVisible()) {
                commitRename();
            }
        });
    }

    private StackPane buildGrip(Corner corner) {
        Rectangle handle = new Rectangle(GRIP_SIZE, GRIP_SIZE);
        handle.setArcWidth(4);
        handle.setArcHeight(4);

        StackPane grip = new StackPane(handle);
        grip.setManaged(false);
        grip.setCursor(corner.cursor);
        grip.setOpacity(GRIP_RESTING_OPACITY);
        grip.setOnMouseEntered(event -> grip.setOpacity(1));
        grip.setOnMouseExited(event -> grip.setOpacity(GRIP_RESTING_OPACITY));
        grip.setOnMousePressed(event -> {
            resizeOrigin = group;
            controller.onGroupFrameEditStarted(this);
            event.consume();
        });
        grip.setOnMouseDragged(event -> {
            resizeTo(corner, content.sceneToLocal(event.getSceneX(), event.getSceneY()));
            event.consume();
        });
        grip.setOnMouseReleased(event -> {
            resizeOrigin = null;
            controller.onGroupFrameEdited(this);
            event.consume();
        });
        grip.getProperties().put("handle", handle);
        return grip;
    }

    /**
     * Applies a grip drag. Both moved edges are measured from the rectangle the gesture started on
     * rather than from the previous frame, so a drag that pushes an edge past its opposite one and
     * back again comes out where the pointer is instead of accumulating whatever the minimum-size
     * clamp swallowed on the way.
     */
    private void resizeTo(Corner corner, Point2D pointerContentPoint) {
        NodeGroup origin = resizeOrigin;
        if (origin == null) {
            return;
        }
        double left = origin.x();
        double top = origin.y();
        double width = origin.width();
        double height = origin.height();

        if (corner.movesLeftEdge) {
            // Clamped against the fixed right edge, so dragging the left edge rightward stops at the
            // minimum width instead of turning the rectangle inside out.
            left = Math.min(pointerContentPoint.getX(), origin.maxX() - NodeGroup.MIN_WIDTH);
            width = origin.maxX() - left;
        } else {
            width = Math.max(NodeGroup.MIN_WIDTH, pointerContentPoint.getX() - origin.x());
        }
        if (corner.movesTopEdge) {
            top = Math.min(pointerContentPoint.getY(), origin.maxY() - NodeGroup.MIN_HEIGHT);
            height = origin.maxY() - top;
        } else {
            height = Math.max(NodeGroup.MIN_HEIGHT, pointerContentPoint.getY() - origin.y());
        }
        setGroup(group.withBounds(left, top, width, height));
    }

    // --- Title bar drag ----------------------------------------------------------

    private void handleDragStart(MouseEvent event) {
        controller.onGroupPressed(this);
        lastDragContentPoint = content.sceneToLocal(event.getSceneX(), event.getSceneY());
        event.consume();
    }

    private void handleDragging(MouseEvent event) {
        Point2D now = content.sceneToLocal(event.getSceneX(), event.getSceneY());
        double deltaX = now.getX() - lastDragContentPoint.getX();
        double deltaY = now.getY() - lastDragContentPoint.getY();
        lastDragContentPoint = now;
        // The canvas moves this frame along with everything it commands, so this view deliberately
        // does not move itself first - that would double the delta.
        controller.onGroupDragged(deltaX, deltaY);
        event.consume();
    }

    private void handleDragEnd(MouseEvent event) {
        controller.onGroupReleased();
        event.consume();
    }

    // --- Renaming ----------------------------------------------------------------

    /** Swaps the label for an editable field and focuses it — what a double-click on the title does. */
    public void beginRename() {
        titleField.setText(group.title());
        titleLabel.setVisible(false);
        titleLabel.setManaged(false);
        titleField.setVisible(true);
        titleField.setManaged(true);
        requestLayout();
        titleField.requestFocus();
        titleField.selectAll();
    }

    private void commitRename() {
        String typed = titleField.getText();
        endRename();
        if (typed.equals(group.title())) {
            return;
        }
        controller.onGroupFrameEditStarted(this);
        setGroup(group.withTitle(typed));
        controller.onGroupFrameEdited(this);
    }

    private void endRename() {
        titleField.setVisible(false);
        titleField.setManaged(false);
        titleLabel.setVisible(true);
        titleLabel.setManaged(true);
        requestLayout();
    }

    /** Rebuilt on each open so the checked colour reflects the frame's current one. */
    private void showContextMenu(ContextMenuEvent event) {
        ContextMenu menu = new ContextMenu();

        MenuItem rename = new MenuItem("Rename…");
        rename.setOnAction(action -> beginRename());

        Menu colors = new Menu("Colour");
        for (String[] choice : COLOR_CHOICES) {
            MenuItem item = new MenuItem(choice[0], swatch(choice[1]));
            item.setDisable(choice[1].equalsIgnoreCase(group.color()));
            item.setOnAction(action -> {
                controller.onGroupFrameEditStarted(this);
                setGroup(group.withColor(choice[1]));
                controller.onGroupFrameEdited(this);
            });
            colors.getItems().add(item);
        }

        menu.getItems().addAll(rename, colors);
        menu.show(this, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    private static Rectangle swatch(String color) {
        Rectangle square = new Rectangle(10, 10, Color.web(color));
        square.setArcWidth(3);
        square.setArcHeight(3);
        return square;
    }

    // --- Frame state -------------------------------------------------------------

    /** This frame's current rectangle, title and colour — the whole of its state, as one value. */
    public NodeGroup getGroup() {
        return group;
    }

    /** Replaces the whole frame: position, size, title and colour. The single write path, so nothing can drift. */
    public void setGroup(NodeGroup group) {
        this.group = group;
        setLayoutX(group.x());
        setLayoutY(group.y());
        setPrefSize(group.width(), group.height());

        Color color = Color.web(group.color());
        background.setFill(color.deriveColor(0, 1, 1, FILL_OPACITY));
        background.setStroke(color.deriveColor(0, 1, 1, BORDER_OPACITY));
        titleBar.setStyle("-fx-background-color: " + toRgba(color, 0.85) + "; -fx-background-radius: 4;");
        for (StackPane grip : grips) {
            ((Rectangle) grip.getProperties().get("handle")).setFill(color.deriveColor(0, 1, 1, 0.9));
        }
        titleLabel.setText(group.title().isEmpty() ? "Group" : group.title());
        titleLabel.setOpacity(group.title().isEmpty() ? 0.6 : 1);
        requestLayout();
    }

    private static String toRgba(Color color, double alpha) {
        return String.format("rgba(%d,%d,%d,%.2f)",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255),
                alpha);
    }

    /** Whether this frame commands {@code bounds} — a node's or another frame's, in canvas coordinates. */
    public boolean commands(Bounds bounds) {
        return group.commands(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
    }

    /** Amber border, matching a selected node's. */
    public void setSelected(boolean selected) {
        this.selected = selected;
        selectionBorder.setVisible(selected);
    }

    public boolean isSelected() {
        return selected;
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();

        background.setWidth(width);
        background.setHeight(height);
        selectionBorder.setWidth(width);
        selectionBorder.setHeight(height);
        selectionBorder.relocate(0, 0);

        // Inset by a grip on each side: the title sits beside the top-left grip rather than over it,
        // and can never grow across the top-right one.
        double available = Math.max(0, width - 2 * GRIP_SIZE);
        double barWidth = Math.min(titleBar.prefWidth(-1), available);
        titleBar.resizeRelocate(GRIP_SIZE, 0, barWidth, titleBar.prefHeight(barWidth));

        for (int i = 0; i < grips.size(); i++) {
            Corner corner = Corner.values()[i];
            grips.get(i).resizeRelocate(corner.movesLeftEdge ? 0 : width - GRIP_SIZE,
                    corner.movesTopEdge ? 0 : height - GRIP_SIZE, GRIP_SIZE, GRIP_SIZE);
        }
    }
}
