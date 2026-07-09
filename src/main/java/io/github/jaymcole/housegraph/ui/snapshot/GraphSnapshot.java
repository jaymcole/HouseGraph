package io.github.jaymcole.housegraph.ui.snapshot;

import java.util.List;

/** A self-contained slice of the graph (some or all of its nodes, plus the edges between them). */
public record GraphSnapshot(List<ClipboardNode> nodes, List<ClipboardDataEdge> dataEdges, List<ClipboardFlowEdge> flowEdges) {
}
