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
}
