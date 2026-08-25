package io.github.jaymcole.housegraph.catalog;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.TypeConverters;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Statically analyzes a parsed save file for structural defects that {@code GraphFileIO} and
 * {@code GraphCanvas} would otherwise only discover by silently dropping an edge (a dangling
 * reference, an unresolvable port, an incompatible data type) or by crashing at run time (a data
 * cycle) — the failure modes a hand-written or agent-generated graph is most likely to hit, and
 * that a GUI session would normally surface one click at a time.
 *
 * <p>Every {@link Finding} carries a JSON Pointer (RFC 6901) into the save file, so a caller can map
 * it straight back to the offending array element without re-deriving indices — the same
 * node/edge-by-index identity the format itself uses (see {@code docs/engine/save-format.md}).
 *
 * <p>Two tiers of check run, matching the file's own layers:
 * <ul>
 *   <li><b>Purely structural</b> — dangling node references and duplicate data edges into one
 *   input — need only the array shapes and indices, so they run even when {@code registry} cannot
 *   resolve a node's class (an uninstalled library).</li>
 *   <li><b>Type-aware</b> — an unresolvable port reference and a data/flow type mismatch — need a
 *   real instance of the node to read its ports, exactly like {@link SchemaDriftCheck}. A node
 *   whose type does not resolve is skipped for these (silently: {@code GraphDependencyCheck} is
 *   what reports a missing library), which is also why this class instantiates nodes rather than
 *   reusing {@code GraphFileIO.fromJson} — that method already drops an unresolvable edge with only
 *   a log line, which is exactly the information a caller here needs back as a structured finding.
 * </ul>
 *
 * <p>Cycle detection covers only <b>data</b> edges: a data cycle is a hard runtime failure
 * ({@code NodeGraph.resolveInternal} throws {@code IllegalStateException} the first time such a
 * node is pulled). A flow cycle is not reported — the engine's per-run {@code flowVisited} dedup
 * makes a loop back through already-fired nodes an ordinary, well-defined no-op, not a defect.
 */
public final class GraphStructureValidator {

    private static final Logger log = Log.get(GraphStructureValidator.class);

    /** Stable, machine-matchable finding codes. */
    public static final class Codes {
        public static final String DANGLING_NODE_REFERENCE = "dangling-node-reference";
        public static final String UNRESOLVED_PORT = "unresolved-port";
        public static final String TYPE_MISMATCH = "type-mismatch";
        public static final String DUPLICATE_INPUT_EDGE = "duplicate-input-edge";
        public static final String DATA_CYCLE = "data-cycle";

        private Codes() {
        }
    }

    public enum Severity {
        ERROR,
        WARNING
    }

    /**
     * One defect found in the save file.
     *
     * @param code            a stable identifier from {@link Codes}, for matching without parsing prose
     * @param severity        whether this would break the graph ({@link Severity#ERROR}) or is only worth a look
     * @param message         a human-readable explanation
     * @param pointer         an RFC 6901 JSON Pointer to the primary offending location in the save file
     * @param relatedPointers additional pointers involved (e.g. every edge in a reported cycle, or the
     *                        other edge a duplicate collides with); empty when {@code pointer} is the whole story
     */
    public record Finding(String code, Severity severity, String message, String pointer,
                          List<String> relatedPointers) {

        public Finding {
            relatedPointers = List.copyOf(relatedPointers);
        }

        static Finding error(String code, String message, String pointer) {
            return new Finding(code, Severity.ERROR, message, pointer, List.of());
        }

        static Finding error(String code, String message, String pointer, List<String> related) {
            return new Finding(code, Severity.ERROR, message, pointer, related);
        }
    }

    /**
     * @param findings every defect found, in the order the checks ran
     */
    public record Report(List<Finding> findings) {

        public Report {
            findings = List.copyOf(findings);
        }

        /** Whether the graph is free of anything that would break it (warnings don't count). */
        public boolean isValid() {
            return findings.stream().noneMatch(f -> f.severity() == Severity.ERROR);
        }
    }

    private GraphStructureValidator() {
    }

