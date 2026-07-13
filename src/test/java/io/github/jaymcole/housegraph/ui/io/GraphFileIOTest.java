package io.github.jaymcole.housegraph.ui.io;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardDataEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardNode;
import io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot;
import io.github.jaymcole.housegraph.graph.ExecutionPolicy;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import io.github.jaymcole.housegraph.graph.nodes.loader.SecretLoaderNode;
import io.github.jaymcole.housegraph.graph.nodes.object.ObjectDecomposerNode;
import javafx.geometry.Point2D;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises GraphFileIO's JSON conversion directly (no GraphCanvas/JavaFX toolkit
 * needed, since ClipboardNode/GraphSnapshot are plain data), including a real
 * toString()/re-parse round trip — the same text path save()/load() actually take.
 */
class GraphFileIOTest {

    @SuppressWarnings("unchecked")
    @Test
    void roundTripsNodesPositionsAndValues() {
        ConstantFloatNode constant = new ConstantFloatNode();
        constant.getOutputs().get(0).setValue(5f);

        AddNode add = new AddNode();

        GraphSnapshot snapshot = new GraphSnapshot(
                List.of(
                        new ClipboardNode(constant, 10.0, 20.0),
                        new ClipboardNode(add, 100.0, 20.0)),
                List.of(new ClipboardDataEdge(0, 0, 1, 0, List.of())),
                List.of());

        GraphSnapshot roundTripped = roundTrip(snapshot);

        assertEquals(2, roundTripped.nodes().size());

        ClipboardNode reconstructedConstant = roundTripped.nodes().get(0);
        assertTrue(reconstructedConstant.node() instanceof ConstantFloatNode);
        assertNotSame(constant, reconstructedConstant.node());
        assertEquals(10.0, reconstructedConstant.x());
        assertEquals(20.0, reconstructedConstant.y());
        assertEquals(5f, reconstructedConstant.node().getOutputs().get(0).getValue());

        ClipboardNode reconstructedAdd = roundTripped.nodes().get(1);
        assertTrue(reconstructedAdd.node() instanceof AddNode);
        assertEquals(100.0, reconstructedAdd.x());

        assertEquals(1, roundTripped.dataEdges().size());
        ClipboardDataEdge edge = roundTripped.dataEdges().get(0);
        assertEquals(0, edge.sourceNodeIndex());
        assertEquals(0, edge.sourceVariableIndex());
        assertEquals(1, edge.targetNodeIndex());
        assertEquals(0, edge.targetVariableIndex());
    }

    @Test
    void roundTripsFlowEdges() {
        AddNode a = new AddNode();
        AddNode b = new AddNode();

        // Non-zero source port index (as a decider's second branch would have) to prove
        // the specific port an edge leaves from survives the round trip, not just the
        // nodes; and a couple of waypoints to prove manual routing survives too.
        GraphSnapshot snapshot = new GraphSnapshot(
                List.of(
                        new ClipboardNode(a, 0.0, 0.0),
                        new ClipboardNode(b, 50.0, 0.0)),
                List.of(),
                List.of(new ClipboardFlowEdge(0, 1, 1, 0,
                        List.of(new Point2D(12.5, 34.0), new Point2D(60.0, -8.0)))));

        GraphSnapshot roundTripped = roundTrip(snapshot);

        assertEquals(1, roundTripped.flowEdges().size());
        ClipboardFlowEdge flowEdge = roundTripped.flowEdges().get(0);
        assertEquals(0, flowEdge.sourceNodeIndex());
        assertEquals(1, flowEdge.sourcePortIndex());
        assertEquals(1, flowEdge.targetNodeIndex());
        assertEquals(0, flowEdge.targetPortIndex());
        assertEquals(List.of(new Point2D(12.5, 34.0), new Point2D(60.0, -8.0)), flowEdge.waypoints());
    }

    @Test
    void unknownNodeTypeLoadsAsAPlaceholderRatherThanFailingTheWholeLoad() {
        JSONObject root = new JSONObject();
        root.put("nodes", List.of(unknownNodeJson(7.0, 9.0)));
        root.put("dataEdges", List.of());
        root.put("flowEdges", List.of());

        GraphSnapshot snapshot = GraphFileIO.fromJson(root);

        // The slot is preserved (with a null node and its saved position) so it can hold the
        // index for any later node; place() is what drops it off the actual canvas.
        assertEquals(1, snapshot.nodes().size());
        ClipboardNode placeholder = snapshot.nodes().get(0);
        assertNull(placeholder.node(), "an unrebuildable node loads as a null-node placeholder");
        assertEquals(7.0, placeholder.x());
        assertEquals(9.0, placeholder.y());
    }

