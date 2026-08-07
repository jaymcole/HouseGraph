package io.github.jaymcole.housegraph.ui.io;

import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardDataEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardNode;
import io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ExecutionPolicy;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.nodes.MissingNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves/loads a {@link GraphCanvas}'s entire contents to/from a JSON file, reusing the
 * same index-based node/edge shape ({@link GraphSnapshot}) built for
 * copy/paste. The JSON conversion ({@link #toJson}/{@link #fromJson}) is deliberately
 * kept free of any JavaFX/GraphCanvas dependency so it can be unit-tested headlessly;
 * {@link #save}/{@link #load} are the thin wrappers that touch an actual canvas.
 * <p>
 * The root carries a {@code version} (see {@link #CURRENT_VERSION}) and a {@link #migrate} seam for
 * future structural format changes. Per node the file stores its {@code type} — a stable type id
 * ({@link NodeRegistry#persistentTypeId}, decoupled from the class name so moving/renaming a node
 * class doesn't strand old saves), canvas {@code x}/{@code y}, its {@code executionPolicy} (see
 * {@link ExecutionPolicy}), its {@code maxConcurrency} and {@code timeoutMillis} (both written only
 * when non-zero), its persistable input/output values, a {@code requiredInputs} entry, and any
 * node-specific {@code state}.
 * <p>
 * <b>Ports are persisted by name, not position.</b> A node's {@code inputs}/{@code outputs} are
 * written as {@code {name, value}} objects (computed and secret values are omitted — see
 * {@link NodeVariable#isPersistentValue}), and a data/flow edge references the variable or port it
 * touches by <em>name</em> whenever that name is non-blank and unique on its node (falling back to a
 * positional index for the common unnamed single flow port). On load, values are matched to inputs by
 * name and edges resolved by name. This is what lets a node author reorder or insert a port in a
 * {@code configure*} hook without silently mis-binding every previously-saved graph to the wrong
 * anchors — the failure mode of the old purely positional format. {@code requiredInputs} is likewise
 * an array of the <em>names</em> of the required inputs.
 * <p>
 * <b>Reads stay forgiving, including of the old positional format.</b> Files written before this
 * change store bare scalar {@code inputs}/{@code outputs} arrays, integer edge references, and a
 * positional {@code requiredInputs} boolean array; all three are still read positionally (detected by
 * JSON shape). Beyond that: a missing {@code executionPolicy} loads as the default {@code QUEUE},
 * missing {@code maxConcurrency}/{@code timeoutMillis} as 0 (unlimited / no timeout), a missing
 * {@code requiredInputs} leaves each input's author-declared default, an unknown node type loads as a
 * null-node placeholder (rather than failing the whole load) that holds its index slot so later nodes
 * — and the edges that reference them — stay correctly aligned, and an edge whose named endpoint no
 * longer resolves on its node is dropped rather than mis-wired.
 */
public final class GraphFileIO {

    private static final Logger log = Log.get(GraphFileIO.class);

    /**
     * The save-format version this build writes (stamped at the root as {@code "version"}). A file
     * with no {@code version} key predates versioning and is read as {@link #LEGACY_VERSION}. Bump
     * this when a change can't be handled by the shape-sniffing forgiving reads below, and add the
     * corresponding step to {@link #migrate}.
     */
    static final int CURRENT_VERSION = 2;

    /** The version assumed for a save file that has no {@code version} key (written before versioning). */
    static final int LEGACY_VERSION = 0;

    private GraphFileIO() {
    }

    public static void save(GraphCanvas canvas, File file) throws IOException {
        JSONObject root = toJson(canvas.snapshotAll(), canvas.getNodeRegistry());
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(root.toString(2));
        }
    }

    public static void load(GraphCanvas canvas, File file) throws IOException {
        JSONObject root;
        try (FileReader reader = new FileReader(file)) {
            root = new JSONObject(new JSONTokener(reader));
        }
        canvas.loadSnapshot(fromJson(root, canvas.getNodeRegistry()));
    }

    // The registry is a parameter rather than a static lookup so these two stay headless AND
    // injectable: a test can now hand in a registry scanning a fixture package instead of asserting
    // against whatever node types happen to ship in the app.
    static JSONObject toJson(GraphSnapshot snapshot, NodeRegistry registry) {
        List<ClipboardNode> snapshotNodes = snapshot.nodes();
        JSONArray nodesJson = new JSONArray();
        // Keyed by library id, insertion-ordered so the written table is stable between saves.
        Map<String, JSONObject> pluginRows = new LinkedHashMap<>();
        for (ClipboardNode entry : snapshotNodes) {
            BaseNode node = entry.node();

            // A node type this build couldn't resolve is written back byte-for-byte, apart from its
            // position. Re-deriving it from the placeholder's rebuilt ports would silently drop the
            // state map, concurrency/timeout settings, required-input flags, and any key a future
            // format adds that this build doesn't know about. See MissingNode.
            if (node instanceof MissingNode missing) {
                JSONObject preserved = new JSONObject(missing.rawJson().toString());
                preserved.put("x", entry.x());
                preserved.put("y", entry.y());
                nodesJson.put(preserved);
                if (missing.missingPluginId() != null) {
                    pluginRows.putIfAbsent(missing.missingPluginId(),
                            missing.rawPluginRow() != null
                                    ? new JSONObject(missing.rawPluginRow().toString())
                                    : new JSONObject().put("id", missing.missingPluginId()));
                }
                continue;
            }

            JSONObject nodeJson = new JSONObject();
            nodeJson.put("type", NodeRegistry.persistentTypeId(node.getClass()));
            String pluginId = registry.pluginIdOf(node.getClass());
            // Built-ins carry no "plugin" key at all, so a graph using only core nodes produces a v2
            // file that differs from its v1 form by exactly the version number.
            if (!NodeRegistry.CORE_PLUGIN_ID.equals(pluginId)) {
                nodeJson.put("plugin", pluginId);
                pluginRows.computeIfAbsent(pluginId, id -> new JSONObject().put("id", id));
            }
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
            BaseNode sourceNode = nodeAt(snapshotNodes, edge.sourceNodeIndex());
            BaseNode targetNode = nodeAt(snapshotNodes, edge.targetNodeIndex());
            JSONObject edgeJson = new JSONObject();
            edgeJson.put("sourceNode", edge.sourceNodeIndex());
            edgeJson.put("sourceVariable", variableRef(sourceNode, edge.sourceVariableIndex(), true));
            edgeJson.put("targetNode", edge.targetNodeIndex());
            edgeJson.put("targetVariable", variableRef(targetNode, edge.targetVariableIndex(), false));
            edgeJson.put("waypoints", waypointsToJson(edge.waypoints()));
            dataEdgesJson.put(edgeJson);
        }

        JSONArray flowEdgesJson = new JSONArray();
        for (ClipboardFlowEdge edge : snapshot.flowEdges()) {
            BaseNode sourceNode = nodeAt(snapshotNodes, edge.sourceNodeIndex());
            BaseNode targetNode = nodeAt(snapshotNodes, edge.targetNodeIndex());
            JSONObject edgeJson = new JSONObject();
            edgeJson.put("sourceNode", edge.sourceNodeIndex());
            edgeJson.put("sourcePort", flowPortRef(sourceNode, edge.sourcePortIndex(), true));
            edgeJson.put("targetNode", edge.targetNodeIndex());
            edgeJson.put("targetPort", flowPortRef(targetNode, edge.targetPortIndex(), false));
            edgeJson.put("waypoints", waypointsToJson(edge.waypoints()));
            flowEdgesJson.put(edgeJson);
        }

        JSONObject root = new JSONObject();
        root.put("version", CURRENT_VERSION);
        // Written only when non-empty, matching the discipline used for maxConcurrency/timeoutMillis/
        // requiredInputs. This is the table the load-time dependency check reads in one pass, before
        // a single node is built or a single class is loaded.
        if (!pluginRows.isEmpty()) {
            root.put("plugins", new JSONArray(pluginRows.values()));
        }
        root.put("nodes", nodesJson);
        root.put("dataEdges", dataEdgesJson);
        root.put("flowEdges", flowEdgesJson);
        return root;
    }

    static GraphSnapshot fromJson(JSONObject root, NodeRegistry registry) {
        int version = root.optInt("version", LEGACY_VERSION);
        root = migrate(root, version);

        // A v1 file has no plugins table; optJSONArray gives null and the map stays empty, which is
        // exactly the legacy behaviour (every node resolves with a null owning library).
        Map<String, JSONObject> pluginRows = readPluginRows(root.optJSONArray("plugins"));

        List<ClipboardNode> nodes = new ArrayList<>();
        JSONArray nodesJson = root.getJSONArray("nodes");
        for (int i = 0; i < nodesJson.length(); i++) {
            JSONObject nodeJson = nodesJson.getJSONObject(i);
            String typeName = nodeJson.getString("type");
            String pluginId = nodeJson.optString("plugin", null);
            double x = nodeJson.getDouble("x");
            double y = nodeJson.getDouble("y");
            Class<? extends BaseNode> nodeClass = registry.resolveClass(typeName, pluginId);
            if (nodeClass == null) {
                log.warn("Node type \"{}\"{} is not installed; keeping it as a placeholder so it survives a save",
                        typeName, pluginId == null ? "" : " (from \"" + pluginId + "\")");
                // NOT a null slot. A null was dropped by place() and never written back, so opening a
                // graph without the providing library and re-saving destroyed the node, its values,
                // its state, and every edge touching it. MissingNode preserves all of that verbatim.
                nodes.add(new ClipboardNode(MissingNode.from(nodeJson, pluginRows.get(pluginId)), x, y));
                continue;
            }
            BaseNode node = NodeRegistry.instantiate(nodeClass);
            if (node == null) {
                // A resolvable type that won't instantiate is an internal error, not absent user
                // data — there is nothing to preserve, so the index-holding null slot still applies.
                nodes.add(new ClipboardNode(null, x, y));
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
            nodes.add(new ClipboardNode(node, x, y));
        }

        JSONArray dataEdgesJson = root.getJSONArray("dataEdges");
        JSONArray flowEdgesJson = root.getJSONArray("flowEdges");

        // Back-fill placeholder ports from the edges before resolving any of them. A placeholder
        // rebuilds its data ports from the saved value arrays, but flow ports are never persisted on
        // a node — they exist only as edge endpoints — so without this pass every flow edge touching
        // a missing node would be dropped, and the graph would lose its wiring even though the node
        // itself survived.
        backFillPlaceholderPorts(nodes, dataEdgesJson, flowEdgesJson);

        List<ClipboardDataEdge> dataEdges = new ArrayList<>();
        for (int i = 0; i < dataEdgesJson.length(); i++) {
            JSONObject edgeJson = dataEdgesJson.getJSONObject(i);
            int sourceNode = edgeJson.getInt("sourceNode");
            int targetNode = edgeJson.getInt("targetNode");
            int sourceVariable = resolveVariableIndex(nodeAt(nodes, sourceNode), edgeJson.get("sourceVariable"), true);
            int targetVariable = resolveVariableIndex(nodeAt(nodes, targetNode), edgeJson.get("targetVariable"), false);
            if (sourceVariable < 0 || targetVariable < 0) {
                log.warn("Dropping a data edge whose endpoint no longer resolves on its node (source {}, target {})",
                        sourceNode, targetNode);
                continue;
            }
            dataEdges.add(new ClipboardDataEdge(sourceNode, sourceVariable, targetNode, targetVariable,
                    waypointsFromJson(edgeJson)));
        }

        List<ClipboardFlowEdge> flowEdges = new ArrayList<>();
        for (int i = 0; i < flowEdgesJson.length(); i++) {
            JSONObject edgeJson = flowEdgesJson.getJSONObject(i);
            int sourceNode = edgeJson.getInt("sourceNode");
            int targetNode = edgeJson.getInt("targetNode");
            // sourcePort/targetPort default to index 0 so a save written before flow ports had
            // identity (every node had a single port) still loads correctly.
            int sourcePort = resolveFlowPortIndex(nodeAt(nodes, sourceNode), edgeJson.opt("sourcePort"), true);
            int targetPort = resolveFlowPortIndex(nodeAt(nodes, targetNode), edgeJson.opt("targetPort"), false);
            if (sourcePort < 0 || targetPort < 0) {
                log.warn("Dropping a flow edge whose endpoint no longer resolves on its node (source {}, target {})",
                        sourceNode, targetNode);
                continue;
            }
            flowEdges.add(new ClipboardFlowEdge(sourceNode, sourcePort, targetNode, targetPort,
                    waypointsFromJson(edgeJson)));
        }

        return new GraphSnapshot(nodes, dataEdges, flowEdges);
    }

    /**
     * Upgrades a save file's JSON from {@code version} to {@link #CURRENT_VERSION}, returning the
     * (possibly transformed) root. The single seam for structural format migrations that the
     * shape-sniffing forgiving reads elsewhere can't express.
     * <p>
     * There are no such migrations yet — every shipped format (positional values, name-keyed values,
     * class-name and type-id node identity) is read directly by the loaders below regardless of
     * version — so this currently passes the root through unchanged after logging anything newer than
     * it understands. When you introduce one, add a step here (typically a {@code while (version <
     * CURRENT_VERSION)} ladder that transforms and bumps) and bump {@link #CURRENT_VERSION}.
     *
     * @param root    the parsed save file
     * @param version the file's declared version ({@link #LEGACY_VERSION} if it had none)
     * @return the root at the current version
     */
    private static JSONObject migrate(JSONObject root, int version) {
        if (version > CURRENT_VERSION) {
            log.warn("Save file version {} is newer than this build understands ({}); loading it as-is",
                    version, CURRENT_VERSION);
        }
        return root;
    }

    /** The node at {@code index} in a snapshot list, or null if out of range or a placeholder slot. */
    private static BaseNode nodeAt(List<ClipboardNode> nodes, int index) {
        return index >= 0 && index < nodes.size() ? nodes.get(index).node() : null;
    }

    /** The root {@code plugins} table as a lookup by library id. Empty for a v1 file, which has none. */
    private static Map<String, JSONObject> readPluginRows(JSONArray pluginsJson) {
        // A LinkedHashMap rather than Map.of() even when empty: a node with no "plugin" key looks it
        // up with a null id, and the immutable maps throw on a null key.
        Map<String, JSONObject> rows = new LinkedHashMap<>();
        if (pluginsJson == null) {
            return rows;
        }
        for (int i = 0; i < pluginsJson.length(); i++) {
            JSONObject row = pluginsJson.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String id = row.optString("id", null);
            if (id != null && !id.isBlank()) {
                rows.putIfAbsent(id, row);
            }
        }
        return rows;
    }

    /**
     * Gives every {@link MissingNode} the ports the edge lists say it had, so those edges resolve
     * onto it instead of being dropped. Runs before edge resolution, and only touches placeholders —
     * a real node's ports are authoritative and must never be invented from a save file.
     */
    private static void backFillPlaceholderPorts(List<ClipboardNode> nodes, JSONArray dataEdges, JSONArray flowEdges) {
        for (int i = 0; i < dataEdges.length(); i++) {
            JSONObject edgeJson = dataEdges.getJSONObject(i);
            if (nodeAt(nodes, edgeJson.getInt("sourceNode")) instanceof MissingNode source) {
                source.ensureDataPort(edgeJson.opt("sourceVariable"), true);
            }
            if (nodeAt(nodes, edgeJson.getInt("targetNode")) instanceof MissingNode target) {
                target.ensureDataPort(edgeJson.opt("targetVariable"), false);
            }
        }
        for (int i = 0; i < flowEdges.length(); i++) {
            JSONObject edgeJson = flowEdges.getJSONObject(i);
            if (nodeAt(nodes, edgeJson.getInt("sourceNode")) instanceof MissingNode source) {
                source.ensureFlowPort(edgeJson.opt("sourcePort"), true);
            }
            if (nodeAt(nodes, edgeJson.getInt("targetNode")) instanceof MissingNode target) {
                target.ensureFlowPort(edgeJson.opt("targetPort"), false);
            }
        }
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

    /**
     * Writes each variable as a {@code {name, value}} object so load can match it to an input by name
     * rather than position. Only manually-authored values are written; computed and secret values are
     * left null and recomputed on load (see {@link NodeVariable#isPersistentValue}).
     */
    @SuppressWarnings("unchecked")
    private static JSONArray valuesToJson(List<NodeVariable> variables) {
        JSONArray array = new JSONArray();
        for (NodeVariable variable : variables) {
            Object value = variable.isPersistentValue() ? variable.getValue() : null;
            JSONObject entry = new JSONObject();
            entry.put("name", variable.name);
            entry.put("value", value == null ? JSONObject.NULL : value);
            array.put(entry);
        }
        return array;
    }

    /**
     * Restores saved values onto a node's variables. New saves store {@code {name, value}} objects,
     * matched to a variable by unique name (falling back to the entry's ordinal position); old saves
     * store bare scalars, applied positionally.
     */
    @SuppressWarnings("unchecked")
    private static void applyValues(List<NodeVariable> variables, JSONArray values) {
        if (values.isEmpty()) {
            return;
        }
        if (values.get(0) instanceof JSONObject) {
            for (int i = 0; i < values.length(); i++) {
                JSONObject entry = values.getJSONObject(i);
                String name = entry.has("name") ? entry.getString("name") : null;
                Object raw = entry.isNull("value") ? null : entry.get("value");
                NodeVariable target = resolveVariable(variables, name, i);
                if (target != null) {
                    target.setValue(coerce(raw, target.type));
                }
            }
        } else {
            for (int i = 0; i < Math.min(variables.size(), values.length()); i++) {
                Object raw = values.isNull(i) ? null : values.get(i);
                variables.get(i).setValue(coerce(raw, variables.get(i).type));
            }
        }
    }

    /** The variable a saved value applies to: the uniquely name-matching one, else the one at {@code ordinal}. */
    @SuppressWarnings("rawtypes")
    private static NodeVariable resolveVariable(List<NodeVariable> variables, String name, int ordinal) {
        NodeVariable named = uniqueByName(variables, name);
        if (named != null) {
            return named;
        }
        return ordinal >= 0 && ordinal < variables.size() ? variables.get(ordinal) : null;
    }

    /**
     * The names of a node's {@link NodeVariable#isRequired() required} inputs, or {@code null} when
     * none are — so the common "no required inputs" node writes nothing. Read back by
     * {@link #applyRequired}. Storing names (not a positional boolean array) keeps the choice bound to
     * the right input when ports are reordered.
     */
    @SuppressWarnings("unchecked")
    private static JSONArray requiredInputsToJson(List<NodeVariable> inputs) {
        JSONArray array = new JSONArray();
        for (NodeVariable input : inputs) {
            if (input.isRequired()) {
                array.put(input.name);
            }
        }
        return array.isEmpty() ? null : array;
    }

    /**
     * Restores each input's required flag. A new save lists the required input <em>names</em> (an
     * input is required iff its name is present, so an author-required input the user turned off — and
     * thus omitted — reloads off). An old save lists a positional boolean per input.
     */
    @SuppressWarnings("unchecked")
    private static void applyRequired(List<NodeVariable> inputs, JSONArray required) {
        if (!required.isEmpty() && required.get(0) instanceof String) {
            List<Object> names = required.toList();
            for (NodeVariable input : inputs) {
                input.setRequired(names.contains(input.name));
            }
        } else {
            for (int i = 0; i < Math.min(inputs.size(), required.length()); i++) {
                inputs.get(i).setRequired(required.getBoolean(i));
            }
        }
    }

    /**
     * How an edge names one endpoint: the variable's {@code name} when it is non-blank and unique on
     * its node (so it survives a port reorder), else its positional {@code index} (the fallback for
     * the unnamed single ports most nodes have, and when the node isn't available). {@code output}
     * selects the outputs vs. inputs list.
     */
    private static Object variableRef(BaseNode node, int index, boolean output) {
        if (node == null) {
            return index;
        }
        List<NodeVariable> variables = output ? node.getOutputs() : node.getInputs();
        if (index < 0 || index >= variables.size()) {
            return index;
        }
        String name = variables.get(index).name;
        return uniqueByName(variables, name) != null ? name : index;
    }

    /** Resolves an edge's saved variable endpoint to a current index: a name uniquely, an integer positionally. */
    private static int resolveVariableIndex(BaseNode node, Object ref, boolean output) {
        if (ref instanceof Number number) {
            return number.intValue();
        }
        if (ref instanceof String name && node != null) {
            return indexByName(output ? node.getOutputs() : node.getInputs(), name);
        }
        return -1;
    }

    /** The flow-port counterpart to {@link #variableRef}: name when non-blank and unique, else index. */
    private static Object flowPortRef(BaseNode node, int index, boolean out) {
        if (node == null) {
            return index;
        }
        List<FlowPort> ports = out ? node.getFlowOutputs() : node.getFlowInputs();
        if (index < 0 || index >= ports.size()) {
            return index;
        }
        String name = ports.get(index).name;
        return uniqueFlowPortName(ports, name) ? name : index;
    }

    /**
     * Resolves an edge's saved flow-port endpoint to a current index: a name uniquely, an integer
     * positionally, and a missing reference to 0 (a pre-flow-port-identity save).
     */
    private static int resolveFlowPortIndex(BaseNode node, Object ref, boolean out) {
        if (ref == null) {
            return 0;
        }
        if (ref instanceof Number number) {
            return number.intValue();
        }
        if (ref instanceof String name && node != null) {
            List<FlowPort> ports = out ? node.getFlowOutputs() : node.getFlowInputs();
            for (int i = 0; i < ports.size(); i++) {
                if (ports.get(i).name.equals(name) && uniqueFlowPortName(ports, name)) {
                    return i;
                }
            }
            return -1;
        }
        return -1;
    }

    /** The single variable with this name, or null if the name is blank, absent, or shared by several. */
    @SuppressWarnings("rawtypes")
    private static NodeVariable uniqueByName(List<NodeVariable> variables, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        NodeVariable match = null;
        for (NodeVariable variable : variables) {
            if (variable.name.equals(name)) {
                if (match != null) {
                    return null; // ambiguous
                }
                match = variable;
            }
        }
        return match;
    }

    /** The index of the single variable with this name, or -1 if blank, absent, or ambiguous. */
    @SuppressWarnings("rawtypes")
    private static int indexByName(List<NodeVariable> variables, String name) {
        NodeVariable match = uniqueByName(variables, name);
        return match == null ? -1 : variables.indexOf(match);
    }

    private static boolean uniqueFlowPortName(List<FlowPort> ports, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        int count = 0;
        for (FlowPort port : ports) {
            if (port.name.equals(name)) {
                count++;
            }
        }
        return count == 1;
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