    /**
     * Runs every structural and type-aware check against a parsed save file.
     *
     * @param root     a parsed save file (see {@code GraphFileIO.readRoot})
     * @param registry resolves each node's saved type to a class, exactly as loading would; a type
     *                 that doesn't resolve is skipped for the checks that need an instance
     * @return every defect found, empty when the graph is structurally sound
     */
    public static Report inspect(JSONObject root, NodeRegistry registry) {
        List<Finding> findings = new ArrayList<>();

        JSONArray nodesJson = root.optJSONArray("nodes");
        int nodeCount = nodesJson == null ? 0 : nodesJson.length();
        BaseNode[] nodes = new BaseNode[nodeCount];
        if (nodesJson != null) {
            for (int i = 0; i < nodeCount; i++) {
                nodes[i] = resolveNode(nodesJson.optJSONObject(i), registry);
            }
        }

        JSONArray dataEdgesJson = root.optJSONArray("dataEdges");
        JSONArray flowEdgesJson = root.optJSONArray("flowEdges");

        // Node-index pairs for data edges whose endpoints are both in bounds - the graph cycle
        // detection walks, unpolluted by an edge already reported as dangling.
        List<int[]> dataEdgeNodePairs = new ArrayList<>();
        // Index into dataEdgeNodePairs -> original dataEdges[] index, for reporting a cycle's edges.
        List<Integer> dataEdgeOriginalIndex = new ArrayList<>();

        // Keyed by (targetNode, canonical target port identity) -> every dataEdges[] index feeding it.
        Map<String, List<Integer>> incomingByInput = new LinkedHashMap<>();

        if (dataEdgesJson != null) {
            for (int i = 0; i < dataEdgesJson.length(); i++) {
                JSONObject edgeJson = dataEdgesJson.optJSONObject(i);
                if (edgeJson == null) {
                    continue;
                }
                String pointer = "/dataEdges/" + i;
                int sourceNode = edgeJson.optInt("sourceNode", -1);
                int targetNode = edgeJson.optInt("targetNode", -1);
                boolean sourceInBounds = inBounds(sourceNode, nodeCount);
                boolean targetInBounds = inBounds(targetNode, nodeCount);
                if (!sourceInBounds) {
                    findings.add(Finding.error(Codes.DANGLING_NODE_REFERENCE,
                            "sourceNode " + sourceNode + " does not name a node in this file (nodes has "
                                    + nodeCount + ")", pointer + "/sourceNode"));
                }
                if (!targetInBounds) {
                    findings.add(Finding.error(Codes.DANGLING_NODE_REFERENCE,
                            "targetNode " + targetNode + " does not name a node in this file (nodes has "
                                    + nodeCount + ")", pointer + "/targetNode"));
                }
                if (!sourceInBounds || !targetInBounds) {
                    continue;
                }

                dataEdgeNodePairs.add(new int[]{sourceNode, targetNode});
                dataEdgeOriginalIndex.add(i);

                BaseNode sourceNodeInstance = nodes[sourceNode];
                BaseNode targetNodeInstance = nodes[targetNode];
                NodeVariable<?> sourceVariable = sourceNodeInstance == null ? null
                        : resolveVariable(sourceNodeInstance.getOutputs(), edgeJson.opt("sourceVariable"));
                NodeVariable<?> targetVariable = targetNodeInstance == null ? null
                        : resolveVariable(targetNodeInstance.getInputs(), edgeJson.opt("targetVariable"));

                if (sourceNodeInstance != null && sourceVariable == null) {
                    findings.add(Finding.error(Codes.UNRESOLVED_PORT,
                            "sourceVariable " + edgeJson.opt("sourceVariable") + " does not name an output on node "
                                    + sourceNode, pointer + "/sourceVariable"));
                }
                if (targetNodeInstance != null && targetVariable == null) {
                    findings.add(Finding.error(Codes.UNRESOLVED_PORT,
                            "targetVariable " + edgeJson.opt("targetVariable") + " does not name an input on node "
                                    + targetNode, pointer + "/targetVariable"));
                }
                if (sourceVariable != null && targetVariable != null
                        && !TypeConverters.isCompatible(sourceVariable.type, targetVariable.type)) {
                    findings.add(Finding.error(Codes.TYPE_MISMATCH,
                            "a " + sourceVariable.type.getSimpleName() + " output cannot feed a "
                                    + targetVariable.type.getSimpleName() + " input", pointer));
                }

                String inputKey = targetNode + ":" + (targetVariable != null ? "v" + targetVariable.name
                        : "r" + edgeJson.opt("targetVariable"));
                incomingByInput.computeIfAbsent(inputKey, key -> new ArrayList<>()).add(i);
            }
        }

        for (List<Integer> edgeIndices : incomingByInput.values()) {
            if (edgeIndices.size() < 2) {
                continue;
            }
            for (int k = 1; k < edgeIndices.size(); k++) {
                List<String> related = new ArrayList<>();
                for (int other : edgeIndices) {
                    if (other != edgeIndices.get(k)) {
                        related.add("/dataEdges/" + other);
                    }
                }
                findings.add(Finding.error(Codes.DUPLICATE_INPUT_EDGE,
                        "this input is already fed by another data edge; an input can have only one source",
                        "/dataEdges/" + edgeIndices.get(k), related));
            }
        }

        findDataCycles(dataEdgeNodePairs, dataEdgeOriginalIndex, nodeCount, findings);

        if (flowEdgesJson != null) {
            for (int i = 0; i < flowEdgesJson.length(); i++) {
                JSONObject edgeJson = flowEdgesJson.optJSONObject(i);
                if (edgeJson == null) {
                    continue;
                }
                String pointer = "/flowEdges/" + i;
                int sourceNode = edgeJson.optInt("sourceNode", -1);
                int targetNode = edgeJson.optInt("targetNode", -1);
                boolean sourceInBounds = inBounds(sourceNode, nodeCount);
                boolean targetInBounds = inBounds(targetNode, nodeCount);
                if (!sourceInBounds) {
                    findings.add(Finding.error(Codes.DANGLING_NODE_REFERENCE,
                            "sourceNode " + sourceNode + " does not name a node in this file (nodes has "
                                    + nodeCount + ")", pointer + "/sourceNode"));
                }
                if (!targetInBounds) {
                    findings.add(Finding.error(Codes.DANGLING_NODE_REFERENCE,
                            "targetNode " + targetNode + " does not name a node in this file (nodes has "
                                    + nodeCount + ")", pointer + "/targetNode"));
                }
                if (!sourceInBounds || !targetInBounds) {
                    continue;
                }

                BaseNode sourceNodeInstance = nodes[sourceNode];
                BaseNode targetNodeInstance = nodes[targetNode];
                if (sourceNodeInstance != null && edgeJson.has("sourcePort")
                        && resolveFlowPort(sourceNodeInstance.getFlowOutputs(), edgeJson.opt("sourcePort")) == null) {
                    findings.add(Finding.error(Codes.UNRESOLVED_PORT,
                            "sourcePort " + edgeJson.opt("sourcePort") + " does not name a flow output on node "
                                    + sourceNode, pointer + "/sourcePort"));
                }
                if (targetNodeInstance != null && edgeJson.has("targetPort")
                        && resolveFlowPort(targetNodeInstance.getFlowInputs(), edgeJson.opt("targetPort")) == null) {
                    findings.add(Finding.error(Codes.UNRESOLVED_PORT,
                            "targetPort " + edgeJson.opt("targetPort") + " does not name a flow input on node "
                                    + targetNode, pointer + "/targetPort"));
                }
            }
        }

        return new Report(findings);
    }