    @Test
    void unknownNodeKeepsLaterNodesAtTheirOriginalIndexSoEdgesStayAligned() {
        // A real node, then an unknown one, then a real node an edge points at. Before the
        // placeholder fix the unknown node was dropped, shifting index 2 to 1 and rewiring the
        // edge onto the wrong node; now the placeholder holds index 1 and index 2 still resolves.
        JSONObject root = new JSONObject();
        root.put("nodes", List.of(
                realNodeJson(ConstantFloatNode.class),
                unknownNodeJson(0.0, 0.0),
                realNodeJson(AddNode.class)));
        // Edge from node 0's output into node 2's first input.
        JSONObject edge = new JSONObject();
        edge.put("sourceNode", 0);
        edge.put("sourceVariable", 0);
        edge.put("targetNode", 2);
        edge.put("targetVariable", 0);
        root.put("dataEdges", List.of(edge));
        root.put("flowEdges", List.of());

        GraphSnapshot snapshot = GraphFileIO.fromJson(root);

        assertEquals(3, snapshot.nodes().size());
        assertTrue(snapshot.nodes().get(0).node() instanceof ConstantFloatNode);
        assertNull(snapshot.nodes().get(1).node(), "the unknown node holds index 1 as a placeholder");
        assertTrue(snapshot.nodes().get(2).node() instanceof AddNode,
                "the node after the unknown one keeps its original index");
        // The edge's saved indices are untouched, and index 2 still lands on the AddNode.
        assertEquals(2, snapshot.dataEdges().get(0).targetNodeIndex());
    }

    private static JSONObject unknownNodeJson(double x, double y) {
        JSONObject nodeJson = new JSONObject();
        nodeJson.put("type", "com.example.NotARealNode");
        nodeJson.put("x", x);
        nodeJson.put("y", y);
        nodeJson.put("inputs", List.of());
        nodeJson.put("outputs", List.of());
        return nodeJson;
    }

    private static JSONObject realNodeJson(Class<? extends BaseNode> type) {
        JSONObject nodeJson = new JSONObject();
        nodeJson.put("type", type.getName());
        nodeJson.put("x", 0.0);
        nodeJson.put("y", 0.0);
        nodeJson.put("inputs", List.of());
        nodeJson.put("outputs", List.of());
        return nodeJson;
    }

