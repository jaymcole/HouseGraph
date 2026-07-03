package io.github.jaymcole.housegraph.ui;

import javafx.geometry.Bounds;

/**
 * Common contract for the visual curves that connect two ports ({@link EdgeView} for
 * data, {@link FlowEdgeView} for control flow), so {@link GraphCanvas} can select,
 * delete, and rubber-band both kinds uniformly.
 */
public interface ConnectionView {

    void setSelected(boolean selected);

    boolean isSelected();

    void delete();

    boolean touchesNode(NodeView node);

    Bounds getBoundsInParent();

    /**
     * Whether the connection's actual rendered line passes through {@code rect} (in
     * content coordinates), for rubber-band selection — as opposed to the looser
     * {@link #getBoundsInParent()} box, which for a curved edge is mostly empty space.
     */
    boolean intersects(Bounds rect);
}