    private static boolean inBounds(int index, int count) {
        return index >= 0 && index < count;
    }

    /**
     * Instantiates the node at this JSON entry so its ports can be read, or null when its type
     * doesn't resolve or won't instantiate — either way, type-aware checks touching this node index
     * are silently skipped, the same discipline {@link SchemaDriftCheck} applies.
     */
    private static BaseNode resolveNode(JSONObject nodeJson, NodeRegistry registry) {
        if (nodeJson == null) {
            return null;
        }
        String type = nodeJson.optString("type", null);
        String pluginId = nodeJson.optString("plugin", null);
        Class<? extends BaseNode> nodeClass = registry.resolveClass(type, pluginId);
        if (nodeClass == null) {
            return null;
        }
        BaseNode node = NodeRegistry.instantiate(nodeClass);
        if (node == null) {
            return null;
        }
        try {
            // Before any port is read: a dynamic-port node (the object decomposer) builds its ports
            // from saved state, mirroring GraphFileIO.fromJson's ordering.
            if (nodeJson.has("state")) {
                Map<String, String> state = new HashMap<>();
                JSONObject stateJson = nodeJson.getJSONObject("state");
                for (String key : stateJson.keySet()) {
                    state.put(key, stateJson.optString(key, ""));
                }
                node.loadState(state);
            }
        } catch (RuntimeException e) {
            log.warn("Could not restore state for a \"{}\" node while validating: {}", type, e.toString());
        }
        return node;
    }

