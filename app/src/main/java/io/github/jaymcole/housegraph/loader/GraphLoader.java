package io.github.jaymcole.housegraph.loader;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardDataEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardNode;
import io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Turns a {@link GraphSnapshot} into live nodes and edges on a {@link NodeGraph}, with no canvas
 * involved. The step between {@code GraphFileIO}'s JSON and a running graph.
 *
 * <h2>What one load does</h2>
 * Each snapshot entry is handed to {@code nodeFactory} — which duplicates a clipboard node for a
 * paste, or just unwraps the freshly parsed one for a load — and the result is registered on the
 * graph in snapshot order. Then each saved edge is resolved against those nodes and registered.
 * A {@link GraphLoadListener} sees every node as it is built, before it joins the graph.
 *
 * <h2>Positional identity, resolved once</h2>
 * A snapshot references nodes by index into its own node list and ports by index into the node's
 * variable and flow-port lists (see {@code docs/engine/save-format.md}). Both are resolved here,
 * against the node model itself: a host that renders ports never has to re-derive the mapping, so
 * a view's port order cannot rewire a saved graph.
 *
 * <h2>One bad edge costs only itself</h2>
 * A save file can outlive the node contract it was written against, leaving stale endpoints. Every
 * edge is resolved in isolation and one that does not resolve is dropped with a warning, never
 * aborting the pass. A node the factory could not build leaves a {@code null} slot in
 * {@link LoadedGraph#nodes()} so later indices still land on the node they were saved against.
 */
public final class GraphLoader {

    private static final Logger log = Log.get(GraphLoader.class);

    private GraphLoader() {
    }

    /** Loads a snapshot with nothing watching — the headless case. */
    public static LoadedGraph load(GraphSnapshot snapshot, Function<ClipboardNode, BaseNode> nodeFactory, NodeGraph graph) {
        return load(snapshot, nodeFactory, graph, null);
    }

    /**
     * Loads {@code snapshot} into {@code graph}.
     *
     * <h4>Ordering</h4>
     * Nodes first, in snapshot order, then data edges, then flow edges — a node is registered (and
     * so activated) before anything is wired to it, and {@code listener} is told about each node
     * one step earlier still.
     *
     * @param nodeFactory builds the node for one entry, or returns {@code null} to leave the slot
     *                    empty; a throw from it aborts the load, since that is a broken factory
     *                    rather than a stale save file
     * @param listener    notified as each node is built, before it joins the graph, or {@code null}
     * @return the nodes (index-aligned with the snapshot, nulls kept) and the edges that resolved
     */
    public static LoadedGraph load(GraphSnapshot snapshot,
                                   Function<ClipboardNode, BaseNode> nodeFactory,
                                   NodeGraph graph,
                                   GraphLoadListener listener) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(nodeFactory, "nodeFactory");
        Objects.requireNonNull(graph, "graph");

        List<BaseNode> nodes = new ArrayList<>(snapshot.nodes().size());
        for (ClipboardNode entry : snapshot.nodes()) {
            BaseNode node = nodeFactory.apply(entry);
            nodes.add(node);
            if (node == null) {
                continue;
            }
            if (listener != null) {
                listener.nodeBuilt(entry, node);
            }
            graph.addNode(node);
        }

        List<LoadedDataEdge> dataEdges = new ArrayList<>();
        for (ClipboardDataEdge entry : snapshot.dataEdges()) {
            try {
                Edge edge = connectDataEdge(graph, nodes, entry);
                if (edge != null) {
                    dataEdges.add(new LoadedDataEdge(entry, edge));
                }
            } catch (RuntimeException e) {
                log.warn("Skipping data edge that failed to reconnect: {}", e.toString());
            }
        }

        List<LoadedFlowEdge> flowEdges = new ArrayList<>();
        for (ClipboardFlowEdge entry : snapshot.flowEdges()) {
            try {
                FlowEdge flowEdge = connectFlowEdge(graph, nodes, entry);
                if (flowEdge != null) {
                    flowEdges.add(new LoadedFlowEdge(entry, flowEdge));
                }
            } catch (RuntimeException e) {
                log.warn("Skipping flow edge that failed to reconnect: {}", e.toString());
            }
        }

        // nodes holds nulls by design, so it can't be List.copyOf'd.
        return new LoadedGraph(Collections.unmodifiableList(nodes), List.copyOf(dataEdges), List.copyOf(flowEdges));
    }

    /**
     * Wires one saved data edge, or returns null with a warning if either endpoint no longer
     * resolves (a node index past the loaded count, a {@code null} slot, or a port index the node
     * no longer has). Returning rather than throwing is what keeps one stale edge from costing the
     * user the rest.
     */
    private static Edge connectDataEdge(NodeGraph graph, List<BaseNode> nodes, ClipboardDataEdge entry) {
        BaseNode sourceNode = nodeAt(nodes, entry.sourceNodeIndex());
        BaseNode targetNode = nodeAt(nodes, entry.targetNodeIndex());
        if (sourceNode == null || targetNode == null) {
            log.warn("Skipping data edge to unresolved node (source={}, target={}, node count={})",
                    entry.sourceNodeIndex(), entry.targetNodeIndex(), nodes.size());
            return null;
        }
        NodeVariable sourceVariable = at(sourceNode.getOutputs(), entry.sourceVariableIndex());
        NodeVariable targetVariable = at(targetNode.getInputs(), entry.targetVariableIndex());
        if (sourceVariable == null || targetVariable == null) {
            log.warn("Skipping data edge with out-of-range port index (sourceVar={}, targetVar={}) between nodes {} and {}",
                    entry.sourceVariableIndex(), entry.targetVariableIndex(), entry.sourceNodeIndex(), entry.targetNodeIndex());
            return null;
        }
        // An input is fed by at most one data edge, and NodeGraph enforces that by throwing. Wiring
        // one replaces whatever fed the input before, matching the interactive path.
        for (Edge existing : graph.getIncomingDataEdges(targetNode)) {
            if (existing.getTargetVariable() == targetVariable) {
                graph.removeEdge(existing);
            }
        }
        Edge edge = new Edge(sourceNode, sourceVariable, targetNode, targetVariable);
        graph.registerEdge(edge);
        return edge;
    }

    /** Flow-edge counterpart of {@link #connectDataEdge}. */
    private static FlowEdge connectFlowEdge(NodeGraph graph, List<BaseNode> nodes, ClipboardFlowEdge entry) {
        BaseNode sourceNode = nodeAt(nodes, entry.sourceNodeIndex());
        BaseNode targetNode = nodeAt(nodes, entry.targetNodeIndex());
        if (sourceNode == null || targetNode == null) {
            log.warn("Skipping flow edge to unresolved node (source={}, target={}, node count={})",
                    entry.sourceNodeIndex(), entry.targetNodeIndex(), nodes.size());
            return null;
        }
        FlowPort sourcePort = at(sourceNode.getFlowOutputs(), entry.sourcePortIndex());
        FlowPort targetPort = at(targetNode.getFlowInputs(), entry.targetPortIndex());
        if (sourcePort == null || targetPort == null) {
            log.warn("Skipping flow edge with out-of-range port index (sourcePort={}, targetPort={}) between nodes {} and {}",
                    entry.sourcePortIndex(), entry.targetPortIndex(), entry.sourceNodeIndex(), entry.targetNodeIndex());
            return null;
        }
        // A flow-in port may be fed by any number of edges, so there is nothing to replace - but the
        // identical pair twice is pointless, and NodeGraph would happily hold both. Reuse it instead.
        for (FlowEdge existing : graph.getOutgoingFlowEdges(sourceNode)) {
            if (existing.getSourcePort() == sourcePort
                    && existing.getTargetNode() == targetNode
                    && existing.getTargetPort() == targetPort) {
                return existing;
            }
        }
        FlowEdge flowEdge = new FlowEdge(sourceNode, sourcePort, targetNode, targetPort);
        graph.registerFlowEdge(flowEdge);
        return flowEdge;
    }

    /**
     * The node at {@code index}, or null if it doesn't resolve — either the index is out of range,
     * or the slot is a null placeholder left by a node that couldn't be built. Both mean "an edge
     * references a node that is no longer there", so both skip the edge.
     */
    private static BaseNode nodeAt(List<BaseNode> nodes, int index) {
        return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
    }

    /** The port at {@code index}, or null if out of range (an edge referencing a port the node no longer has). */
    private static <T> T at(List<T> ports, int index) {
        return index >= 0 && index < ports.size() ? ports.get(index) : null;
    }
}
