package io.github.jaymcole.housegraph.ui;

import javafx.geometry.Point2D;
import javafx.scene.Group;

/**
 * The endpoint an edge attaches to — the small shared surface of {@link PortView}
 * (data) and {@link FlowPortView} (control flow) that {@link AbstractEdgeView} needs
 * to draw and hit-test a connection, without caring which kind of port it is.
 */
interface EdgeAnchor {

    /** This anchor's centre in the pannable/zoomable content coordinate space. */
    Point2D getCenterInContent(Group content);

    /** The node this anchor belongs to (whose movement the edge follows). */
    NodeView getOwner();
}