    /** Resolves a data-edge {@code portRef} (a unique name, or a positional index) against a variable list. */
    @SuppressWarnings("rawtypes")
    private static NodeVariable<?> resolveVariable(List<NodeVariable> variables, Object ref) {
        if (ref instanceof String name) {
            NodeVariable<?> match = null;
            for (NodeVariable variable : variables) {
                if (variable.name.equals(name)) {
                    if (match != null) {
                        return null; // ambiguous, same as GraphFileIO's own name resolution
                    }
                    match = variable;
                }
            }
            return match;
        }
        if (ref instanceof Number number) {
            int index = number.intValue();
            return index >= 0 && index < variables.size() ? variables.get(index) : null;
        }
        return null;
    }

    /** The flow-port counterpart to {@link #resolveVariable}. */
    private static FlowPort resolveFlowPort(List<FlowPort> ports, Object ref) {
        if (ref instanceof String name) {
            FlowPort match = null;
            for (FlowPort port : ports) {
                if (port.name.equals(name)) {
                    if (match != null) {
                        return null;
                    }
                    match = port;
                }
            }
            return match;
        }
        if (ref instanceof Number number) {
            int index = number.intValue();
            return index >= 0 && index < ports.size() ? ports.get(index) : null;
        }
        return null;
    }

    /**
     * Reports one finding per back-edge a depth-first walk finds in the directed graph of node
     * indices that data edges form — the standard white/gray/black cycle detection, the same shape
     * as a topological sort's failure case. Every node is walked exactly once; a graph with several
     * disjoint cycles gets one finding per cycle, not one per node in it.
     */
    private static void findDataCycles(List<int[]> edges, List<Integer> originalEdgeIndex, int nodeCount,
                                       List<Finding> findings) {
        if (nodeCount == 0 || edges.isEmpty()) {
            return;
        }
        Map<Integer, List<Integer>> outgoing = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            outgoing.computeIfAbsent(edges.get(i)[0], key -> new ArrayList<>()).add(i);
        }

        int[] state = new int[nodeCount]; // 0 = white, 1 = gray (on the current DFS path), 2 = black (done)
        // Positions into `edges`/`originalEdgeIndex` for the edges on the current DFS path, in order.
        List<Integer> path = new ArrayList<>();
        for (int start = 0; start < nodeCount; start++) {
            if (state[start] == 0) {
                walk(start, outgoing, edges, originalEdgeIndex, state, path, findings);
            }
        }
    }

    private static void walk(int node, Map<Integer, List<Integer>> outgoing, List<int[]> edges,
                             List<Integer> originalEdgeIndex, int[] state, List<Integer> path,
                             List<Finding> findings) {
        state[node] = 1;
        for (int edgePosition : outgoing.getOrDefault(node, List.of())) {
            int next = edges.get(edgePosition)[1];
            path.add(edgePosition);
            if (state[next] == 1) {
                reportCycle(next, path, edges, originalEdgeIndex, findings);
            } else if (state[next] == 0) {
                walk(next, outgoing, edges, originalEdgeIndex, state, path, findings);
            }
            path.remove(path.size() - 1);
        }
        state[node] = 2;
    }

    /**
     * {@code edgePosition} just closed a cycle back to {@code reentryNode}, which is still gray (on
     * the current DFS path). The cycle itself is the suffix of {@code path} starting at the edge that
     * first left {@code reentryNode} — everything on the path before that belongs to how the walk
     * reached the cycle, not the cycle.
     */
    private static void reportCycle(int reentryNode, List<Integer> path, List<int[]> edges,
                                    List<Integer> originalEdgeIndex, List<Finding> findings) {
        int startAt = 0;
        for (int i = 0; i < path.size(); i++) {
            if (edges.get(path.get(i))[0] == reentryNode) {
                startAt = i;
                break;
            }
        }
        List<String> cycleEdgePointers = new ArrayList<>();
        for (int i = startAt; i < path.size(); i++) {
            cycleEdgePointers.add("/dataEdges/" + originalEdgeIndex.get(path.get(i)));
        }
        String primary = cycleEdgePointers.get(0);
        List<String> related = cycleEdgePointers.size() > 1
                ? cycleEdgePointers.subList(1, cycleEdgePointers.size())
                : List.of();
        findings.add(Finding.error(Codes.DATA_CYCLE,
                "these data edges form a cycle; pulling any node on it fails at run time", primary, related));
    }
}
