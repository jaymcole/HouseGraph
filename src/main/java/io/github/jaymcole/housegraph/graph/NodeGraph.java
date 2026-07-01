package io.github.jaymcole.housegraph.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns a set of {@link BaseNode}s and the {@link Edge}/{@link FlowEdge} connections
 * between them, and drives their execution.
 * <p>
 * This is an instance (not static) so that each canvas/document gets its own
 * isolated graph — multiple graphs can coexist, tests can create a throwaway graph
 * per case, and deleting a node actually releases it (no static map holding a
 * reference forever).
 * <p>
 * A node must be added via {@link #addNode} before it can take part in an edge, and
 * a {@link BaseNode#execute()}/{@link BaseNode#beginProcessing()} call only works
 * once its node has been added to a graph.
 */
public class NodeGraph {

    private final Set<BaseNode> nodes = new LinkedHashSet<>();
    private final Map<BaseNode, Set<Edge>> outgoingDataEdges = new HashMap<>();
    private final Map<BaseNode, Set<Edge>> incomingDataEdges = new HashMap<>();
    private final Map<BaseNode, Set<FlowEdge>> outgoingFlowEdges = new HashMap<>();
    private final Map<BaseNode, Set<FlowEdge>> incomingFlowEdges = new HashMap<>();

    /** Tracks which nodes have already been cascaded through during the current execute() pass. */
    private final Set<BaseNode> flowVisited = new HashSet<>();

    // --- Node lifecycle ---------------------------------------------------------

    public void addNode(BaseNode node) {
        Objects.requireNonNull(node, "node");
        if (node.getGraph() != null && node.getGraph() != this) {
            throw new IllegalStateException(node.getName() + " already belongs to another NodeGraph");
        }
        if (nodes.add(node)) {
            node.setGraph(this);
        }
    }

    public void removeNode(BaseNode node) {
        Objects.requireNonNull(node, "node");
        for (Edge edge : new ArrayList<>(getOutgoingDataEdges(node))) {
            removeEdge(edge);
        }
        for (Edge edge : new ArrayList<>(getIncomingDataEdges(node))) {
            removeEdge(edge);
        }
        for (FlowEdge edge : new ArrayList<>(getOutgoingFlowEdges(node))) {
            removeFlowEdge(edge);
        }
        for (FlowEdge edge : new ArrayList<>(getIncomingFlowEdges(node))) {
            removeFlowEdge(edge);
        }
        outgoingDataEdges.remove(node);
        incomingDataEdges.remove(node);
        outgoingFlowEdges.remove(node);
        incomingFlowEdges.remove(node);
        nodes.remove(node);
        node.setGraph(null);
    }

    public Set<BaseNode> getNodes() {
        return Collections.unmodifiableSet(nodes);
    }

    // --- Data edges ---------------------------------------------------------------

    public void registerEdge(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        requireRegistered(edge.getSourceNode());
        requireRegistered(edge.getTargetNode());
        if (edge.getSourceVariable().type != edge.getTargetVariable().type) {
            throw new IllegalArgumentException(
                    "Cannot connect a " + edge.getSourceVariable().type.getSimpleName() + " output to a "
                            + edge.getTargetVariable().type.getSimpleName() + " input");
        }
        addToSet(outgoingDataEdges, edge.getSourceNode(), edge);
        addToSet(incomingDataEdges, edge.getTargetNode(), edge);
    }

    public void removeEdge(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        removeFromSet(outgoingDataEdges, edge.getSourceNode(), edge);
        removeFromSet(incomingDataEdges, edge.getTargetNode(), edge);
    }

    public Set<Edge> getOutgoingDataEdges(BaseNode node) {
        return Collections.unmodifiableSet(outgoingDataEdges.getOrDefault(node, Collections.emptySet()));
    }

    public Set<Edge> getIncomingDataEdges(BaseNode node) {
        return Collections.unmodifiableSet(incomingDataEdges.getOrDefault(node, Collections.emptySet()));
    }

    // --- Flow edges -----------------------------------------------------------

    public void registerFlowEdge(FlowEdge edge) {
        Objects.requireNonNull(edge, "edge");
        requireRegistered(edge.getSourceNode());
        requireRegistered(edge.getTargetNode());
        addToSet(outgoingFlowEdges, edge.getSourceNode(), edge);
        addToSet(incomingFlowEdges, edge.getTargetNode(), edge);
    }

    public void removeFlowEdge(FlowEdge edge) {
        Objects.requireNonNull(edge, "edge");
        removeFromSet(outgoingFlowEdges, edge.getSourceNode(), edge);
        removeFromSet(incomingFlowEdges, edge.getTargetNode(), edge);
    }

    public Set<FlowEdge> getOutgoingFlowEdges(BaseNode node) {
        return Collections.unmodifiableSet(outgoingFlowEdges.getOrDefault(node, Collections.emptySet()));
    }

    public Set<FlowEdge> getIncomingFlowEdges(BaseNode node) {
        return Collections.unmodifiableSet(incomingFlowEdges.getOrDefault(node, Collections.emptySet()));
    }

    // --- Execution ------------------------------------------------------------

    /**
     * Pulls a fresh value through {@code node}'s incoming data edges (resolving
     * upstream nodes first) and runs its process(). Every call is a fresh pass: all
     * nodes' statuses are reset first, so this never serves a stale cached value.
     *
     * @throws IllegalStateException if the data edges form a cycle
     */
    public void resolve(BaseNode node) {
        requireRegistered(node);
        resetAllStatuses();
        resolveInternal(node);
    }

    /**
     * Resolves {@code node} (see {@link #resolve}) and then cascades along its
     * outgoing flow edges, resolving each downstream node in turn. A node reached
     * via two different flow paths in the same pass only runs once.
     *
     * @throws IllegalStateException if the data or flow edges form a cycle
     */
    public void execute(BaseNode node) {
        requireRegistered(node);
        resetAllStatuses();
        flowVisited.clear();
        executeInternal(node);
    }

    private void executeInternal(BaseNode node) {
        if (!flowVisited.add(node)) {
            return;
        }
        resolveInternal(node);
        for (FlowEdge flowEdge : getOutgoingFlowEdges(node)) {
            executeInternal(flowEdge.getTargetNode());
        }
    }

    private void resolveInternal(BaseNode node) {
        NodeProcessingStatus status = node.getStatus();
        if (status == NodeProcessingStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cycle detected in data graph at node: " + node.getName());
        }
        if (status.isComplete()) {
            return;
        }

        node.setStatus(NodeProcessingStatus.IN_PROGRESS);
        for (Edge edge : getIncomingDataEdges(node)) {
            resolveInternal(edge.getSourceNode());
            propagateValue(edge);
        }

        try {
            node.process();
            node.setStatus(NodeProcessingStatus.SUCCESS);
            node.setLastError(null);
        } catch (Exception e) {
            node.setStatus(NodeProcessingStatus.FAILED);
            node.setLastError(e);
            System.err.println("Node \"" + node.getName() + "\" failed to process: " + e);
        }
        node.onExecuted();
    }

    @SuppressWarnings("unchecked")
    private void propagateValue(Edge edge) {
        edge.getTargetVariable().setValue(edge.getSourceVariable().getValue());
    }

    private void resetAllStatuses() {
        for (BaseNode node : nodes) {
            node.setStatus(NodeProcessingStatus.NOT_STARTED);
        }
    }

    // --- Helpers ----------------------------------------------------------------

    private void requireRegistered(BaseNode node) {
        if (!nodes.contains(node)) {
            throw new IllegalStateException(node.getName() + " must be added to the graph before it can be wired or run");
        }
    }

    private static <T> void addToSet(Map<BaseNode, Set<T>> map, BaseNode node, T value) {
        map.computeIfAbsent(node, key -> new HashSet<>()).add(value);
    }

    private static <T> void removeFromSet(Map<BaseNode, Set<T>> map, BaseNode node, T value) {
        Set<T> set = map.get(node);
        if (set != null) {
            set.remove(value);
        }
    }
}
