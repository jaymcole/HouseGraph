package io.github.jaymcole.housegraph.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class Graph {

    private static HashMap<BaseNode, HashSet<Edge>> nodeToLeftEdgesMap = new HashMap<>();
    private static HashMap<BaseNode, HashSet<Edge>> nodeToRightEdgesMap = new HashMap<>();

    public static void registerEdge(Edge edge) {
        addNodeEdge(edge.getLeftNode(), edge, nodeToRightEdgesMap);
        addNodeEdge(edge.getRightNode(), edge, nodeToLeftEdgesMap);
    }

    public static void removeEdge(Edge edge) {
        removeNodeEdge(edge.getLeftNode(), edge, nodeToRightEdgesMap);
        removeNodeEdge(edge.getRightNode(), edge, nodeToLeftEdgesMap);
    }

    public static HashSet<Edge> getLeftEdges(BaseNode node) {
        return nodeToLeftEdgesMap.getOrDefault(node, new HashSet<>());
    }

    public static HashSet<Edge> getRightEdges(BaseNode node) {
        return nodeToRightEdgesMap.getOrDefault(node, new HashSet<>());
    }

    private static void addNodeEdge(BaseNode node, Edge edge, HashMap<BaseNode, HashSet<Edge>> map) {
        if (!map.containsKey(node)) {
            map.put(node, new HashSet<>(List.of(edge)));
        } else {
            map.get(node).add(edge);
        }
    }

    private static void removeNodeEdge(BaseNode node, Edge edge, HashMap<BaseNode, HashSet<Edge>> map) {
        if (map.containsKey(node)) {
            map.get(node).remove(edge);
        }
    }

}
