package io.github.jaymcole.housegraph.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Static registry of {@link FlowEdge}s, mirroring {@link Graph} but for control-flow
 * (execution order) rather than data. Only the outgoing direction is tracked, since
 * {@link BaseNode#execute()} only ever needs to walk forward.
 */
public class FlowGraph {

    private static final HashMap<BaseNode, HashSet<FlowEdge>> nodeToOutgoingFlowEdges = new HashMap<>();

    public static void registerFlowEdge(FlowEdge edge) {
        if (!nodeToOutgoingFlowEdges.containsKey(edge.getSourceNode())) {
            nodeToOutgoingFlowEdges.put(edge.getSourceNode(), new HashSet<>(List.of(edge)));
        } else {
            nodeToOutgoingFlowEdges.get(edge.getSourceNode()).add(edge);
        }
    }

    public static void removeFlowEdge(FlowEdge edge) {
        HashSet<FlowEdge> edges = nodeToOutgoingFlowEdges.get(edge.getSourceNode());
        if (edges != null) {
            edges.remove(edge);
        }
    }

    public static HashSet<FlowEdge> getOutgoingFlowEdges(BaseNode node) {
        return nodeToOutgoingFlowEdges.getOrDefault(node, new HashSet<>());
    }

}
