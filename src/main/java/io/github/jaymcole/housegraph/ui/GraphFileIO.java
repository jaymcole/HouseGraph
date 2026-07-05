package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import javafx.geometry.Point2D;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves/loads a {@link GraphCanvas}'s entire contents to/from a JSON file, reusing the
 * same index-based node/edge shape ({@link GraphCanvas.GraphSnapshot}) built for
 * copy/paste. The JSON conversion ({@link #toJson}/{@link #fromJson}) is deliberately
 * kept free of any JavaFX/GraphCanvas dependency so it can be unit-tested headlessly;
 * {@link #save}/{@link #load} are the thin wrappers that touch an actual canvas.
 */
public final class GraphFileIO {

    private GraphFileIO() {
    }

    public static void save(GraphCanvas canvas, File file) throws IOException {
        JSONObject root = toJson(canvas.snapshotAll());
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(root.toString(2));
        }
    }

    public static void load(GraphCanvas canvas, File file) throws IOException {
        JSONObject root;
        try (FileReader reader = new FileReader(file)) {
            root = new JSONObject(new JSONTokener(reader));
        }
        canvas.loadSnapshot(fromJson(root));
    }

    static JSONObject toJson(GraphCanvas.GraphSnapshot snapshot) {
        JSONArray nodesJson = new JSONArray();
        for (GraphCanvas.ClipboardNode entry : snapshot.nodes()) {
            BaseNode node = entry.node();
            JSONObject nodeJson = new JSONObject();
            nodeJson.put("type", node.getClass().getName());
            nodeJson.put("x", entry.x());
            nodeJson.put("y", entry.y());
            nodeJson.put("inputs", valuesToJson(node.getInputs()));
            nodeJson.put("outputs", valuesToJson(node.getOutputs()));
            Map<String, String> state = node.saveState();
            if (!state.isEmpty()) {
                nodeJson.put("state", new JSONObject(state));
            }
            nodesJson.put(nodeJson);
        }

        JSONArray dataEdgesJson = new JSONArray();
        for (GraphCanvas.ClipboardDataEdge edge : snapshot.dataEdges()) {
            JSONObject edgeJson = new JSONObject();
            edgeJson.put("sourceNode", edge.sourceNodeIndex());
            edgeJson.put("sourceVariable", edge.sourceVariableIndex());
            edgeJson.put("targetNode", edge.targetNodeIndex());
            edgeJson.put("targetVariable", edge.targetVariableIndex());
            edgeJson.put("waypoints", waypointsToJson(edge.waypoints()));
            dataEdgesJson.put(edgeJson);
        }

        JSONArray flowEdgesJson = new JSONArray();
        for (GraphCanvas.ClipboardFlowEdge edge : snapshot.flowEdges()) {
            JSONObject edgeJson = new JSONObject();
            edgeJson.put("sourceNode", edge.sourceNodeIndex());
            edgeJson.put("sourcePort", edge.sourcePortIndex());
            edgeJson.put("targetNode", edge.targetNodeIndex());
            edgeJson.put("targetPort", edge.targetPortIndex());
            edgeJson.put("waypoints", waypointsToJson(edge.waypoints()));
            flowEdgesJson.put(edgeJson);
        }

        JSONObject root = new JSONObject();
        root.put("nodes", nodesJson);
        root.put("dataEdges", dataEdgesJson);
        root.put("flowEdges", flowEdgesJson);
        return root;
    }

    static GraphCanvas.GraphSnapshot fromJson(JSONObject root) {
        List<GraphCanvas.ClipboardNode> nodes = new ArrayList<>();
        JSONArray nodesJson = root.getJSONArray("nodes");
        for (int i = 0; i < nodesJson.length(); i++) {
            JSONObject nodeJson = nodesJson.getJSONObject(i);
            String typeName = nodeJson.getString("type");
            Class<? extends BaseNode> nodeClass = NodeRegistry.resolveClass(typeName);
            if (nodeClass == null) {
                System.err.println("Skipping unknown node type in save file: " + typeName);
                continue;
            }
            BaseNode node = NodeRegistry.instantiate(nodeClass);
            if (node == null) {
                continue;
            }
            applyValues(node.getInputs(), nodeJson.getJSONArray("inputs"));
            applyValues(node.getOutputs(), nodeJson.getJSONArray("outputs"));
            if (nodeJson.has("state")) {
                node.loadState(readState(nodeJson.getJSONObject("state")));
            }
            nodes.add(new GraphCanvas.ClipboardNode(node, nodeJson.getDouble("x"), nodeJson.getDouble("y")));
        }

        List<GraphCanvas.ClipboardDataEdge> dataEdges = new ArrayList<>();
        JSONArray dataEdgesJson = root.getJSONArray("dataEdges");
        for (int i = 0; i < dataEdgesJson.length(); i++) {
            JSONObject edgeJson = dataEdgesJson.getJSONObject(i);
            dataEdges.add(new GraphCanvas.ClipboardDataEdge(
                    edgeJson.getInt("sourceNode"), edgeJson.getInt("sourceVariable"),
                    edgeJson.getInt("targetNode"), edgeJson.getInt("targetVariable"),
                    waypointsFromJson(edgeJson)));
        }

        List<GraphCanvas.ClipboardFlowEdge> flowEdges = new ArrayList<>();
        JSONArray flowEdgesJson = root.getJSONArray("flowEdges");
        for (int i = 0; i < flowEdgesJson.length(); i++) {
            JSONObject edgeJson = flowEdgesJson.getJSONObject(i);
            // sourcePort/targetPort default to 0 so a save file written before flow
            // ports had identity (every node had a single port) still loads correctly.
            flowEdges.add(new GraphCanvas.ClipboardFlowEdge(
                    edgeJson.getInt("sourceNode"), edgeJson.optInt("sourcePort", 0),
                    edgeJson.getInt("targetNode"), edgeJson.optInt("targetPort", 0),
                    waypointsFromJson(edgeJson)));
        }

        return new GraphCanvas.GraphSnapshot(nodes, dataEdges, flowEdges);
    }

    private static JSONArray waypointsToJson(List<Point2D> waypoints) {
        JSONArray array = new JSONArray();
        for (Point2D point : waypoints) {
            JSONObject json = new JSONObject();
            json.put("x", point.getX());
            json.put("y", point.getY());
            array.put(json);
        }
        return array;
    }

    /** Reads an edge's "waypoints" array, or an empty list if the key is absent (a save from before routing existed). */
    private static List<Point2D> waypointsFromJson(JSONObject edgeJson) {
        List<Point2D> points = new ArrayList<>();
        JSONArray array = edgeJson.optJSONArray("waypoints");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                points.add(new Point2D(json.getDouble("x"), json.getDouble("y")));
            }
        }
        return points;
    }

    @SuppressWarnings("unchecked")
    private static JSONArray valuesToJson(List<NodeVariable> variables) {
        JSONArray array = new JSONArray();
        for (NodeVariable variable : variables) {
            // A secret's or transient's value is never written to disk - only null is
            // stored in its slot, so position-based load still lines up. The node
            // re-resolves/repopulates it at run time.
            Object value = variable.isSecret() || variable.isTransient() ? null : variable.getValue();
            array.put(value == null ? JSONObject.NULL : value);
        }
        return array;
    }

    private static Map<String, String> readState(JSONObject stateJson) {
        Map<String, String> state = new HashMap<>();
        for (String stateKey : stateJson.keySet()) {
            state.put(stateKey, stateJson.getString(stateKey));
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static void applyValues(List<NodeVariable> variables, JSONArray values) {
        for (int i = 0; i < Math.min(variables.size(), values.length()); i++) {
            Object raw = values.isNull(i) ? null : values.get(i);
            variables.get(i).setValue(coerce(raw, variables.get(i).type));
        }
    }

    /** Coerces a raw JSON value (org.json only knows number/string/boolean/JSONObject.NULL) to a NodeVariable's declared type. */
    private static Object coerce(Object raw, Class<?> type) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            if (type == Float.class) {
                return number.floatValue();
            }
            if (type == Double.class) {
                return number.doubleValue();
            }
            if (type == Integer.class) {
                return number.intValue();
            }
        }
        return raw;
    }
}
