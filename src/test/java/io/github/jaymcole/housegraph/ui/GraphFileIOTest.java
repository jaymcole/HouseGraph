package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.BaseNode;
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

        GraphCanvas.GraphSnapshot snapshot = new GraphCanvas.GraphSnapshot(
                List.of(
                        new GraphCanvas.ClipboardNode(constant, 10.0, 20.0),
                        new GraphCanvas.ClipboardNode(add, 100.0, 20.0)),
                List.of(new GraphCanvas.ClipboardDataEdge(0, 0, 1, 0, List.of())),
                List.of());

        GraphCanvas.GraphSnapshot roundTripped = roundTrip(snapshot);

        assertEquals(2, roundTripped.nodes().size());

        GraphCanvas.ClipboardNode reconstructedConstant = roundTripped.nodes().get(0);
        assertTrue(reconstructedConstant.node() instanceof ConstantFloatNode);
        assertNotSame(constant, reconstructedConstant.node());
        assertEquals(10.0, reconstructedConstant.x());
        assertEquals(20.0, reconstructedConstant.y());
        assertEquals(5f, reconstructedConstant.node().getOutputs().get(0).getValue());

        GraphCanvas.ClipboardNode reconstructedAdd = roundTripped.nodes().get(1);
        assertTrue(reconstructedAdd.node() instanceof AddNode);
        assertEquals(100.0, reconstructedAdd.x());

        assertEquals(1, roundTripped.dataEdges().size());
        GraphCanvas.ClipboardDataEdge edge = roundTripped.dataEdges().get(0);
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
        GraphCanvas.GraphSnapshot snapshot = new GraphCanvas.GraphSnapshot(
                List.of(
                        new GraphCanvas.ClipboardNode(a, 0.0, 0.0),
                        new GraphCanvas.ClipboardNode(b, 50.0, 0.0)),
                List.of(),
                List.of(new GraphCanvas.ClipboardFlowEdge(0, 1, 1, 0,
                        List.of(new Point2D(12.5, 34.0), new Point2D(60.0, -8.0)))));

        GraphCanvas.GraphSnapshot roundTripped = roundTrip(snapshot);

        assertEquals(1, roundTripped.flowEdges().size());
        GraphCanvas.ClipboardFlowEdge flowEdge = roundTripped.flowEdges().get(0);
        assertEquals(0, flowEdge.sourceNodeIndex());
        assertEquals(1, flowEdge.sourcePortIndex());
        assertEquals(1, flowEdge.targetNodeIndex());
        assertEquals(0, flowEdge.targetPortIndex());
        assertEquals(List.of(new Point2D(12.5, 34.0), new Point2D(60.0, -8.0)), flowEdge.waypoints());
    }

    @Test
    void unknownNodeTypeIsSkippedRatherThanFailingTheWholeLoad() {
        JSONObject nodeJson = new JSONObject();
        nodeJson.put("type", "com.example.NotARealNode");
        nodeJson.put("x", 0.0);
        nodeJson.put("y", 0.0);
        nodeJson.put("inputs", List.of());
        nodeJson.put("outputs", List.of());

        JSONObject root = new JSONObject();
        root.put("nodes", List.of(nodeJson));
        root.put("dataEdges", List.of());
        root.put("flowEdges", List.of());

        GraphCanvas.GraphSnapshot snapshot = GraphFileIO.fromJson(root);

        assertTrue(snapshot.nodes().isEmpty());
    }

    @Test
    void anAuthoredValueIsWrittenButAnAuthoredSecretIsNot() {
        SecretHolder node = new SecretHolder();
        node.plain.setValue("visible");
        node.secret.setValue("TOP_SECRET");

        JSONObject json = GraphFileIO.toJson(new GraphCanvas.GraphSnapshot(
                List.of(new GraphCanvas.ClipboardNode(node, 0.0, 0.0)), List.of(), List.of()));

        JSONArray outputs = json.getJSONArray("nodes").getJSONObject(0).getJSONArray("outputs");
        assertEquals("visible", outputs.get(0), "a manually-authored value is still written");
        assertTrue(outputs.isNull(1), "the secret's slot is null even though it's authored");
        assertFalse(json.toString().contains("TOP_SECRET"), "the secret value must appear nowhere in the file");
    }

    @Test
    void computedOutputValuesAreNotWrittenAndNonFiniteNumbersDontBreakSaving() {
        // A decomposer-style computed output holding a non-finite float used to blow up the
        // save (org.json rejects Infinity/NaN). Computed (non-editable) values are no longer
        // written at all, so saving succeeds and the slot is simply null.
        ComputedHolder node = new ComputedHolder();
        node.value.setValue(Float.POSITIVE_INFINITY);

        JSONObject json = GraphFileIO.toJson(new GraphCanvas.GraphSnapshot(
                List.of(new GraphCanvas.ClipboardNode(node, 0.0, 0.0)), List.of(), List.of()));

        JSONArray outputs = json.getJSONArray("nodes").getJSONObject(0).getJSONArray("outputs");
        assertTrue(outputs.isNull(0), "a computed value is not written to disk");
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

        GraphCanvas.GraphSnapshot snapshot = GraphFileIO.fromJson(root);

        BaseNode loaded = snapshot.nodes().get(0).node();
        List<String> outputNames = loaded.getOutputs().stream().map(variable -> variable.name).toList();
        assertEquals(List.of("title", "size"), outputNames, "decomposer outputs must regenerate from saved state on load");
    }

    @Test
    void nodeStateRoundTripsThroughSaveAndLoad() {
        // The Secret Loader persists which key it points at (never the secret) via node state.
        SecretLoaderNode source = new SecretLoaderNode();
        source.loadState(Map.of("key", "API_KEY"));

        GraphCanvas.GraphSnapshot roundTripped = roundTrip(new GraphCanvas.GraphSnapshot(
                List.of(new GraphCanvas.ClipboardNode(source, 0.0, 0.0)), List.of(), List.of()));

        assertEquals(1, roundTripped.nodes().size());
        assertEquals(Map.of("key", "API_KEY"), roundTripped.nodes().get(0).node().saveState());
    }

    @Test
    void executionPolicyRoundTripsAndDefaultsToQueueWhenAbsent() {
        AddNode restarting = new AddNode();
        restarting.setExecutionPolicy(ExecutionPolicy.RESTART);

        GraphCanvas.GraphSnapshot roundTripped = roundTrip(new GraphCanvas.GraphSnapshot(
                List.of(new GraphCanvas.ClipboardNode(restarting, 0.0, 0.0)), List.of(), List.of()));
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

        GraphCanvas.GraphSnapshot legacy = GraphFileIO.fromJson(legacyRoot);
        assertEquals(ExecutionPolicy.QUEUE, legacy.nodes().get(0).node().getExecutionPolicy(),
                "a save with no execution policy must default to QUEUE");
    }

    @Test
    void concurrencyLimitAndTimeoutRoundTripAndDefaultToOffWhenAbsent() {
        AddNode limited = new AddNode();
        limited.setMaxConcurrency(2);
        limited.setTimeoutMillis(5000);

        GraphCanvas.GraphSnapshot roundTripped = roundTrip(new GraphCanvas.GraphSnapshot(
                List.of(new GraphCanvas.ClipboardNode(limited, 0.0, 0.0)), List.of(), List.of()));
        BaseNode reloaded = roundTripped.nodes().get(0).node();
        assertEquals(2, reloaded.getMaxConcurrency());
        assertEquals(5000L, reloaded.getTimeoutMillis());

        // A node with defaults writes neither key; a save lacking them loads as unlimited / no timeout.
        AddNode plain = new AddNode();
        JSONObject json = GraphFileIO.toJson(new GraphCanvas.GraphSnapshot(
                List.of(new GraphCanvas.ClipboardNode(plain, 0.0, 0.0)), List.of(), List.of()));
        JSONObject plainNode = json.getJSONArray("nodes").getJSONObject(0);
        assertFalse(plainNode.has("maxConcurrency"), "the default (unlimited) is not written");
        assertFalse(plainNode.has("timeoutMillis"), "the default (no timeout) is not written");

        BaseNode reloadedPlain = roundTrip(new GraphCanvas.GraphSnapshot(
                List.of(new GraphCanvas.ClipboardNode(plain, 0.0, 0.0)), List.of(), List.of())).nodes().get(0).node();
        assertEquals(0, reloadedPlain.getMaxConcurrency());
        assertEquals(0L, reloadedPlain.getTimeoutMillis());
    }

    private static GraphCanvas.GraphSnapshot roundTrip(GraphCanvas.GraphSnapshot snapshot) {
        String text = GraphFileIO.toJson(snapshot).toString();
        return GraphFileIO.fromJson(new JSONObject(new JSONTokener(text)));
    }

    /** A node with one authored and one authored-secret output, for checking secrets don't get serialised. */
    private static final class SecretHolder extends BaseNode {
        final NodeVariable<String> plain = new NodeVariable<>("Plain", String.class, true);
        final NodeVariable<String> secret = new NodeVariable<>("Secret", String.class, true).markSecret();

        @Override
        public void process() {
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
        public void process() {
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
