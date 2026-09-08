package io.github.jaymcole.housegraph.saveformat;

import java.util.List;

/**
 * A self-contained slice of the graph (some or all of its nodes, plus the edges between them) and
 * the group frames drawn behind it.
 *
 * <p>{@code groups} is a view concern the engine has no place for, carried here for the same reason
 * an edge's routing waypoints are: a slice of the canvas is not faithfully copied, pasted or saved
 * without it. {@code GraphLoader} ignores it entirely — only {@code GraphCanvas} draws frames.
 */
public record GraphSnapshot(List<ClipboardNode> nodes, List<ClipboardDataEdge> dataEdges,
                            List<ClipboardFlowEdge> flowEdges, List<NodeGroup> groups) {

    /** A slice with no group frames over it. */
    public GraphSnapshot(List<ClipboardNode> nodes, List<ClipboardDataEdge> dataEdges, List<ClipboardFlowEdge> flowEdges) {
        this(nodes, dataEdges, flowEdges, List.of());
    }
}