    @Test
    void anAuthoredValueIsWrittenButAnAuthoredSecretIsNot() {
        SecretHolder node = new SecretHolder();
        node.plain.setValue("visible");
        node.secret.setValue("TOP_SECRET");

        JSONObject json = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(node, 0.0, 0.0)), List.of(), List.of()));

        JSONArray outputs = json.getJSONArray("nodes").getJSONObject(0).getJSONArray("outputs");
        assertEquals("visible", entryByName(outputs, "Plain").get("value"), "a manually-authored value is still written");
        assertTrue(entryByName(outputs, "Secret").isNull("value"), "the secret's value is null even though it's authored");
        assertFalse(json.toString().contains("TOP_SECRET"), "the secret value must appear nowhere in the file");
    }

    @Test
    void computedOutputValuesAreNotWrittenAndNonFiniteNumbersDontBreakSaving() {
        // A decomposer-style computed output holding a non-finite float used to blow up the
        // save (org.json rejects Infinity/NaN). Computed (non-editable) values are no longer
        // written at all, so saving succeeds and the slot is simply null.
        ComputedHolder node = new ComputedHolder();
        node.value.setValue(Float.POSITIVE_INFINITY);

        JSONObject json = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(node, 0.0, 0.0)), List.of(), List.of()));

        JSONArray outputs = json.getJSONArray("nodes").getJSONObject(0).getJSONArray("outputs");
        assertTrue(entryByName(outputs, "Value").isNull("value"), "a computed value is not written to disk");
        assertFalse(json.toString().toLowerCase().contains("infinity"), "no non-finite number reaches the file");
    }

    @Test
    void dynamicOutputsAreRebuiltFromNodeStateOnLoad() {
        // A decomposer's outputs come from its saved state (the property list), not from
        // any wired edge. On load the state must be restored before the ports are first
        // configured, or the outputs come back empty. Emulates a decomposer that had a
        // record-typed source with "title"/"size" properties.
        JSONObject nodeJson = new JSONObject();
        nodeJson.put("type", ObjectDecomposerNode.class.getName());
        nodeJson.put("x", 0.0);
        nodeJson.put("y", 0.0);
        nodeJson.put("inputs", new JSONArray(List.of(JSONObject.NULL)));
        nodeJson.put("outputs", new JSONArray());
        nodeJson.put("state", new JSONObject(Map.of("properties", "title:java.lang.String, size:java.lang.Integer")));

        JSONObject root = new JSONObject();
        root.put("nodes", new JSONArray(List.of(nodeJson)));
        root.put("dataEdges", new JSONArray());
        root.put("flowEdges", new JSONArray());

        GraphSnapshot snapshot = GraphFileIO.fromJson(root);

        BaseNode loaded = snapshot.nodes().get(0).node();
        List<String> outputNames = loaded.getOutputs().stream().map(variable -> variable.name).toList();
        assertEquals(List.of("title", "size"), outputNames, "decomposer outputs must regenerate from saved state on load");
    }

    @Test
    void nodeStateRoundTripsThroughSaveAndLoad() {
        // The Secret Loader persists which key it points at (never the secret) via node state.
        SecretLoaderNode source = new SecretLoaderNode();
        source.loadState(Map.of("key", "API_KEY"));

        GraphSnapshot roundTripped = roundTrip(new GraphSnapshot(
                List.of(new ClipboardNode(source, 0.0, 0.0)), List.of(), List.of()));

        assertEquals(1, roundTripped.nodes().size());
        assertEquals(Map.of("key", "API_KEY"), roundTripped.nodes().get(0).node().saveState());
    }

    @Test
    void executionPolicyRoundTripsAndDefaultsToQueueWhenAbsent() {
        AddNode restarting = new AddNode();
        restarting.setExecutionPolicy(ExecutionPolicy.RESTART);

        GraphSnapshot roundTripped = roundTrip(new GraphSnapshot(
                List.of(new ClipboardNode(restarting, 0.0, 0.0)), List.of(), List.of()));
        assertEquals(ExecutionPolicy.RESTART, roundTripped.nodes().get(0).node().getExecutionPolicy());

        // A save written before policies existed has no "executionPolicy" key; it must load as QUEUE.
        JSONObject legacyNode = new JSONObject();
        legacyNode.put("type", AddNode.class.getName());
        legacyNode.put("x", 0.0);
        legacyNode.put("y", 0.0);
        legacyNode.put("inputs", new JSONArray(List.of(JSONObject.NULL, JSONObject.NULL)));
        legacyNode.put("outputs", new JSONArray(List.of(JSONObject.NULL)));
        JSONObject legacyRoot = new JSONObject();
        legacyRoot.put("nodes", new JSONArray(List.of(legacyNode)));
        legacyRoot.put("dataEdges", new JSONArray());
        legacyRoot.put("flowEdges", new JSONArray());

        GraphSnapshot legacy = GraphFileIO.fromJson(legacyRoot);
        assertEquals(ExecutionPolicy.QUEUE, legacy.nodes().get(0).node().getExecutionPolicy(),
                "a save with no execution policy must default to QUEUE");
    }

    @Test
    void concurrencyLimitAndTimeoutRoundTripAndDefaultToOffWhenAbsent() {
        AddNode limited = new AddNode();
        limited.setMaxConcurrency(2);
        limited.setTimeoutMillis(5000);

        GraphSnapshot roundTripped = roundTrip(new GraphSnapshot(
                List.of(new ClipboardNode(limited, 0.0, 0.0)), List.of(), List.of()));
        BaseNode reloaded = roundTripped.nodes().get(0).node();
        assertEquals(2, reloaded.getMaxConcurrency());
        assertEquals(5000L, reloaded.getTimeoutMillis());

        // A node with defaults writes neither key; a save lacking them loads as unlimited / no timeout.
        AddNode plain = new AddNode();
        JSONObject json = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(plain, 0.0, 0.0)), List.of(), List.of()));
        JSONObject plainNode = json.getJSONArray("nodes").getJSONObject(0);
        assertFalse(plainNode.has("maxConcurrency"), "the default (unlimited) is not written");
        assertFalse(plainNode.has("timeoutMillis"), "the default (no timeout) is not written");

        BaseNode reloadedPlain = roundTrip(new GraphSnapshot(
                List.of(new ClipboardNode(plain, 0.0, 0.0)), List.of(), List.of())).nodes().get(0).node();
        assertEquals(0, reloadedPlain.getMaxConcurrency());
        assertEquals(0L, reloadedPlain.getTimeoutMillis());
    }

    @Test
    void requiredInputChoiceRoundTripsAndIsOnlyWrittenWhenSomethingIsRequired() {
        // The user marks AddNode's first input required; the choice must survive a save/load.
        AddNode add = new AddNode();
        add.getInputs().get(0).setRequired(true);

        GraphSnapshot roundTripped = roundTrip(new GraphSnapshot(
                List.of(new ClipboardNode(add, 0.0, 0.0)), List.of(), List.of()));
        BaseNode reloaded = roundTripped.nodes().get(0).node();
        assertTrue(reloaded.getInputs().get(0).isRequired(), "the required input must reload as required");
        assertFalse(reloaded.getInputs().get(1).isRequired(), "an input left optional must reload optional");

        // A node with no required inputs writes no requiredInputs key at all.
        AddNode plain = new AddNode();
        JSONObject json = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(plain, 0.0, 0.0)), List.of(), List.of()));
        assertFalse(json.getJSONArray("nodes").getJSONObject(0).has("requiredInputs"),
                "no requiredInputs key is written when nothing is required");
    }

    @Test
    void aUserCanOverrideAnAuthorRequiredInputToOptional() {
        // IfBoolNode's Condition is author-required; a save where the user turned it off must
        // reload with it off (the requiredInputs array carries the explicit false).
        io.github.jaymcole.housegraph.graph.nodes.control.IfBoolNode ifNode =
                new io.github.jaymcole.housegraph.graph.nodes.control.IfBoolNode();
        assertTrue(ifNode.getInputs().get(0).isRequired(), "Condition is author-required by default");
        ifNode.getInputs().get(0).setRequired(false);

        // Something else on the node is required so the array is actually written.
        JSONObject nodeJson = new JSONObject();
        nodeJson.put("type", io.github.jaymcole.housegraph.graph.nodes.control.IfBoolNode.class.getName());
        nodeJson.put("x", 0.0);
        nodeJson.put("y", 0.0);
        nodeJson.put("inputs", new JSONArray(List.of(JSONObject.NULL)));
        nodeJson.put("outputs", new JSONArray());
        nodeJson.put("requiredInputs", new JSONArray(List.of(false)));
        JSONObject root = new JSONObject();
        root.put("nodes", new JSONArray(List.of(nodeJson)));
        root.put("dataEdges", new JSONArray());
        root.put("flowEdges", new JSONArray());

        GraphSnapshot loaded = GraphFileIO.fromJson(root);
        assertFalse(loaded.nodes().get(0).node().getInputs().get(0).isRequired(),
                "an explicit false in requiredInputs overrides the author default");
    }

    @Test
    void legacySaveWithoutRequiredInputsKeepsAuthorDefaults() {
        // A save written before required inputs existed has no requiredInputs key; an
        // author-required input (IfBoolNode's Condition) must still load as required.
        JSONObject nodeJson = new JSONObject();
        nodeJson.put("type", io.github.jaymcole.housegraph.graph.nodes.control.IfBoolNode.class.getName());
        nodeJson.put("x", 0.0);
        nodeJson.put("y", 0.0);
        nodeJson.put("inputs", new JSONArray(List.of(JSONObject.NULL)));
        nodeJson.put("outputs", new JSONArray());
        JSONObject root = new JSONObject();
        root.put("nodes", new JSONArray(List.of(nodeJson)));
        root.put("dataEdges", new JSONArray());
        root.put("flowEdges", new JSONArray());

        GraphSnapshot loaded = GraphFileIO.fromJson(root);
        assertTrue(loaded.nodes().get(0).node().getInputs().get(0).isRequired(),
                "a missing requiredInputs key must leave the author default (required) intact");
    }

    // --- Name-keyed port identity (resilience to reorder) and its back-compat ------------------

    @Test
    void valuesAndEdgesBindByNameRegardlessOfSavedOrder() {
        // A save whose AddNode inputs are listed in the opposite order to the node's actual port
        // order, with a data edge pointing at "V2" by name. Name-keying must bind each value and the
        // edge to the right port regardless of position - the whole point of the format. (A purely
        // positional format would put V2's value on V1 and wire the edge to the wrong input.)
        JSONObject constant = nodeJson(ConstantFloatNode.class,
                new JSONArray(),
                new JSONArray(List.of(valueEntry("out", 5.0))));
        JSONObject add = nodeJson(AddNode.class,
                new JSONArray(List.of(valueEntry("V2", 2.0), valueEntry("V1", 1.0))), // reversed vs. configure order
                new JSONArray(List.of(valueEntry("Sum", JSONObject.NULL))));

        JSONObject edge = new JSONObject();
        edge.put("sourceNode", 0);
        edge.put("sourceVariable", "out");
        edge.put("targetNode", 1);
        edge.put("targetVariable", "V2");

        JSONObject root = new JSONObject();
        root.put("nodes", new JSONArray(List.of(constant, add)));
        root.put("dataEdges", new JSONArray(List.of(edge)));
        root.put("flowEdges", new JSONArray());

        GraphSnapshot loaded = GraphFileIO.fromJson(root);

        BaseNode addNode = loaded.nodes().get(1).node();
        assertEquals(1.0f, addNode.getInputs().get(0).getValue(), "V1's value binds by name, not by saved position");
        assertEquals(2.0f, addNode.getInputs().get(1).getValue(), "V2's value binds by name, not by saved position");

        ClipboardDataEdge dataEdge = loaded.dataEdges().get(0);
        assertEquals(0, dataEdge.sourceVariableIndex(), "\"out\" resolves to the constant's output index");
        assertEquals(1, dataEdge.targetVariableIndex(), "\"V2\" resolves to AddNode's second input regardless of order");
    }

    @Test
    void namedEndpointsAreWrittenByNameAndUnnamedFlowPortsByIndex() {
        AddNode source = new AddNode();
        AddNode target = new AddNode();
        JSONObject json = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(source, 0, 0), new ClipboardNode(target, 0, 0)),
                List.of(new ClipboardDataEdge(0, 0, 1, 1, List.of())),   // Sum -> V2
                List.of(new ClipboardFlowEdge(0, 0, 1, 0, List.of()))));  // the single unnamed flow ports

        JSONObject dataEdge = json.getJSONArray("dataEdges").getJSONObject(0);
        assertEquals("Sum", dataEdge.get("sourceVariable"), "a named output is referenced by name");
        assertEquals("V2", dataEdge.get("targetVariable"), "a named input is referenced by name");

        JSONObject flowEdge = json.getJSONArray("flowEdges").getJSONObject(0);
        assertEquals(0, flowEdge.get("sourcePort"), "an unnamed single flow port falls back to its index");
        assertEquals(0, flowEdge.get("targetPort"));
    }

    @Test
    void anEdgeWhoseNamedEndpointNoLongerExistsIsDroppedNotMiswired() {
        JSONObject constant = nodeJson(ConstantFloatNode.class,
                new JSONArray(), new JSONArray(List.of(valueEntry("out", 5.0))));
        JSONObject add = nodeJson(AddNode.class,
                new JSONArray(List.of(valueEntry("V1", JSONObject.NULL), valueEntry("V2", JSONObject.NULL))),
                new JSONArray(List.of(valueEntry("Sum", JSONObject.NULL))));

        JSONObject edge = new JSONObject();
        edge.put("sourceNode", 0);
        edge.put("sourceVariable", "out");
        edge.put("targetNode", 1);
        edge.put("targetVariable", "Renamed"); // a port that no longer exists on AddNode

        JSONObject root = new JSONObject();
        root.put("nodes", new JSONArray(List.of(constant, add)));
        root.put("dataEdges", new JSONArray(List.of(edge)));
        root.put("flowEdges", new JSONArray());

        GraphSnapshot loaded = GraphFileIO.fromJson(root);
        assertTrue(loaded.dataEdges().isEmpty(), "an edge to a vanished named port is dropped, not attached to the wrong input");
    }

    @Test
    void legacyPositionalSaveStillLoads() {
        // A pre-name-keying file: bare scalar value arrays and integer edge references.
        JSONObject constant = nodeJson(ConstantFloatNode.class,
                new JSONArray(), new JSONArray(List.of(5.0)));
        JSONObject add = nodeJson(AddNode.class,
                new JSONArray(List.of(JSONObject.NULL, JSONObject.NULL)),
                new JSONArray(List.of(JSONObject.NULL)));

        JSONObject edge = new JSONObject();
        edge.put("sourceNode", 0);
        edge.put("sourceVariable", 0);
        edge.put("targetNode", 1);
        edge.put("targetVariable", 0);

        JSONObject root = new JSONObject();
        root.put("nodes", new JSONArray(List.of(constant, add)));
        root.put("dataEdges", new JSONArray(List.of(edge)));
        root.put("flowEdges", new JSONArray());

        GraphSnapshot loaded = GraphFileIO.fromJson(root);
        assertEquals(5f, loaded.nodes().get(0).node().getOutputs().get(0).getValue(), "a legacy scalar value loads positionally");
        ClipboardDataEdge dataEdge = loaded.dataEdges().get(0);
        assertEquals(0, dataEdge.sourceVariableIndex());
        assertEquals(0, dataEdge.targetVariableIndex());
    }

    // --- Stable type identity and format version -----------------------------------------------

    @Test
    void nodeTypeIsWrittenAsItsStableIdAndTheRootIsVersioned() {
        JSONObject json = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(new AddNode(), 0, 0)), List.of(), List.of()));

        assertEquals(GraphFileIO.CURRENT_VERSION, json.getInt("version"), "the root carries the current format version");
        assertEquals("AddNode", json.getJSONArray("nodes").getJSONObject(0).getString("type"),
                "a node is identified by its stable type id (the simple class name), not its fully-qualified name");
    }

    @Test
    void aSaveIdentifyingANodeByStableIdLoads() {
        JSONObject node = nodeJson(AddNode.class, new JSONArray(), new JSONArray());
        node.put("type", "AddNode"); // the stable id a new save writes
        JSONObject root = new JSONObject();
        root.put("version", GraphFileIO.CURRENT_VERSION);
        root.put("nodes", new JSONArray(List.of(node)));
        root.put("dataEdges", new JSONArray());
        root.put("flowEdges", new JSONArray());

        GraphSnapshot loaded = GraphFileIO.fromJson(root);
        assertTrue(loaded.nodes().get(0).node() instanceof AddNode, "a stable-id type resolves back to its class");
    }

    @Test
    void aLegacyFileWithAFullyQualifiedTypeAndNoVersionStillLoads() {
        // Pre-#2 saves stored the fully-qualified class name and had no "version" key.
        JSONObject node = nodeJson(AddNode.class, new JSONArray(), new JSONArray()); // type = FQCN
        JSONObject root = new JSONObject();
        root.put("nodes", new JSONArray(List.of(node)));
        root.put("dataEdges", new JSONArray());
        root.put("flowEdges", new JSONArray());

        GraphSnapshot loaded = GraphFileIO.fromJson(root);
        assertTrue(loaded.nodes().get(0).node() instanceof AddNode,
                "a fully-qualified class name still resolves and a missing version loads as legacy");
    }

    private static JSONObject nodeJson(Class<? extends BaseNode> type, JSONArray inputs, JSONArray outputs) {
        JSONObject nodeJson = new JSONObject();
        nodeJson.put("type", type.getName());
        nodeJson.put("x", 0.0);
        nodeJson.put("y", 0.0);
        nodeJson.put("inputs", inputs);
        nodeJson.put("outputs", outputs);
        return nodeJson;
    }

    private static JSONObject valueEntry(String name, Object value) {
        JSONObject entry = new JSONObject();
        entry.put("name", name);
        entry.put("value", value);
        return entry;
    }

    private static JSONObject entryByName(JSONArray entries, String name) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (name.equals(entry.optString("name", null))) {
                return entry;
            }
        }
        throw new IllegalArgumentException("No value entry named " + name);
    }

    private static GraphSnapshot roundTrip(GraphSnapshot snapshot) {
        String text = GraphFileIO.toJson(snapshot).toString();
        return GraphFileIO.fromJson(new JSONObject(new JSONTokener(text)));
    }

    /** A node with one authored and one authored-secret output, for checking secrets don't get serialised. */
    private static final class SecretHolder extends BaseNode {
        final NodeVariable<String> plain = new NodeVariable<>("Plain", String.class, true);
        final NodeVariable<String> secret = new NodeVariable<>("Secret", String.class, true).markSecret();

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(plain);
            addOutput(secret);
        }
    }

    /** A node with a single computed (non-editable) output, for checking computed values aren't serialised. */
    private static final class ComputedHolder extends BaseNode {
        final NodeVariable<Float> value = new NodeVariable<>("Value", Float.class);

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(value);
        }
    }
}
