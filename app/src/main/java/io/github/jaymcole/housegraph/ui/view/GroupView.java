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
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.transform.Scale;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-canvas view of a {@link NodeGroup}: a translucent labelled rectangle drawn behind the
 * graph, which carries whatever sits inside it when it is moved.
 *
 * <h2>The body is mouse-transparent; the chrome is not</h2>
 * A frame is a large background region, and a large background region that swallows clicks would
 * make the canvas inside it unusable — no rubber band, no click-through to what is behind. So the
 * <b>fill</b> takes no input at all. The two pieces of chrome do: the <b>title bar</b> at the
 * top-left is the drag handle (and the label, and the right-click target), and the <b>border</b>
 * resizes the frame. That is the same division {@link NodeView} makes, where the title bar drags
 * and the body does not.
 *
 * <h2>The border is the resize control, the way an OS window's is</h2>
 * Eight handles cover the frame's own border: one strip down each edge, running it end to end, and
 * one square at each corner. Nothing is drawn for them — the gesture announces itself with the
 * resize cursor and by lighting the edges the drag would move, in the frame's own colour. A frame
 * at rest is its rectangle and its title, with no resize furniture stuck to it.
 *
 * <p>Only the border strip is live, not the fill, so everything the frame is drawn around stays
 * reachable. The cost is that a rubber band cannot be started in the few pixels directly over an
 * edge, which is what buys the gesture everywhere along the border rather than at a few points
 * on it.
 *
 * <h2>Side handles resize on one axis only</h2>
 * A corner handle drags both adjacent edges. A side handle drags only the edge it covers: the left
 * and right strips move horizontally and change width alone, and the top and bottom strips move
 * vertically and change height alone — a side never touches the axis it doesn't own. Corners are
 * added to the frame after the sides, so the pixels they share start the two-axis drag.
 *
 * <h2>Resizing does not move anything</h2>
 * A drag on the title bar moves the frame and everything it commands. A drag on the border changes
 * the rectangle only — which is the whole point, since the rectangle is what decides membership:
 * growing a frame over a node is how that node joins it, and shrinking off one is how it leaves.
 *
 * <h2>The title bar paints in a layer above every node</h2>
 * The body renders behind the graph so its translucent fill never washes out what is inside it —
 * but a title sitting at the frame's own paint depth would then vanish under any node placed near
 * that corner. So the title bar is not a child of this {@code Region}: {@link #getTitleBar()} hands
 * it to {@code GraphCanvas}, which keeps it in a separate group stacked above every {@code NodeView}.
 * It is inset by a corner's width so the top-left corner handle stays reachable beneath it, and
 * clamped so it can never grow across the top-right one.
 * {@link #setGroup} repositions and resizes it there directly, in the same content coordinates this
 * frame itself uses, since it can no longer rely on this {@code Region}'s own {@code layoutChildren}
 * to place a child that is not actually its child.
 *
 * <h2>What this class does not decide</h2>
 * It owns its own rectangle and nothing else. Which nodes ride along with a move, what a gesture
 * costs on the undo stack, and where this frame (and its title bar) sits in the paint order are all
 * {@code GraphCanvas}'s, reached through {@link GroupController} — the same shape as
 * {@link NodeView.DragController}. See {@code docs/engine/ui-layer.md}.
 */
public class GroupView extends Region {

    /** Lets the owning canvas apply a frame's gestures to everything the frame commands, and record them. */
    public interface GroupController {

        /**
         * A drag on the title bar is starting: select the frame and work out what rides along.
         *
         * @param shiftDown whether Shift was held, so the canvas can add to the selection rather than replace it.
         */
        void onGroupPressed(GroupView group, boolean shiftDown);

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

        /**
         * Hands keyboard focus back to the canvas.
         *
         * <p>Needed because this view's inline title editor is hidden <em>while it still holds
         * focus</em> — Enter commits and closes it in one step. JavaFX does not move focus off a node
         * just because it became invisible, so without this the hidden field stays the scene's focus
         * owner and goes on swallowing every shortcut: Ctrl/Cmd+Z, Delete, copy, paste. Renaming a
         * frame would quietly cost the user their keyboard.
         */
        void focusCanvas();
    }

    /** Where a handle sits along one axis: pinned to an edge, or spanning the whole of it. */
    private enum Anchor {
        START, MIDDLE, END
    }

    /**
     * Where a resize handle sits on the frame's border, and therefore which edges its drag moves. A
     * corner combines a non-{@code MIDDLE} anchor on both axes and moves both edges; a side handle is
     * {@code MIDDLE} on one axis — the axis it does not resize, and the one it spans end to end —
     * and pinned on the other.
     */
    private enum Handle {
        NORTH_WEST(Anchor.START, Anchor.START, Cursor.NW_RESIZE),
        NORTH(Anchor.MIDDLE, Anchor.START, Cursor.N_RESIZE),
        NORTH_EAST(Anchor.END, Anchor.START, Cursor.NE_RESIZE),
        WEST(Anchor.START, Anchor.MIDDLE, Cursor.W_RESIZE),
        EAST(Anchor.END, Anchor.MIDDLE, Cursor.E_RESIZE),
        SOUTH_WEST(Anchor.START, Anchor.END, Cursor.SW_RESIZE),
        SOUTH(Anchor.MIDDLE, Anchor.END, Cursor.S_RESIZE),
        SOUTH_EAST(Anchor.END, Anchor.END, Cursor.SE_RESIZE);

        final Anchor horizontal;
        final Anchor vertical;
        final Cursor cursor;

        Handle(Anchor horizontal, Anchor vertical, Cursor cursor) {
            this.horizontal = horizontal;
            this.vertical = vertical;
            this.cursor = cursor;
        }

        boolean resizesHorizontal() {
            return horizontal != Anchor.MIDDLE;
        }

        boolean resizesVertical() {
            return vertical != Anchor.MIDDLE;
        }

        boolean movesLeftEdge() {
            return horizontal == Anchor.START;
        }

        boolean movesTopEdge() {
            return vertical == Anchor.START;
        }

        boolean isCorner() {
            return resizesHorizontal() && resizesVertical();
        }

        /**
         * How big this handle's strip is on a frame of the given size. A side handle is thin across
         * the edge it drags and spans the frame on the axis it leaves alone; a corner is thick on
         * both. Clamped against the frame's own size, though {@code NodeGroup}'s minimums keep a
         * frame well clear of that.
         */
        double width(double frameWidth) {
            return size(horizontal, frameWidth);
        }

        double height(double frameHeight) {
            return size(vertical, frameHeight);
        }

        private double size(Anchor anchor, double frameSize) {
            if (anchor == Anchor.MIDDLE) {
                return frameSize;
            }
            return Math.min(isCorner() ? RESIZE_CORNER_SPAN : RESIZE_BORDER_THICKNESS, frameSize);
        }

        /** A handle hugs the edge it drags, and starts at zero on an axis it spans. */
        double x(double frameWidth) {
            return horizontal == Anchor.END ? frameWidth - width(frameWidth) : 0;
        }

        double y(double frameHeight) {
            return vertical == Anchor.END ? frameHeight - height(frameHeight) : 0;
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

    /**
     * How far into the frame a resize handle reaches. Wider than {@link NodeView}'s: that one has to
     * stay clear of a port circle twelve pixels in, while a frame's border has nothing near it but
     * empty canvas. A frame is also the thing you zoom out to see whole, and a handle does not
     * compensate for zoom the way the title bar does, so a strip that is thin in canvas pixels is
     * thin on screen too.
     */
    private static final double RESIZE_BORDER_THICKNESS = 10;
    private static final double RESIZE_CORNER_SPAN = 20;

    /**
     * The edges a resize handle lights, drawn in the frame's own colour at full strength over its
     * own border. Held back from the corners by the border's arc, which a straight bar run edge to
     * edge would otherwise overshoot.
     */
    private static final double RESIZE_EDGE_WIDTH = 3;
    private static final double CORNER_ARC = 10;

    private static final double BORDER_WIDTH = 2;
    private static final double FILL_OPACITY = 0.10;
    private static final double BORDER_OPACITY = 0.55;
    private static final Color SELECTED_BORDER_COLOR = Color.web("#e5c07b");
    private static final double SELECTED_BORDER_WIDTH = 2;

    private final Group content;
    private final GroupController controller;

    private final Rectangle background = new Rectangle();
    private final Rectangle selectionBorder = new Rectangle();
    private final HBox titleBar = new HBox();
    private final Label titleLabel = new Label();
    private final TextField titleField = new TextField();
    /**
     * The transparent strips of border that resize the frame: one per {@link Handle}, in that enum's
     * order, which is what {@link #layoutChildren()} relies on to place them. Nothing is drawn for
     * them — see {@link #edgeHighlights}.
     */
    private final List<Rectangle> resizeZones = new ArrayList<>();

    /**
     * What a resize handle actually shows: the edges it would move, in {@link Handle#values()}-
     * independent order — top, right, bottom, left — matching {@link #refreshResizeHighlight()}.
     */
    private final List<Rectangle> edgeHighlights = new ArrayList<>();

    /** The handle under the pointer and the handle being dragged. A drag outranks the pointer. */
    private Handle hoveredHandle;
    private Handle draggedHandle;
    /** Grows the title bar back to its 1:1 on-screen size as the canvas zooms out past it; see {@link #setZoom}. */
    private final Scale titleZoomCompensation = new Scale(1, 1, 0, 0);

    private NodeGroup group;
    private boolean selected;

    private Point2D lastDragContentPoint;
    /** The rectangle a resize drag started from, so every step of it is measured against one fixed origin. */
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
        background.setArcWidth(CORNER_ARC);
        background.setArcHeight(CORNER_ARC);
        background.setStrokeType(StrokeType.INSIDE);
        background.setStrokeWidth(BORDER_WIDTH);

        // Same overlay discipline as NodeView's: unmanaged, mouse-transparent, stroked INSIDE, so
        // showing it can never move or resize the frame under it.
        selectionBorder.setFill(null);
        selectionBorder.setStroke(SELECTED_BORDER_COLOR);
        selectionBorder.setStrokeWidth(SELECTED_BORDER_WIDTH);
        selectionBorder.setStrokeType(StrokeType.INSIDE);
        selectionBorder.setArcWidth(CORNER_ARC);
        selectionBorder.setArcHeight(CORNER_ARC);
        selectionBorder.setMouseTransparent(true);
        selectionBorder.setManaged(false);
        selectionBorder.setVisible(false);

        buildTitleBar();
        titleBar.getTransforms().add(titleZoomCompensation);
        for (Handle handle : Handle.values()) {
            resizeZones.add(buildResizeZone(handle));
        }
        for (int i = 0; i < 4; i++) {
            Rectangle highlight = new Rectangle();
            highlight.setMouseTransparent(true);
            highlight.setManaged(false);
            highlight.setVisible(false);
            edgeHighlights.add(highlight);
        }

        getChildren().add(background);
        getChildren().addAll(edgeHighlights);
        // Sides first, then corners, so a corner sits on top of the two sides it overlaps and the
        // pixels all three share start the two-axis drag - which is what a pointer aimed at a corner
        // means. Every handle is transparent, so this is about which one takes the press, not paint.
        for (int i = 0; i < resizeZones.size(); i++) {
            if (!Handle.values()[i].isCorner()) {
                getChildren().add(resizeZones.get(i));
            }
        }
        for (int i = 0; i < resizeZones.size(); i++) {
            if (Handle.values()[i].isCorner()) {
                getChildren().add(resizeZones.get(i));
            }
        }
        getChildren().add(selectionBorder);

        setGroup(group);
    }

    /**
     * The title bar, for the canvas to place in its title-overlay layer instead of as a child of
     * this frame — see the class Javadoc. Its position and size are kept in step by {@link #setGroup}
     * regardless of which {@code Parent} it actually sits under.
     */
    public Region getTitleBar() {
        return titleBar;
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

    /**
     * Builds one handle: a strip over the border it drags, filled with {@code Color.TRANSPARENT} and
     * not left unfilled — an unfilled shape is invisible to the pointer, which is the one thing this
     * has to be. Sized by {@link #layoutChildren()}, the only place that knows the frame's size.
     */
    private Rectangle buildResizeZone(Handle handle) {
        Rectangle zone = new Rectangle();
        zone.setFill(Color.TRANSPARENT);
        zone.setManaged(false);
        zone.setCursor(handle.cursor);
        zone.setOnMouseEntered(event -> {
            hoveredHandle = handle;
            refreshResizeHighlight();
        });
        zone.setOnMouseExited(event -> {
            if (hoveredHandle == handle) {
                hoveredHandle = null;
            }
            refreshResizeHighlight();
        });
        zone.setOnMousePressed(event -> {
            // The same reason NodeView's drag focuses the canvas: a gesture that consumes its own
            // press leaves focus wherever it was, and the next Ctrl/Cmd+Z would go there instead.
            controller.focusCanvas();
            resizeOrigin = group;
            draggedHandle = handle;
            refreshResizeHighlight();
            controller.onGroupFrameEditStarted(this);
            event.consume();
        });
        zone.setOnMouseDragged(event -> {
            resizeTo(handle, content.sceneToLocal(event.getSceneX(), event.getSceneY()));
            event.consume();
        });
        zone.setOnMouseReleased(event -> {
            // A drag moves the border out from under the pointer, so the strip's own enter/exit is no
            // longer a reliable account of where the pointer ended up. Settle it from the release
            // point, or a frame just dragged wider keeps its edges lit.
            hoveredHandle = zone.contains(event.getX(), event.getY()) ? handle : null;
            draggedHandle = null;
            refreshResizeHighlight();
            resizeOrigin = null;
            controller.onGroupFrameEdited(this);
            event.consume();
        });
        return zone;
    }

    /**
     * Lights the edges the current handle would move, and keeps them lit for as long as one is being
     * dragged: a gesture in progress should go on showing what it is doing even while the pointer
     * runs ahead of the edge it is pulling.
     */
    private void refreshResizeHighlight() {
        Handle handle = draggedHandle != null ? draggedHandle : hoveredHandle;
        boolean horizontal = handle != null && handle.resizesHorizontal();
        boolean vertical = handle != null && handle.resizesVertical();
        // Top, right, bottom, left - the order edgeHighlights is built and laid out in.
        edgeHighlights.get(0).setVisible(vertical && handle.movesTopEdge());
        edgeHighlights.get(1).setVisible(horizontal && !handle.movesLeftEdge());
        edgeHighlights.get(2).setVisible(vertical && !handle.movesTopEdge());
        edgeHighlights.get(3).setVisible(horizontal && handle.movesLeftEdge());
    }

    /**
     * Applies a resize drag. Moved edges are measured from the rectangle the gesture started on rather
     * than from the previous frame, so a drag that pushes an edge past its opposite one and back
     * again comes out where the pointer is instead of accumulating whatever the minimum-size clamp
     * swallowed on the way. A side handle leaves the axis it doesn't own untouched — a horizontal
     * side never changes {@code top}/{@code height}, a vertical one never changes {@code left}/
     * {@code width}.
     */
    private void resizeTo(Handle handle, Point2D pointerContentPoint) {
        NodeGroup origin = resizeOrigin;
        if (origin == null) {
            return;
        }
        double left = origin.x();
        double top = origin.y();
        double width = origin.width();
        double height = origin.height();

        if (handle.resizesHorizontal()) {
            if (handle.movesLeftEdge()) {
                // Clamped against the fixed right edge, so dragging the left edge rightward stops at
                // the minimum width instead of turning the rectangle inside out.
                left = Math.min(pointerContentPoint.getX(), origin.maxX() - NodeGroup.MIN_WIDTH);
                width = origin.maxX() - left;
            } else {
                width = Math.max(NodeGroup.MIN_WIDTH, pointerContentPoint.getX() - origin.x());
            }
        }
        if (handle.resizesVertical()) {
            if (handle.movesTopEdge()) {
                top = Math.min(pointerContentPoint.getY(), origin.maxY() - NodeGroup.MIN_HEIGHT);
                height = origin.maxY() - top;
            } else {
                height = Math.max(NodeGroup.MIN_HEIGHT, pointerContentPoint.getY() - origin.y());
            }
        }
        setGroup(group.withBounds(left, top, width, height));
    }

    // --- Title bar drag ----------------------------------------------------------

    private void handleDragStart(MouseEvent event) {
        controller.onGroupPressed(this, event.isShiftDown());
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
        layoutTitleBar();
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
        // Before hiding it: an invisible node that still owns focus keeps eating key events. This
        // runs on every exit from the editor — commit, no-op commit, and Escape — because all three
        // leave the field focused and only some of them reach onGroupFrameEdited.
        boolean wasFocused = titleField.isFocused();
        titleField.setVisible(false);
        titleField.setManaged(false);
        titleLabel.setVisible(true);
        titleLabel.setManaged(true);
        if (wasFocused) {
            controller.focusCanvas();
        }
        layoutTitleBar();
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
        // Focus the canvas before the popup opens, so closing it returns the keyboard to the canvas
        // rather than to whatever happened to hold focus when the menu was summoned.
        controller.focusCanvas();
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
        // Full strength, against the border's own BORDER_OPACITY: a lit edge reads as this frame's,
        // and as brighter than the edge it is drawn over.
        for (Rectangle highlight : edgeHighlights) {
            highlight.setFill(color);
        }
        titleLabel.setText(group.title().isEmpty() ? "Group" : group.title());
        titleLabel.setOpacity(group.title().isEmpty() ? 0.6 : 1);
        layoutTitleBar();
        requestLayout();
    }

    /**
     * Places the title bar in content coordinates directly, since it sits in the canvas's
     * title-overlay layer rather than as a child of this {@code Region} — see the class Javadoc.
     * Inset by a corner's width so the title sits beside the top-left corner handle rather than over
     * it, and clamped so it can never grow across the top-right one. The bar lives in a layer above
     * this frame, so anywhere it reaches is a place the border cannot be grabbed — which is the
     * bargain an OS window makes too, its title bar being the move handle.
     */
    private void layoutTitleBar() {
        double available = Math.max(0, group.width() - 2 * RESIZE_CORNER_SPAN);
        double barWidth = Math.min(titleBar.prefWidth(-1), available);
        titleBar.resizeRelocate(group.x() + RESIZE_CORNER_SPAN, group.y(),
                barWidth, titleBar.prefHeight(barWidth));
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

    /**
     * Counteracts the canvas zoom on the title bar alone, so the label stays legible when zoomed
     * out instead of shrinking into an unreadable smear. Below 1:1 the title bar grows just enough
     * to hold its on-screen size steady at the 1:1 size; at 1:1 and above it scales normally with
     * everything else, since the problem this solves only exists when zooming out.
     */
    public void setZoom(double zoom) {
        double factor = zoom > 0 ? Math.max(1, 1 / zoom) : 1;
        titleZoomCompensation.setX(factor);
        titleZoomCompensation.setY(factor);
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

        for (int i = 0; i < resizeZones.size(); i++) {
            Handle handle = Handle.values()[i];
            Rectangle zone = resizeZones.get(i);
            zone.setLayoutX(handle.x(width));
            zone.setLayoutY(handle.y(height));
            zone.setWidth(handle.width(width));
            zone.setHeight(handle.height(height));
        }

        // Top, right, bottom, left - the order refreshResizeHighlight() addresses them in. Each bar
        // is held back from both corners by the border's arc, which it would otherwise overshoot.
        double barLength = Math.max(0, width - 2 * CORNER_ARC);
        double barHeight = Math.max(0, height - 2 * CORNER_ARC);
        place(edgeHighlights.get(0), CORNER_ARC, 0, barLength, RESIZE_EDGE_WIDTH);
        place(edgeHighlights.get(1), width - RESIZE_EDGE_WIDTH, CORNER_ARC, RESIZE_EDGE_WIDTH, barHeight);
        place(edgeHighlights.get(2), CORNER_ARC, height - RESIZE_EDGE_WIDTH, barLength, RESIZE_EDGE_WIDTH);
        place(edgeHighlights.get(3), 0, CORNER_ARC, RESIZE_EDGE_WIDTH, barHeight);
    }

    private static void place(Rectangle rectangle, double x, double y, double width, double height) {
        rectangle.setLayoutX(x);
        rectangle.setLayoutY(y);
        rectangle.setWidth(width);
        rectangle.setHeight(height);
    }
}
