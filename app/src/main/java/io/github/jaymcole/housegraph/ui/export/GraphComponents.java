package io.github.jaymcole.housegraph.ui.export;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.NodeGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Splits a graph into its connected components — the "distinct graphs" a single save file can hold.
 * <p>
 * One canvas commonly carries several unrelated automations side by side, and each is its own thing:
 * {@link GraphImageExport} renders one image per component so an unrelated automation never appears
 * in another one's picture.
 *
 * <h2>What counts as connected</h2>
 * Data edges and flow edges are both treated as plain undirected links. The two are separate
 * concepts everywhere else in this codebase (see {@code docs/decisions/0001-separate-data-and-flow-edges.md}),
 * but not here: "which nodes belong to the same picture" is a question about reachability on the
 * canvas, not about how values or execution travel, and a user reading a diagram considers two nodes
 * joined by either kind of wire to be part of the same automation. Direction is ignored for the same
 * reason — a node's upstream constants belong in its picture as much as its downstream consumers.
 * <p>
 * A node with no edges at all is its own single-node component, and gets its own image. That is
 * deliberate rather than a degenerate case: an unwired node on the canvas is a distinct thing the
 * user put there.
 *
 * <h2>Ordering</h2>
 * Components come back in the order their first node appears in {@link NodeGraph#getNodes()}, and
 * each component's nodes in that same order. {@code getNodes()} preserves insertion order, so the
 * result is deterministic for a given graph rather than varying run to run with hash order — which
 * matters because the caller turns component <em>index</em> into a filename.
 *
 * <p>This class is deliberately free of any JavaFX dependency so it can be unit-tested headlessly;
 * everything that touches pixels lives in {@link GraphImageExport}.
 */
public final class GraphComponents {

    private GraphComponents() {
    }

    /**
     * Groups every node in {@code graph} into connected components.
     *
     * @return one set per component, never empty sets; an empty list for an empty graph
     */
    public static List<Set<BaseNode>> connectedComponents(NodeGraph graph) {
        List<Set<BaseNode>> components = new ArrayList<>();
        Set<BaseNode> unvisited = new LinkedHashSet<>(graph.getNodes());

        while (!unvisited.isEmpty()) {
            BaseNode seed = unvisited.iterator().next();
            components.add(reachableFrom(seed, graph, unvisited));
        }
        return components;
    }

    /**
     * Breadth-first walk out from {@code seed} across edges of both kinds in both directions,
     * removing each node it reaches from {@code unvisited} so the outer loop's next seed starts a
     * genuinely new component.
     */
    private static Set<BaseNode> reachableFrom(BaseNode seed, NodeGraph graph, Set<BaseNode> unvisited) {
        Set<BaseNode> component = new LinkedHashSet<>();
        Deque<BaseNode> queue = new ArrayDeque<>();

        queue.add(seed);
        unvisited.remove(seed);
        component.add(seed);

        while (!queue.isEmpty()) {
            BaseNode node = queue.removeFirst();
            for (BaseNode neighbour : neighboursOf(node, graph)) {
                // remove() doubles as the visited test: it only succeeds the first time, so a cycle
                // — which a graph with a loop node certainly has — can't re-enqueue a node forever.
                if (unvisited.remove(neighbour)) {
                    component.add(neighbour);
                    queue.addLast(neighbour);
                }
            }
        }
        return component;
    }

    /** Every node joined to {@code node} by a data or flow edge, in either direction. */
    private static List<BaseNode> neighboursOf(BaseNode node, NodeGraph graph) {
        List<BaseNode> neighbours = new ArrayList<>();
        for (Edge edge : graph.getOutgoingDataEdges(node)) {
            neighbours.add(edge.getTargetNode());
        }
        for (Edge edge : graph.getIncomingDataEdges(node)) {
            neighbours.add(edge.getSourceNode());
        }
        for (FlowEdge edge : graph.getOutgoingFlowEdges(node)) {
            neighbours.add(edge.getTargetNode());
        }
        for (FlowEdge edge : graph.getIncomingFlowEdges(node)) {
            neighbours.add(edge.getSourceNode());
        }
        return neighbours;
    }
}
