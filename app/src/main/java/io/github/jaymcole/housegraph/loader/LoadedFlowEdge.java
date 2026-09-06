package io.github.jaymcole.housegraph.loader;

import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.saveformat.ClipboardFlowEdge;

/** Flow-edge counterpart of {@link LoadedDataEdge}: the snapshot entry, and the live {@link FlowEdge} it produced. */
public record LoadedFlowEdge(ClipboardFlowEdge entry, FlowEdge edge) {
}
