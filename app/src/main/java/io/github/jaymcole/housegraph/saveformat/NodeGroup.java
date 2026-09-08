package io.github.jaymcole.housegraph.saveformat;

import java.util.Comparator;

/**
 * One labelled rectangle drawn behind the graph, grouping whatever happens to sit inside it.
 *
 * <h2>Membership is geometry, not a stored pointer</h2>
 * A group holds no list of what it contains. It <b>commands</b> a node — or another group —
 * exactly while that thing's rectangle lies entirely within its own, and the answer is recomputed
 * at the moment an action needs it. Dragging one node out of a frame is therefore all it takes to
 * remove it from that frame, and there is no membership to keep in step with the canvas, to
 * migrate, or to have go stale against it. It is also why a group carries no id: nothing refers to
 * one.
 *
 * <h2>Nesting is by size</h2>
 * Containment already implies the larger of two nested frames is the one that commands the other,
 * and {@link #commands(NodeGroup)} states that directly: strictly larger, and containing. Two
 * frames laid on exactly the same rectangle therefore command each other in neither direction,
 * which is what keeps "apply this to everything I contain" from running in a circle. Two frames
 * that merely overlap contain each other in neither direction either; a node in the overlap is
 * commanded by both, and dragging either one takes it along.
 *
 * <p>{@link #LARGEST_FIRST} is the matching paint order: largest first, so the smallest frame — the
 * innermost, most specific one — is drawn last and reads on top. That ordering is over the whole
 * set rather than only over nested pairs, so two overlapping frames stack predictably too.
 *
 * <p>A plain value with no canvas in it, so the rules above are unit-testable headlessly and the
 * whole frame round-trips as one immutable thing. {@code ui/view/GroupView} draws it and
 * {@code GraphFileIO} writes it; see {@code docs/engine/ui-layer.md} and
 * {@code docs/engine/save-format.md}.
 *
 * @param title  the label shown at the frame's top-left; never null, may be empty
 * @param x      left edge, in canvas coordinates
 * @param y      top edge, in canvas coordinates
 * @param width  at least {@link #MIN_WIDTH}
 * @param height at least {@link #MIN_HEIGHT}
 * @param color  the frame's colour as a CSS hex string; never null
 */
public record NodeGroup(String title, double x, double y, double width, double height, String color) {

    /** What a frame is drawn in until the user picks something else. */
    public static final String DEFAULT_COLOR = "#61afef";

    /** Small enough to frame a single node, large enough to leave its title and corner grips reachable. */
    public static final double MIN_WIDTH = 80;

    /** @see #MIN_WIDTH */
    public static final double MIN_HEIGHT = 56;

    /**
     * Paint order: largest first, so the smallest frame is drawn last and reads on top of every
     * frame enclosing it. Ties are left in the order they came in, which keeps a re-save of an
     * unchanged canvas byte-identical.
     */
    public static final Comparator<NodeGroup> LARGEST_FIRST =
            Comparator.comparingDouble(NodeGroup::area).reversed();

    /**
     * Normalises the two things a caller can get wrong — a null title or colour, and a rectangle
     * dragged smaller than it can be used at — so every {@code NodeGroup} in existence is drawable
     * and every resize gesture is clamped by construction rather than at each call site.
     */
    public NodeGroup {
        title = title == null ? "" : title;
        color = color == null || color.isBlank() ? DEFAULT_COLOR : color;
        width = Math.max(MIN_WIDTH, width);
        height = Math.max(MIN_HEIGHT, height);
    }

    /** A default-sized, untitled frame with its top-left corner at {@code (x, y)}. */
    public static NodeGroup at(double x, double y) {
        return new NodeGroup("", x, y, 320, 220, DEFAULT_COLOR);
    }

    /** How much canvas this frame covers — the ordering {@link #LARGEST_FIRST} and {@link #commands(NodeGroup)} use. */
    public double area() {
        return width * height;
    }

    /** The right edge, in canvas coordinates. */
    public double maxX() {
        return x + width;
    }

    /** The bottom edge, in canvas coordinates. */
    public double maxY() {
        return y + height;
    }

    /**
     * Whether this frame commands the rectangle {@code (rx, ry, rw, rh)} — that is, whether the
     * rectangle lies entirely within it, so an action on the frame carries it along.
     *
     * <p>Entirely, not merely overlapping: a node half out of a frame is on its way out, and taking
     * it along would make dragging a frame move things the user can see are not in it.
     */
    public boolean commands(double rx, double ry, double rw, double rh) {
        return rx >= x && ry >= y && rx + rw <= maxX() && ry + rh <= maxY();
    }

    /**
     * Whether this frame commands {@code other}: strictly larger, and containing it. The size test
     * is what makes the relation one-way — see the class Javadoc.
     */
    public boolean commands(NodeGroup other) {
        return other != null && area() > other.area() && commands(other.x, other.y, other.width, other.height);
    }

    /** The same frame shifted by a drag delta. */
    public NodeGroup movedBy(double deltaX, double deltaY) {
        return new NodeGroup(title, x + deltaX, y + deltaY, width, height, color);
    }

    /** The same frame over a new rectangle, keeping its title and colour — what a resize produces. */
    public NodeGroup withBounds(double x, double y, double width, double height) {
        return new NodeGroup(title, x, y, width, height, color);
    }

    /** The same frame relabelled. */
    public NodeGroup withTitle(String title) {
        return new NodeGroup(title, x, y, width, height, color);
    }

    /** The same frame recoloured. */
    public NodeGroup withColor(String color) {
        return new NodeGroup(title, x, y, width, height, color);
    }
}
