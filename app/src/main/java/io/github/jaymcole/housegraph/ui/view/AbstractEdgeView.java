package io.github.jaymcole.housegraph.ui.view;

import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared visuals and behaviour of the two edge kinds ({@link EdgeView} for data,
 * {@link FlowEdgeView} for control flow): a curve between two {@link EdgeAnchor}s that
 * can be selected, pulsed on traversal, and manually re-routed.
 * <p>
 * <b>Routing.</b> Double-clicking the edge drops a draggable waypoint; the curve then
 * bends smoothly through each waypoint in order, so a line can be steered around nodes
 * it would otherwise cross. Waypoints are purely visual — they never touch the graph
 * model or execution. With no waypoints the edge keeps its original smooth
 * port-to-port curve. Double-click a waypoint's handle to remove it.
 * <p>
 * <b>Interaction.</b> The visible curve is thin and mouse-transparent; a wider,
 * fully transparent "hit" path laid over the same route is what actually catches
 * clicks, so the ~2px line needn't be hit precisely. A single click selects the edge
 * (so it can be deleted with the keyboard — there's no longer a delete button); a
 * double-click adds a waypoint. Selection and undo-recording are delegated back to the
 * canvas through an {@link EdgeInteractionListener}, since both are canvas-wide concerns.
 */
public abstract class AbstractEdgeView extends Group implements ConnectionView {

    private static final Color SELECTED_STROKE = Color.web("#e5c07b");
    private static final Color PULSE_STROKE = Color.web("#61dafb");
    private static final Color HANDLE_STROKE = Color.web("#282c34");
    private static final Duration PULSE_DURATION = Duration.millis(400);
    private static final double HANDLE_RADIUS = 5;
    private static final double HIT_WIDTH = 12;
    /**
     * How far the click-band stops short of each endpoint. The visible curve still
     * runs all the way to the port, but the band doesn't — so the port/flow anchor
     * underneath it stays clickable (e.g. to start a second edge from an anchor that
     * already has one), which it wouldn't be if a 12px band blanketed it.
     */
    private static final double HIT_ENDPOINT_INSET = 16;
    /** Samples per cubic segment when flattening the curve for rubber-band hit-testing — enough to stay within a pixel or two of the drawn line. */
    private static final int FLATTEN_STEPS_PER_SEGMENT = 12;

    protected final EdgeAnchor source;
    protected final EdgeAnchor target;
    protected final Group content;
    private final EdgeInteractionListener listener;
    private final Runnable onDelete;

    private final Path curve = new Path();
    private final Path hitArea = new Path();
    private final Group handleLayer = new Group();
    private final List<Point2D> waypoints = new ArrayList<>();

    /** The waypoint list snapshotted at the start of a handle drag, so the whole drag records as one undo step. */
    private List<Point2D> waypointDragBefore;

    private boolean selected = false;
    private PauseTransition pulseRevert;

    protected AbstractEdgeView(EdgeAnchor source, EdgeAnchor target, Group content, EdgeInteractionListener listener, Runnable onDelete) {
        this.source = source;
        this.target = target;
        this.content = content;
        this.listener = listener;
        this.onDelete = onDelete;

        curve.setFill(null);
        curve.setMouseTransparent(true);
        curve.setStrokeLineCap(StrokeLineCap.ROUND);

        // Invisible but mouse-catching band along the same route: null fill so only the
        // (wide, transparent) stroke picks, giving a comfortable click target.
        hitArea.setFill(null);
        hitArea.setStroke(Color.TRANSPARENT);
        hitArea.setStrokeWidth(HIT_WIDTH);
        hitArea.setStrokeLineCap(StrokeLineCap.ROUND);
        hitArea.setCursor(Cursor.HAND);
        hitArea.setOnMousePressed(event -> {
            // Consume so a press on the edge doesn't fall through to the canvas and
            // start a rubber-band / clear the current selection.
            if (event.getButton() == MouseButton.PRIMARY) {
                event.consume();
            }
        });
        hitArea.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (event.getClickCount() == 2) {
                List<Point2D> before = new ArrayList<>(waypoints);
                addWaypoint(content.sceneToLocal(event.getSceneX(), event.getSceneY()));
                notifyWaypointsChanged(before);
            } else {
                listener.selectEdge(this);
            }
            event.consume();
        });

        getChildren().addAll(curve, hitArea, handleLayer);

        // Redraw whenever either endpoint's node moves *or* resizes. Tracking
        // boundsInParent (rather than just layoutX/Y) is what catches a resize: a node
        // that grows - e.g. the Animal Classifier widening to fit its result label -
        // keeps its top-left layout position but shifts its right-hand output ports, so
        // only a bounds change reflects that the anchors moved. boundsInParent is
        // recomputed after the node's own children are laid out, so the anchor circles
        // are already at their new positions when this fires (unlike width/height, which
        // change mid-layout, before the ports have been repositioned).
        source.getOwner().boundsInParentProperty().addListener((obs, o, n) -> updatePath());
        target.getOwner().boundsInParentProperty().addListener((obs, o, n) -> updatePath());

        applyStyle();
        updatePath();
    }

    /** The normal (unselected, not-pulsing) stroke colour — data blue vs flow green. */
    protected abstract Color baseStroke();

    public void updatePath() {
        List<Point2D> points = routePoints();
        curve.getElements().setAll(buildRoute(points));
        // Same route for the click-band, but pulled back from both ports so it doesn't
        // cover the anchors at the ends (see HIT_ENDPOINT_INSET).
        hitArea.getElements().setAll(buildRoute(insetEndpoints(points, HIT_ENDPOINT_INSET)));
    }

    /** Full ordered route: source anchor, every waypoint, target anchor. */
    private List<Point2D> routePoints() {
        List<Point2D> points = new ArrayList<>();
        points.add(source.getCenterInContent(content));
        points.addAll(waypoints);
        points.add(target.getCenterInContent(content));
        return points;
    }

    /** A copy of the route with the first/last point pulled {@code inset} px toward its neighbour, freeing the anchors underneath. */
    private static List<Point2D> insetEndpoints(List<Point2D> points, double inset) {
        if (points.size() < 2) {
            return points;
        }
        List<Point2D> result = new ArrayList<>(points);
        int last = points.size() - 1;
        result.set(0, moveToward(points.get(0), points.get(1), inset));
        result.set(last, moveToward(points.get(last), points.get(last - 1), inset));
        return result;
    }

    /** {@code from} shifted {@code distance} px toward {@code toward}; the midpoint if they're closer than that (never overshoots). */
    private static Point2D moveToward(Point2D from, Point2D toward, double distance) {
        double length = from.distance(toward);
        if (length <= distance) {
            return from.midpoint(toward);
        }
        double t = distance / length;
        return new Point2D(
                from.getX() + (toward.getX() - from.getX()) * t,
                from.getY() + (toward.getY() - from.getY()) * t);
    }

    /**
     * Every segment (port→waypoint, waypoint→waypoint, waypoint→port) uses the same
     * cubic as a plain port-to-port edge: control points pushed horizontally so the
     * curve leaves each point heading outward and arrives heading inward. With no
     * waypoints that's exactly one cubic — the original look — and with waypoints it's
     * a smooth chain of them (the shared horizontal tangents keep each join kink-free).
     */
    private static List<PathElement> buildRoute(List<Point2D> points) {
        List<PathElement> elements = new ArrayList<>();
        Point2D start = points.get(0);
        elements.add(new MoveTo(start.getX(), start.getY()));
        for (int i = 1; i < points.size(); i++) {
            Point2D from = points.get(i - 1);
            Point2D to = points.get(i);
            Point2D[] controls = controlPoints(from, to);
            elements.add(new CubicCurveTo(
                    controls[0].getX(), controls[0].getY(),
                    controls[1].getX(), controls[1].getY(),
                    to.getX(), to.getY()));
        }
        return elements;
    }

    /**
     * The two cubic control points for the segment {@code from}→{@code to}, pushed
     * horizontally by a fixed offset so the curve leaves and enters each point on a
     * horizontal tangent. Shared by rendering ({@link #buildRoute}) and hit-testing
     * ({@link #flatten}) so selection is tested against the very same curve that's drawn.
     */
    private static Point2D[] controlPoints(Point2D from, Point2D to) {
        double offset = Math.max(50, Math.abs(to.getX() - from.getX()) / 2);
        return new Point2D[]{
                new Point2D(from.getX() + offset, from.getY()),
                new Point2D(to.getX() - offset, to.getY())
        };
    }

    private void addWaypoint(Point2D point) {
        // Insert into whichever segment of the current route runs nearest the click, so
        // the new point lands "in line" rather than always at the end of the chain.
        List<Point2D> points = routePoints();
        int bestSegment = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < points.size() - 1; i++) {
            double distance = distanceToSegment(point, points.get(i), points.get(i + 1));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestSegment = i;
            }
        }
        waypoints.add(bestSegment, point);
        rebuildHandles();
        updatePath();
    }

    private void rebuildHandles() {
        handleLayer.getChildren().clear();
        for (int i = 0; i < waypoints.size(); i++) {
            handleLayer.getChildren().add(createHandle(i));
        }
    }

    private Circle createHandle(int index) {
        Point2D at = waypoints.get(index);
        Circle handle = new Circle(at.getX(), at.getY(), HANDLE_RADIUS, baseStroke());
        handle.setStroke(HANDLE_STROKE);
        handle.setStrokeWidth(1);
        handle.setCursor(Cursor.MOVE);
        handle.setOnMousePressed(event -> {
            waypointDragBefore = new ArrayList<>(waypoints);
            event.consume();
        });
        handle.setOnMouseDragged(event -> {
            moveWaypoint(index, content.sceneToLocal(event.getSceneX(), event.getSceneY()));
            event.consume();
        });
        handle.setOnMouseReleased(event -> {
            if (waypointDragBefore != null && !waypointDragBefore.equals(waypoints)) {
                notifyWaypointsChanged(waypointDragBefore);
            }
            waypointDragBefore = null;
            event.consume();
        });
        handle.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                List<Point2D> before = new ArrayList<>(waypoints);
                waypoints.remove(index);
                rebuildHandles();
                updatePath();
                notifyWaypointsChanged(before);
            }
            event.consume();
        });
        return handle;
    }

    /** Moves the waypoint at {@code index} (and its handle) to a new spot, redrawing the route. */
    private void moveWaypoint(int index, Point2D point) {
        waypoints.set(index, point);
        if (index < handleLayer.getChildren().size()) {
            Circle handle = (Circle) handleLayer.getChildren().get(index);
            handle.setCenterX(point.getX());
            handle.setCenterY(point.getY());
        }
        updatePath();
    }

    private void notifyWaypointsChanged(List<Point2D> before) {
        listener.waypointsChanged(this, before, new ArrayList<>(waypoints));
    }

    /** Distance from point {@code p} to the segment {@code a}–{@code b}, clamped to its ends. */
    private static double distanceToSegment(Point2D p, Point2D a, Point2D b) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return p.distance(a);
        }
        double t = ((p.getX() - a.getX()) * dx + (p.getY() - a.getY()) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return p.distance(a.getX() + t * dx, a.getY() + t * dy);
    }

    /** A defensive copy of this edge's routing waypoints, in content coordinates (for save/copy). */
    public List<Point2D> getWaypoints() {
        return new ArrayList<>(waypoints);
    }

    /** Replaces the routing waypoints wholesale — for restoring from a save, paste, or undo. */
    public void setWaypoints(List<Point2D> points) {
        waypoints.clear();
        waypoints.addAll(points);
        rebuildHandles();
        updatePath();
    }

    @Override
    public void delete() {
        content.getChildren().remove(this);
        onDelete.run();
    }

    @Override
    public boolean touchesNode(NodeView node) {
        return source.getOwner() == node || target.getOwner() == node;
    }

    @Override
    public boolean intersects(Bounds rect) {
        // Cheap bounding-box reject first: if the loose box misses, the curve can't hit,
        // so an edge nowhere near the band never pays for flattening.
        if (!getBoundsInParent().intersects(rect)) {
            return false;
        }
        List<Point2D> flat = flatten(routePoints());
        for (int i = 1; i < flat.size(); i++) {
            if (segmentIntersectsRect(flat.get(i - 1), flat.get(i), rect)) {
                return true;
            }
        }
        return false;
    }

    /** Samples the cubic route into a dense polyline, so geometry tests run against (a close approximation of) the drawn curve. */
    private static List<Point2D> flatten(List<Point2D> route) {
        List<Point2D> flat = new ArrayList<>();
        flat.add(route.get(0));
        for (int i = 1; i < route.size(); i++) {
            Point2D from = route.get(i - 1);
            Point2D to = route.get(i);
            Point2D[] controls = controlPoints(from, to);
            for (int step = 1; step <= FLATTEN_STEPS_PER_SEGMENT; step++) {
                flat.add(cubicPoint(from, controls[0], controls[1], to, (double) step / FLATTEN_STEPS_PER_SEGMENT));
            }
        }
        return flat;
    }

    /** Point on the cubic Bézier (p0,p1,p2,p3) at parameter t. */
    private static Point2D cubicPoint(Point2D p0, Point2D p1, Point2D p2, Point2D p3, double t) {
        double u = 1 - t;
        double w0 = u * u * u;
        double w1 = 3 * u * u * t;
        double w2 = 3 * u * t * t;
        double w3 = t * t * t;
        return new Point2D(
                w0 * p0.getX() + w1 * p1.getX() + w2 * p2.getX() + w3 * p3.getX(),
                w0 * p0.getY() + w1 * p1.getY() + w2 * p2.getY() + w3 * p3.getY());
    }

    /** Whether segment a–b touches the rectangle: an endpoint inside, or a crossing of any of the four sides. */
    private static boolean segmentIntersectsRect(Point2D a, Point2D b, Bounds rect) {
        if (rect.contains(a) || rect.contains(b)) {
            return true;
        }
        Point2D topLeft = new Point2D(rect.getMinX(), rect.getMinY());
        Point2D topRight = new Point2D(rect.getMaxX(), rect.getMinY());
        Point2D bottomRight = new Point2D(rect.getMaxX(), rect.getMaxY());
        Point2D bottomLeft = new Point2D(rect.getMinX(), rect.getMaxY());
        return segmentsCross(a, b, topLeft, topRight)
                || segmentsCross(a, b, topRight, bottomRight)
                || segmentsCross(a, b, bottomRight, bottomLeft)
                || segmentsCross(a, b, bottomLeft, topLeft);
    }

    /** Proper-crossing test for segments p1–p2 and p3–p4 (collinear touching is treated as no-cross; fine for selection). */
    private static boolean segmentsCross(Point2D p1, Point2D p2, Point2D p3, Point2D p4) {
        double d1 = cross(p3, p4, p1);
        double d2 = cross(p3, p4, p2);
        double d3 = cross(p1, p2, p3);
        double d4 = cross(p1, p2, p4);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    /** Z-component of (b−a)×(c−a): the signed area / orientation of the turn a→b→c. */
    private static double cross(Point2D a, Point2D b, Point2D c) {
        return (b.getX() - a.getX()) * (c.getY() - a.getY()) - (b.getY() - a.getY()) * (c.getX() - a.getX());
    }

    @Override
    public void setSelected(boolean selected) {
        this.selected = selected;
        applyStyle();
    }

    @Override
    public boolean isSelected() {
        return selected;
    }

    private void applyStyle() {
        curve.setStroke(selected ? SELECTED_STROKE : baseStroke());
        curve.setStrokeWidth(selected ? 3 : 2);
    }

    /** Briefly flashes the curve to show traversal, then reverts to its normal style. */
    public void pulse() {
        curve.setStroke(PULSE_STROKE);
        curve.setStrokeWidth(4);
        if (pulseRevert != null) {
            pulseRevert.stop();
        }
        pulseRevert = new PauseTransition(PULSE_DURATION);
        pulseRevert.setOnFinished(event -> applyStyle());
        pulseRevert.play();
    }
}
