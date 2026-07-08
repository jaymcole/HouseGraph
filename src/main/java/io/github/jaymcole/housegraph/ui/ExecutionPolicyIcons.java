package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.ExecutionPolicy;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;

/**
 * Small vector glyphs for each {@link ExecutionPolicy}, drawn from primitive JavaFX
 * shapes (so they stay crisp at any zoom and need no image assets). {@link NodeView}
 * renders one beside a node's title to show its policy at a glance.
 * <p>
 * Each glyph is laid out in a fixed {@value #SIZE}×{@value #SIZE} box and tinted a
 * distinct accent colour so the four policies are quick to tell apart:
 * <ul>
 *   <li>Drop — a prohibition sign (ringed slash): "ignore".</li>
 *   <li>Restart — a circular arrow: "cancel and run again".</li>
 *   <li>Queue — three stacked lines: "line up and run in turn".</li>
 *   <li>Parallel — two upright bars: "run alongside".</li>
 * </ul>
 */
final class ExecutionPolicyIcons {

    /** The glyph box edge, in px; also the drawing coordinate space (0..SIZE on each axis). */
    static final double SIZE = 14;

    private static final double STROKE = 1.6;
    private static final double CENTER = SIZE / 2;
    private static final double RING_RADIUS = 6;

    private static final Color DROP_COLOR = Color.web("#e06c75");
    private static final Color RESTART_COLOR = Color.web("#61afef");
    private static final Color QUEUE_COLOR = Color.web("#98c379");
    private static final Color PARALLEL_COLOR = Color.web("#c678dd");

    private ExecutionPolicyIcons() {
    }

    /**
     * A fresh icon {@link Node} for {@code policy}, centred in a fixed {@value #SIZE}px box
     * (so successive policies align identically when swapped in place). The whole box is
     * pickable, so a tooltip installed on it responds anywhere over the glyph.
     *
     * @param policy the policy to depict
     * @return a new, self-contained icon node
     */
    static Node create(ExecutionPolicy policy) {
        Group glyph = new Group();
        switch (policy) {
            case DROP -> {
                glyph.getChildren().add(stroked(new Circle(CENTER, CENTER, RING_RADIUS), DROP_COLOR));
                // Diagonal bar across the ring, kept inside the circle's radius.
                glyph.getChildren().add(stroked(new Line(2.9, 2.9, 11.1, 11.1), DROP_COLOR));
            }
            case RESTART -> buildRestart(glyph);
            case QUEUE -> {
                glyph.getChildren().add(stroked(new Line(2.5, 4, 11.5, 4), QUEUE_COLOR));
                glyph.getChildren().add(stroked(new Line(2.5, 7, 11.5, 7), QUEUE_COLOR));
                glyph.getChildren().add(stroked(new Line(2.5, 10, 11.5, 10), QUEUE_COLOR));
            }
            case PARALLEL -> {
                glyph.getChildren().add(stroked(new Line(5, 2.5, 5, 11.5), PARALLEL_COLOR));
                glyph.getChildren().add(stroked(new Line(9, 2.5, 9, 11.5), PARALLEL_COLOR));
            }
        }

        StackPane box = new StackPane(glyph);
        box.setMinSize(SIZE, SIZE);
        box.setPrefSize(SIZE, SIZE);
        box.setMaxSize(SIZE, SIZE);
        box.setPickOnBounds(true);
        return box;
    }

    /** A short human label ("Queue — run next…") shared by the icon tooltip and the policy menu. */
    static String label(ExecutionPolicy policy) {
        return switch (policy) {
            case DROP -> "Drop — ignore triggers while running";
            case RESTART -> "Restart — cancel & rerun with new inputs";
            case QUEUE -> "Queue — run next (latest wins)";
            case PARALLEL -> "Parallel — run every trigger at once";
        };
    }

    /**
     * Draws the circular-arrow glyph: an open ring (gap at the top) plus a filled arrowhead
     * at its leading end, positioned and oriented from the arc's own geometry so the head
     * always sits tangent to the ring rather than being eyeballed.
     */
    private static void buildRestart(Group glyph) {
        double radius = 5.5;
        double startDeg = 110;
        double lengthDeg = 300; // leaves a 60° gap at the top

        Arc arc = new Arc(CENTER, CENTER, radius, radius, startDeg, lengthDeg);
        arc.setType(ArcType.OPEN);
        stroked(arc, RESTART_COLOR);
        glyph.getChildren().add(arc);

        // Leading end of the sweep, and the tangent (direction of travel) there. JavaFX arc
        // angles increase counter-clockwise from 3 o'clock; a point at angle θ sits at
        // (cx + r·cosθ, cy − r·sinθ), so the unit tangent for increasing θ is (−sinθ, −cosθ).
        double endRad = Math.toRadians(startDeg + lengthDeg);
        double ex = CENTER + radius * Math.cos(endRad);
        double ey = CENTER - radius * Math.sin(endRad);
        double tx = -Math.sin(endRad);
        double ty = -Math.cos(endRad);
        double px = -ty; // perpendicular to the tangent
        double py = tx;

        double headLength = 4.2;
        double headHalfWidth = 2.4;
        double tipX = ex + tx * headLength * 0.5;
        double tipY = ey + ty * headLength * 0.5;
        double baseX = ex - tx * headLength * 0.5;
        double baseY = ey - ty * headLength * 0.5;

        Polygon head = new Polygon(
                tipX, tipY,
                baseX + px * headHalfWidth, baseY + py * headHalfWidth,
                baseX - px * headHalfWidth, baseY - py * headHalfWidth);
        head.setFill(RESTART_COLOR);
        glyph.getChildren().add(head);
    }

    private static <T extends Shape> T stroked(T shape, Color color) {
        shape.setStroke(color);
        shape.setStrokeWidth(STROKE);
        shape.setStrokeLineCap(StrokeLineCap.ROUND);
        shape.setFill(null);
        return shape;
    }
}
