package io.github.jaymcole.housegraph.ui.io;

import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardDataEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardNode;
import io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ExecutionPolicy;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
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
 * same index-based node/edge shape ({@link GraphSnapshot}) built for
 * copy/paste. The JSON conversion ({@link #toJson}/{@link #fromJson}) is deliberately
 * kept free of any JavaFX/GraphCanvas dependency so it can be unit-tested headlessly;
 * {@link #save}/{@link #load} are the thin wrappers that touch an actual canvas.
 * <p>
 * Per node the file stores its {@code type}, canvas {@code x}/{@code y}, its
 * {@code executionPolicy} (see {@link ExecutionPolicy}), its {@code maxConcurrency} and
 * {@code timeoutMillis} (both written only when non-zero), its persistable input/output values
 * (computed and secret values are omitted — see {@link NodeVariable#isPersistentValue}), a
 * {@code requiredInputs} positional boolean array (written only when some input is
 * {@link NodeVariable#isRequired() required}), and any node-specific {@code state}. Reads are
 * forgiving of older files: a missing {@code executionPolicy} loads as the default {@code QUEUE},
 * missing {@code maxConcurrency}/{@code timeoutMillis} as 0 (unlimited / no timeout), a missing
 * {@code requiredInputs} leaves each input's author-declared default, and an unknown node type is
 * skipped rather than failing the whole load.
 */
public final class GraphFileIO {

    private static final Logger log = Log.get(GraphFileIO.class);

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

    static JSONObject toJson(GraphSnapshot snapshot) {
        JSONArray nodesJson = new JSONArray();
        for (ClipboardNode entry : snapshot.nodes()) {
            BaseNode node = entry.node();
            JSONObject nodeJson = new JSONObject();
            nodeJson.put("type", node.getClass().getName());
            nodeJson.put("x", entry.x());
            nodeJson.put("y", entry.y());
            nodeJson.put("executionPolicy", node.getExecutionPolicy().name());
            if (node.getMaxConcurrency() != 0) {
                nodeJson.put("maxConcurrency", node.getMaxConcurrency());
            }
            if (node.getTimeoutMillis() != 0) {
                nodeJson.put("timeoutMillis", node.getTimeoutMillis());
            }
            nodeJson.put("inputs", valuesToJson(node.getInputs()));
            nodeJson.put("outputs", valuesToJson(node.getOutputs()));
            JSONArray requiredInputs = requiredInputsToJson(node.getInputs());
            if (requiredInputs != null) {
                nodeJson.put("requiredInputs", requiredInputs);
            }
            Map<String, String> state = node.saveState();
            if (!state.isEmpty()) {
                nodeJson.put("state", new JSONObject(state));
            }
            nodesJson.put(nodeJson);
        }

        JSONArray dataEdgesJson = new JSONArray();
        for (ClipboardDataEdge edge : snapshot.dataEdges()) {
            JSONObject edgeJson = new JSONObject();
            edgeJson.put("sourceNode", edge.sourceNodeIndex());
            edgeJson.put("sourceVariable", edge.sourceVariableIndex());
            edgeJson.put("targetNode", edge.targetNodeIndex());
            edgeJson.put("targetVariable", edge.targetVariableIndex());
            edgeJson.put("waypoints", waypointsToJson(edge.waypoints()));
            dataEdgesJson.put(edgeJson);
        }

        JSONArray flowEdgesJson = new JSONArray();
        for (ClipboardFlowEdge edge : snapshot.flowEdges()) {
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

    static GraphSnapshot fromJson(JSONObject root) {
        List<ClipboardNode> nodes = new ArrayList<>();
        JSONArray nodesJson = root.getJSONArray("nodes");
        for (int i = 0; i < nodesJson.length(); i++) {
            JSONObject nodeJson = nodesJson.getJSONObject(i);
            String typeName = nodeJson.getString("type");
            Class<? extends BaseNode> nodeClass = NodeRegistry.resolveClass(typeName);
            if (nodeClass == null) {
                log.warn("Skipping unknown node type in save file: {}", typeName);
                continue;
            }
            BaseNode node = NodeRegistry.instantiate(nodeClass);
            if (node == null) {
                continue;
            }
            // Restore node config BEFORE touching ports. A dynamic-port node (the object
            // decomposer, a Discord slash command) builds its inputs/outputs from this
            // state, and the getInputs()/getOutputs() calls below are what first trigger
            // that configuration - so the state has to be in place first, or the ports get
            // built empty and never rebuild.
            if (nodeJson.has("state")) {
                node.loadState(readState(nodeJson.getJSONObject("state")));
            }
            // Absent in saves written before execution policies existed; default to QUEUE.
            node.setExecutionPolicy(parsePolicy(nodeJson.optString("executionPolicy", null)));
            node.setMaxConcurrency(nodeJson.optInt("maxConcurrency", 0));
            node.setTimeoutMillis(nodeJson.optLong("timeoutMillis", 0));
            applyValues(node.getInputs(), nodeJson.getJSONArray("inputs"));
            applyValues(node.getOutputs(), nodeJson.getJSONArray("outputs"));
            // Absent in saves written before inputs could be required, and in saves where no input
            // was required — either way the node keeps its author-declared defaults untouched.
            if (nodeJson.has("requiredInputs")) {
                applyRequired(node.getInputs(), nodeJson.getJSONArray("requiredInputs"));
            }
            nodes.add(new ClipboardNode(node, nodeJson.getDouble("x"), nodeJson.getDouble("y")));
        }

        List<ClipboardDataEdge> dataEdges = new ArrayList<>();
        JSONArray dataEdgesJson = root.getJSONArray("dataEdges");
        for (int i = 0; i < dataEdgesJson.length(); i++) {
            JSONObject edgeJson = dataEdgesJson.getJSONObject(i);
            dataEdges.add(new ClipboardDataEdge(
                    edgeJson.getInt("sourceNode"), edgeJson.getInt("sourceVariable"),
                    edgeJson.getInt("targetNode"), edgeJson.getInt("targetVariable"),
                    waypointsFromJson(edgeJson)));
        }

        List<ClipboardFlowEdge> flowEdges = new ArrayList<>();
        JSONArray flowEdgesJson = root.getJSONArray("flowEdges");
        for (int i = 0; i < flowEdgesJson.length(); i++) {
            JSONObject edgeJson = flowEdgesJson.getJSONObject(i);
            // sourcePort/targetPort default to 0 so a save file written before flow
            // ports had identity (every node had a single port) still loads correctly.
            flowEdges.add(new ClipboardFlowEdge(
                    edgeJson.getInt("sourceNode"), edgeJson.optInt("sourcePort", 0),
                    edgeJson.getInt("targetNode"), edgeJson.optInt("targetPort", 0),
                    waypointsFromJson(edgeJson)));
        }

        return new GraphSnapshot(nodes, dataEdges, flowEdges);
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
            // Only manually-authored values are written; computed values are left as null
            // and recomputed on load (see NodeVariable.isPersistentValue). Storing null in
            // the skipped slots keeps position-based load lined up.
            Object value = variable.isPersistentValue() ? variable.getValue() : null;
            array.put(value == null ? JSONObject.NULL : value);
        }
        return array;
    }

    /**
     * A positional boolean per input recording whether it's {@link NodeVariable#isRequired()
     * required}, or {@code null} when none are — so the common "no required inputs" node writes
     * nothing. Aligned with the {@code inputs} value array (same order), read back by
     * {@link #applyRequired}. Note: a node whose only author-required input was un-required by the
     * user therefore writes nothing and reloads with that input required again — the one case this
     * compact "write only when something is required" scheme can't round-trip.
     */
    private static JSONArray requiredInputsToJson(List<NodeVariable> inputs) {
        JSONArray array = new JSONArray();
        boolean anyRequired = false;
        for (NodeVariable input : inputs) {
            boolean required = input.isRequired();
            array.put(required);
            anyRequired |= required;
        }
        return anyRequired ? array : null;
    }

    /** Restores each input's required flag from a saved positional boolean array (extra inputs, if any, keep their author default). */
    private static void applyRequired(List<NodeVariable> inputs, JSONArray flags) {
        for (int i = 0; i < Math.min(inputs.size(), flags.length()); i++) {
            inputs.get(i).setRequired(flags.getBoolean(i));
        }
    }

    /** Parses a saved {@link ExecutionPolicy} name, tolerating null/unknown values by falling back to the default. */
    private static ExecutionPolicy parsePolicy(String name) {
        if (name == null || name.isBlank()) {
            return ExecutionPolicy.QUEUE;
        }
        try {
            return ExecutionPolicy.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown execution policy in save file, defaulting to QUEUE: {}", name);
            return ExecutionPolicy.QUEUE;
        }
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
