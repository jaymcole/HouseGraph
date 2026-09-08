package io.github.jaymcole.housegraph.saveformat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grouping rules, exercised without a canvas — which is the point of keeping them on the record
 * rather than in {@code GroupView}.
 */
class NodeGroupTest {

    private static NodeGroup frame(double x, double y, double width, double height) {
        return new NodeGroup("", x, y, width, height, NodeGroup.DEFAULT_COLOR);
    }

    // --- What a frame commands ------------------------------------------------------------------

    @Test
    void aRectangleWhollyInsideIsCommanded() {
        assertTrue(frame(0, 0, 400, 300).commands(50, 50, 100, 80));
    }

    @Test
    void aRectangleTouchingTheEdgeFromInsideIsStillCommanded() {
        // Flush against the corner counts: a node dragged to sit exactly on the border is in.
        assertTrue(frame(0, 0, 400, 300).commands(0, 0, 400, 300));
    }

    @Test
    void aRectangleHangingOverAnyEdgeIsNotCommanded() {
        NodeGroup group = frame(0, 0, 400, 300);

        assertFalse(group.commands(-10, 50, 100, 80), "over the left edge");
        assertFalse(group.commands(50, -10, 100, 80), "over the top edge");
        assertFalse(group.commands(350, 50, 100, 80), "over the right edge");
        assertFalse(group.commands(50, 250, 100, 80), "over the bottom edge");
    }

    @Test
    void aRectangleEntirelyOutsideIsNotCommanded() {
        assertFalse(frame(0, 0, 400, 300).commands(1000, 1000, 100, 80));
    }

    // --- Frame over frame -----------------------------------------------------------------------

    @Test
    void theLargerFrameCommandsTheSmallerOneAndNotTheReverse() {
        NodeGroup outer = frame(0, 0, 600, 400);
        NodeGroup inner = frame(50, 50, 200, 150);

        assertTrue(outer.commands(inner));
        assertFalse(inner.commands(outer));
    }

    @Test
    void twoFramesOnTheSameRectangleCommandEachOtherInNeitherDirection() {
        // Otherwise "apply this to everything I contain" would run in a circle.
        NodeGroup one = frame(0, 0, 400, 300);
        NodeGroup other = frame(0, 0, 400, 300);

        assertFalse(one.commands(other));
        assertFalse(other.commands(one));
    }

    @Test
    void twoOverlappingFramesCommandEachOtherInNeitherDirection() {
        NodeGroup left = frame(0, 0, 400, 300);
        NodeGroup right = frame(200, 100, 400, 300);

        assertFalse(left.commands(right));
        assertFalse(right.commands(left));
    }

    @Test
    void aNodeInTheOverlapOfTwoFramesIsCommandedByBoth() {
        NodeGroup left = frame(0, 0, 400, 300);
        NodeGroup right = frame(200, 100, 400, 300);

        assertTrue(left.commands(250, 150, 60, 40));
        assertTrue(right.commands(250, 150, 60, 40));
    }

    @Test
    void nestingNeedsNoRecursion() {
        // A node inside the inner frame is inside the outer one by the same containment test, which
        // is why one pass over the frames being dragged already reaches every depth.
        NodeGroup outer = frame(0, 0, 600, 400);
        NodeGroup inner = frame(50, 50, 200, 150);

        assertTrue(inner.commands(60, 60, 40, 30));
        assertTrue(outer.commands(60, 60, 40, 30));
    }

    // --- Paint order ----------------------------------------------------------------------------

    @Test
    void largestFirstPutsTheSmallestFrameLastSoItPaintsOnTop() {
        NodeGroup big = frame(0, 0, 600, 400);
        NodeGroup middle = frame(0, 0, 400, 300);
        NodeGroup small = frame(0, 0, 200, 150);

        List<NodeGroup> ordered = new ArrayList<>(List.of(small, big, middle));
        ordered.sort(NodeGroup.LARGEST_FIRST);

        assertEquals(List.of(big, middle, small), ordered);
    }

    // --- Normalisation --------------------------------------------------------------------------

    @Test
    void aDegenerateRectangleIsClampedToSomethingGrabbable() {
        NodeGroup group = frame(0, 0, 1, 1);

        assertEquals(NodeGroup.MIN_WIDTH, group.width());
        assertEquals(NodeGroup.MIN_HEIGHT, group.height());
    }

    @Test
    void aNullTitleOrColourNormalisesRatherThanReachingTheCanvas() {
        NodeGroup group = new NodeGroup(null, 0, 0, 400, 300, null);

        assertEquals("", group.title());
        assertEquals(NodeGroup.DEFAULT_COLOR, group.color());
    }

    @Test
    void aBlankColourFallsBackToTheDefault() {
        assertEquals(NodeGroup.DEFAULT_COLOR, new NodeGroup("x", 0, 0, 400, 300, "  ").color());
    }

    // --- Derivations ----------------------------------------------------------------------------

    @Test
    void movingKeepsEverythingButThePosition() {
        NodeGroup moved = new NodeGroup("Kitchen", 10, 20, 400, 300, "#98c379").movedBy(5, -7);

        assertEquals(new NodeGroup("Kitchen", 15, 13, 400, 300, "#98c379"), moved);
    }

    @Test
    void resizingKeepsTheTitleAndColour() {
        NodeGroup resized = new NodeGroup("Kitchen", 10, 20, 400, 300, "#98c379").withBounds(0, 0, 200, 150);

        assertEquals("Kitchen", resized.title());
        assertEquals("#98c379", resized.color());
        assertEquals(200, resized.width());
    }
}
