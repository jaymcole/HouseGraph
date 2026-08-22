package io.github.jaymcole.housegraph.ui.io;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.ui.CameraState;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardDataEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardNode;
import io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot;
import io.github.jaymcole.housegraph.graph.ExecutionPolicy;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.nodes.MissingNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import io.github.jaymcole.housegraph.graph.nodes.loader.SecretLoaderNode;
import io.github.jaymcole.housegraph.graph.nodes.object.ObjectDecomposerNode;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginDirectory;
import javafx.geometry.Point2D;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises GraphFileIO's JSON conversion directly (no GraphCanvas/JavaFX toolkit
 * needed, since ClipboardNode/GraphSnapshot are plain data), including a real
 * toString()/re-parse round trip — the same text path save()/load() actually take.
 */
class GraphFileIOTest {

    /**
     * The app's own node library, which is what these fixtures are built from. Save/load takes a
     * registry rather than reaching for a static one, so a test could equally point this at a
     * fixture package — see {@code NodeRegistryTest}.
     */
    private static final NodeRegistry REGISTRY =
            new NodeRegistry(List.of(NodeRegistry.ScanRoot.core(GraphFileIOTest.class.getClassLoader())));

    private static JSONObject toJson(GraphSnapshot snapshot) {
        return GraphFileIO.toJson(snapshot, REGISTRY);
    }

    private static GraphSnapshot fromJson(JSONObject root) {
        return GraphFileIO.fromJson(root, REGISTRY);
    }

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
    void roundTripsTwoFlowEdgesFanningIntoOnePort() {
        AddNode a = new AddNode();
        AddNode b = new AddNode();
        AddNode target = new AddNode();

        // A flow-in port takes any number of edges, so a save has to keep both rather than
        // collapsing them to the one that happens to be written last.
        GraphSnapshot snapshot = new GraphSnapshot(
                List.of(
                        new ClipboardNode(a, 0.0, 0.0),
                        new ClipboardNode(b, 0.0, 60.0),
                        new ClipboardNode(target, 80.0, 30.0)),
                List.of(),
                List.of(
                        new ClipboardFlowEdge(0, 0, 2, 0, List.of()),
                        new ClipboardFlowEdge(1, 0, 2, 0, List.of())));

        GraphSnapshot roundTripped = roundTrip(snapshot);

        assertEquals(2, roundTripped.flowEdges().size(), "both edges into the shared port survive");
        for (ClipboardFlowEdge edge : roundTripped.flowEdges()) {
            assertEquals(2, edge.targetNodeIndex());
            assertEquals(0, edge.targetPortIndex(), "both still target the same flow-in port");
        }
        assertEquals(List.of(0, 1),
                roundTripped.flowEdges().stream().map(ClipboardFlowEdge::sourceNodeIndex).sorted().toList(),
                "and they still come from the two different upstream nodes");
    }

    @Test
    void unknownNodeTypeLoadsAsAPlaceholderRatherThanFailingTheWholeLoad() {
        JSONObject root = new JSONObject();
        root.put("nodes", List.of(unknownNodeJson(7.0, 9.0)));
        root.put("dataEdges", List.of());
        root.put("flowEdges", List.of());

        GraphSnapshot snapshot = fromJson(root);

        assertEquals(1, snapshot.nodes().size());
        ClipboardNode placeholder = snapshot.nodes().get(0);
        assertTrue(placeholder.node() instanceof MissingNode,
                "an unresolvable type loads as a real placeholder node, not a null slot that a later save would drop");
        assertEquals("com.example.NotARealNode", ((MissingNode) placeholder.node()).missingType());
        assertTrue(placeholder.node().isMisconfigured(), "it must look broken on the canvas, not runnable");
        assertEquals(7.0, placeholder.x());
        assertEquals(9.0, placeholder.y());
    }

    @Test
    void aPlaceholderRefusesToRunRatherThanSilentlyDoingNothing() {
        GraphSnapshot snapshot = fromJson(rootWith(List.of(unknownNodeJson(0.0, 0.0)), List.of(), List.of()));
        BaseNode placeholder = snapshot.nodes().get(0).node();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> placeholder.process(ProcessContext.uncancelled()));
        assertTrue(failure.getMessage().contains("com.example.NotARealNode"),
                "the failure has to name the type so the user knows what to install");
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

        GraphSnapshot snapshot = fromJson(root);

        assertEquals(3, snapshot.nodes().size());
        assertTrue(snapshot.nodes().get(0).node() instanceof ConstantFloatNode);
        assertTrue(snapshot.nodes().get(1).node() instanceof MissingNode, "the unknown node holds index 1");
        assertTrue(snapshot.nodes().get(2).node() instanceof AddNode,
                "the node after the unknown one keeps its original index");
        // The edge's saved indices are untouched, and index 2 still lands on the AddNode.
        assertEquals(2, snapshot.dataEdges().get(0).targetNodeIndex());
    }

    // --- The data-loss regression: an uninstalled node type must survive a load/save round trip ---

    @Test
    void anUninstalledNodeSurvivesALoadSaveRoundTripByteForByte() {
        // THE regression guard for this whole format version. Before it, an unresolvable node loaded
        // as a null slot that never reached the canvas and was never written back, so opening a graph
        // without its library and pressing Quick Save destroyed the node permanently.
        JSONObject original = unknownNodeJson(7.0, 9.0);
        original.put("plugin", "housegraph-widgets");
        original.put("executionPolicy", "PARALLEL");
        original.put("maxConcurrency", 4);
        original.put("timeoutMillis", 5000);
        original.put("state", new JSONObject(Map.of("running", "true")));
        original.put("requiredInputs", List.of("Token"));
        original.put("inputs", List.of(namedValue("Token", "abc")));
        original.put("outputs", List.of(namedValue("Result", "xyz")));
        // A key this build knows nothing about, standing in for whatever a future format adds.
        original.put("somethingThisBuildDoesNotUnderstand", "keep me");

        JSONObject root = rootWith(List.of(original), List.of(), List.of());
        root.put("plugins", List.of(new JSONObject()
                .put("id", "housegraph-widgets")
                .put("name", "Widgets")
                .put("version", "1.2.3")
                .put("repository", "https://github.com/example/housegraph-widgets")));

        JSONObject rewritten = toJson(fromJson(root));

        JSONObject node = rewritten.getJSONArray("nodes").getJSONObject(0);
        assertTrue(original.similar(node), "the node's JSON must come back out exactly as it went in");

        JSONObject pluginRow = rewritten.getJSONArray("plugins").getJSONObject(0);
        assertEquals("https://github.com/example/housegraph-widgets", pluginRow.getString("repository"),
                "the repository is the only record of where the missing library comes from; losing it "
                        + "would leave the user unable to repair the graph");
        assertEquals("1.2.3", pluginRow.getString("version"));
    }

    @Test
    void movingAPlaceholderOnCanvasUpdatesOnlyItsCoordinates() {
        JSONObject original = unknownNodeJson(7.0, 9.0);
        GraphSnapshot loaded = fromJson(rootWith(List.of(original), List.of(), List.of()));

        // Same node, dragged elsewhere on the canvas.
        ClipboardNode moved = new ClipboardNode(loaded.nodes().get(0).node(), 300.0, 400.0);
        JSONObject node = toJson(new GraphSnapshot(List.of(moved), List.of(), List.of()))
                .getJSONArray("nodes").getJSONObject(0);

        assertEquals(300.0, node.getDouble("x"));
        assertEquals(400.0, node.getDouble("y"));
        assertEquals("com.example.NotARealNode", node.getString("type"), "everything else is still verbatim");
    }

    @Test
    void edgesIntoAndOutOfAnUninstalledNodeSurviveARoundTrip() {
        // Data ports come back from the saved value arrays; flow ports exist only as edge endpoints,
        // so they have to be back-filled from the edge lists or every flow edge here would be dropped.
        JSONObject missing = unknownNodeJson(0.0, 0.0);
        missing.put("inputs", List.of(namedValue("In", "v")));
        missing.put("outputs", List.of(namedValue("Out", "v")));

        // The source endpoint is positional so this test turns only on the placeholder's own
        // endpoint resolving, not on what a real node happens to call its ports.
        JSONObject dataEdge = new JSONObject()
                .put("sourceNode", 0).put("sourceVariable", 0)
                .put("targetNode", 1).put("targetVariable", "In");
        JSONObject flowEdge = new JSONObject()
                .put("sourceNode", 1).put("sourcePort", "Then")
                .put("targetNode", 2).put("targetPort", 0);

        JSONObject root = rootWith(
                List.of(realNodeJson(ConstantFloatNode.class), missing, realNodeJson(AddNode.class)),
                List.of(dataEdge), List.of(flowEdge));

        GraphSnapshot snapshot = fromJson(root);

        assertEquals(1, snapshot.dataEdges().size(), "a named data endpoint resolves onto the placeholder");
        assertEquals(1, snapshot.dataEdges().get(0).targetNodeIndex(), "and still points at the placeholder");
        assertEquals(1, snapshot.flowEdges().size(), "a named flow endpoint is back-filled onto the placeholder");
        assertEquals(1, snapshot.flowEdges().get(0).sourceNodeIndex());

        // ...and they are still there after writing back out and reading again.
        GraphSnapshot again = fromJson(toJson(snapshot));
        assertEquals(1, again.dataEdges().size());
        assertEquals(1, again.flowEdges().size());
    }

    // --- Format version 2 -----------------------------------------------------------------------

    @Test
    void aCoreOnlyGraphWritesNoPluginKeysAtAll() {
        JSONObject json = toJson(new GraphSnapshot(
                List.of(new ClipboardNode(new AddNode(), 0.0, 0.0)), List.of(), List.of()));

        assertEquals(2, json.getInt("version"));
        assertFalse(json.has("plugins"), "no plugins table when nothing outside core is used");
        assertFalse(json.getJSONArray("nodes").getJSONObject(0).has("plugin"),
                "a built-in node carries no plugin key, so a core-only v2 file differs from v1 only in its version");
    }

    @Test
    void aVersionOneFileWithNoPluginInformationStillLoads() {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("nodes", List.of(realNodeJson(AddNode.class)));
        root.put("dataEdges", List.of());
        root.put("flowEdges", List.of());

        GraphSnapshot snapshot = fromJson(root);

        assertEquals(1, snapshot.nodes().size());
        assertTrue(snapshot.nodes().get(0).node() instanceof AddNode);
    }

    private static JSONObject rootWith(List<JSONObject> nodes, List<JSONObject> dataEdges, List<JSONObject> flowEdges) {
        JSONObject root = new JSONObject();
        root.put("version", GraphFileIO.CURRENT_VERSION);
        root.put("nodes", nodes);
        root.put("dataEdges", dataEdges);
        root.put("flowEdges", flowEdges);
        return root;
    }

    private static JSONObject namedValue(String name, Object value) {
        return new JSONObject().put("name", name).put("value", value);
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

        JSONObject json = toJson(new GraphSnapshot(
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

        JSONObject json = toJson(new GraphSnapshot(
                List.of(new ClipboardNode(node, 0.0, 0.0)), List.of(), List.of()));

        JSONArray outputs = json.getJSONArray("nodes").getJSONObject(0).getJSONArray("outputs");
        assertTrue(entryByName(outputs, "Value").isNull("value"), "a computed value is not written to disk");
        assertFalse(json.toString().toLowerCase().contains("infinity"), "no non-finite number reaches the file");
    }

    @Test
    void aValueTheNodeSeededForItselfSurvivesALoadThatSavedItAsNull() {
        // The load-side half of the persistence gate. A transient/computed/secret variable is always
        // saved as null (only its name is kept), so applying that null back can only destroy what the
        // node seeded at construction — and nothing re-seeds it, so the variable reads null for the
        // life of the node. This is exactly how a resource node's handle output (a Discord bot, a
        // store) came back dead from a loaded graph while a freshly added one worked.
        JSONObject nodeJson = realNodeJson(SeededHandleHolder.class);
        nodeJson.put("outputs", List.of(namedValue("Handle", JSONObject.NULL)));

        GraphSnapshot snapshot = fromJson(rootWith(List.of(nodeJson), List.of(), List.of()));

        BaseNode loaded = snapshot.nodes().get(0).node();
        assertTrue(loaded instanceof SeededHandleHolder, "the fixture type must resolve, not load as a placeholder");
        assertNotNull(loaded.getOutputs().get(0).getValue(),
                "a transient value the node seeded for itself must survive a load that couldn't persist it");
    }

    @Test
    void anAuthoredValueTheUserClearedStillReloadsCleared() {
        // The companion guard: the load-side skip keys on the variable being non-persistent, not on
        // the saved value being null. A persistent input the user emptied must stay empty rather than
        // springing back to whatever default the node's constructor set — which is what a blanket
        // "never apply a null" rule would have done.
        assertEquals("author default", new DefaultedInputHolder().text.getValue(),
                "the fixture only tests anything if its constructor really does seed a default");
        JSONObject nodeJson = realNodeJson(DefaultedInputHolder.class);
        nodeJson.put("inputs", List.of(namedValue("Text", JSONObject.NULL)));

        GraphSnapshot snapshot = fromJson(rootWith(List.of(nodeJson), List.of(), List.of()));

        assertNull(snapshot.nodes().get(0).node().getInputs().get(0).getValue(),
                "a cleared authored value must stay cleared, not spring back to its author default");
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

        GraphSnapshot snapshot = fromJson(root);

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

        GraphSnapshot legacy = fromJson(legacyRoot);
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
        JSONObject json = toJson(new GraphSnapshot(
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
        JSONObject json = toJson(new GraphSnapshot(
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

        GraphSnapshot loaded = fromJson(root);
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

        GraphSnapshot loaded = fromJson(root);
        assertTrue(loaded.nodes().get(0).node().getInputs().get(0).isRequired(),
                "a missing requiredInputs key must leave the author default (required) intact");
    }

    // --- Name-keyed port identity (resilience to reorder) and its back-compat ------------------

    @Test
    void valuesAndEdgesBindByNameRegardlessOfSavedOrder() {
        // A save whose inputs are listed in the opposite order to the node's actual port order, with
        // a data edge pointing at "V2" by name. Name-keying must bind each value and the edge to the
        // right port regardless of position - the whole point of the format. (A purely positional
        // format would put V2's value on V1 and wire the edge to the wrong input.) The fixture's
        // inputs are manually editable because only such values are persisted at all: a file
        // carrying a value for a wire-only input (AddNode's V1/V2) isn't one this build could write,
        // and load ignores those entries rather than clobbering what the node set up for itself.
        JSONObject constant = nodeJson(ConstantFloatNode.class,
                new JSONArray(),
                new JSONArray(List.of(valueEntry("out", 5.0))));
        JSONObject add = nodeJson(EditableInputsHolder.class,
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

        GraphSnapshot loaded = fromJson(root);

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
        JSONObject json = toJson(new GraphSnapshot(
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

        GraphSnapshot loaded = fromJson(root);
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

        GraphSnapshot loaded = fromJson(root);
        assertEquals(5f, loaded.nodes().get(0).node().getOutputs().get(0).getValue(), "a legacy scalar value loads positionally");
        ClipboardDataEdge dataEdge = loaded.dataEdges().get(0);
        assertEquals(0, dataEdge.sourceVariableIndex());
        assertEquals(0, dataEdge.targetVariableIndex());
    }

    // --- Camera state (pan/zoom) -----------------------------------------------------------------

    @Test
    void cameraStateRoundTripsThroughSaveAndLoad() {
        JSONObject json = GraphFileIO.toJson(new GraphSnapshot(List.of(), List.of(), List.of()),
                REGISTRY, PluginDirectory.EMPTY, new CameraState(2.5, 120.0, -40.0));

        CameraState restored = GraphFileIO.cameraFromJson(json);

        assertEquals(2.5, restored.zoom());
        assertEquals(120.0, restored.translateX());
        assertEquals(-40.0, restored.translateY());
    }

    @Test
    void aFileWithNoCameraKeyRestoresTheDefaultCamera() {
        // A save written before camera state existed has no "camera" key at all.
        JSONObject root = rootWith(List.of(), List.of(), List.of());

        assertEquals(CameraState.DEFAULT, GraphFileIO.cameraFromJson(root));
    }

    // --- Stable type identity and format version -----------------------------------------------

    @Test
    void nodeTypeIsWrittenAsItsStableIdAndTheRootIsVersioned() {
        JSONObject json = toJson(new GraphSnapshot(
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

        GraphSnapshot loaded = fromJson(root);
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

        GraphSnapshot loaded = fromJson(root);
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
        String text = toJson(snapshot).toString();
        return fromJson(new JSONObject(new JSONTokener(text)));
    }

    // --- The plugins table: rows have to carry where the library came from ---------------------
    //
    // These use PLUGIN_REGISTRY rather than REGISTRY. REGISTRY is core-only, so under it every node
    // is a built-in and no plugin row is ever written — which is precisely why the missing
    // repository/version/name went unnoticed: nothing here had ever saved a plugin-owned node.

    /**
     * A registry that claims the math package for an out-of-tree library, so {@code AddNode} reads
     * as plugin-owned. Cheaper and clearer than a fixture package: the point under test is what
     * {@code toJson} writes for a non-core owner, not discovery itself.
     */
    private static final NodeRegistry PLUGIN_REGISTRY = new NodeRegistry(List.of(
            new NodeRegistry.ScanRoot("io.github.jaymcole.housegraph.graph.nodes.math",
                    GraphFileIOTest.class.getClassLoader(), "housegraph-widgets", "Widgets", null)));

    private static final PluginCatalog.Installed WIDGETS = new PluginCatalog.Installed(
            "housegraph-widgets", "Widgets", "1.2.3",
            "https://github.com/example/housegraph-widgets", "0.2.0",
            List.of("io.github.jaymcole.housegraph.graph.nodes.math"), "Widgets", "abc123", true);

    private static JSONObject pluginsTableFor(PluginDirectory directory) {
        GraphSnapshot snapshot = new GraphSnapshot(
                List.of(new ClipboardNode(new AddNode(), 0.0, 0.0)), List.of(), List.of());
        return GraphFileIO.toJson(snapshot, PLUGIN_REGISTRY, directory)
                .getJSONArray("plugins").getJSONObject(0);
    }

    @Test
    void aPluginRowRecordsWhereTheLibraryCanBeInstalledFrom() {
        JSONObject row = pluginsTableFor(id -> Optional.of(WIDGETS));

        // The whole reason the table exists. Without this a graph opened on another machine can be
        // told a library is missing but not where to get it, so GraphDependencyCheck's
        // RequiredPlugin.isInstallable() is false and no install can ever be offered.
        assertEquals("https://github.com/example/housegraph-widgets", row.getString("repository"));
        assertEquals("housegraph-widgets", row.getString("id"));
        assertEquals("Widgets", row.getString("name"));
        assertEquals("1.2.3", row.getString("version"));
    }

    @Test
    void aPluginRowDegradesToABareIdWhenTheLibraryIsNotInTheDirectory() {
        // A library uninstalled since the graph was built, or a save made with no catalog to hand.
        // Writing the id alone is the old behaviour and stays correct; writing JSON nulls would not.
        JSONObject row = pluginsTableFor(PluginDirectory.EMPTY);

        assertEquals("housegraph-widgets", row.getString("id"));
        assertFalse(row.has("repository"), "an unknown library must not produce a null repository");
        assertFalse(row.has("version"));
        assertFalse(row.has("name"));
    }

    @Test
    void aBuiltInNodeStillWritesNoPluginsTableAtAll() {
        // The core-only case has to keep producing a file that differs from its v1 form by exactly
        // the version number, so adding library metadata can't churn every existing save.
        GraphSnapshot snapshot = new GraphSnapshot(
                List.of(new ClipboardNode(new AddNode(), 0.0, 0.0)), List.of(), List.of());

        JSONObject root = GraphFileIO.toJson(snapshot, REGISTRY, id -> Optional.of(WIDGETS));

        assertFalse(root.has("plugins"));
        assertFalse(root.getJSONArray("nodes").getJSONObject(0).has("plugin"));
    }

    @Test
    void aPreservedRowFromTheFileBeatsOneRegeneratedFromTheCatalog() {
        // A MissingNode's row came from a file that may know more than this build does — a version
        // that is not installed here, or a key a future format added. Regenerating it from the local
        // catalog would quietly rewrite that to whatever happens to be installed.
        JSONObject original = unknownNodeJson(1.0, 2.0);
        original.put("plugin", "housegraph-widgets");
        JSONObject root = rootWith(List.of(original), List.of(), List.of());
        root.put("plugins", List.of(new JSONObject()
                .put("id", "housegraph-widgets")
                .put("version", "9.9.9")
                .put("repository", "https://github.com/example/somewhere-else")));

        JSONObject rewritten = GraphFileIO.toJson(
                GraphFileIO.fromJson(root, PLUGIN_REGISTRY), PLUGIN_REGISTRY, id -> Optional.of(WIDGETS));

        JSONObject row = rewritten.getJSONArray("plugins").getJSONObject(0);
        assertEquals("9.9.9", row.getString("version"), "the file's own row wins over the catalog's");
        assertEquals("https://github.com/example/somewhere-else", row.getString("repository"));
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

    /**
     * A node whose transient output carries a handle it seeds for itself at construction — the shape
     * of every resource node (a Discord bot, a document store). Public so the loader can reflectively
     * instantiate it by class name.
     */
    public static final class SeededHandleHolder extends BaseNode {
        final NodeVariable<Object> handle = new NodeVariable<>("Handle", Object.class).transientValue();

        public SeededHandleHolder() {
            handle.setValue(new Object());
        }

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(handle);
        }
    }

    /**
     * A two-input node whose inputs can be typed in, so their values are actually persisted — the
     * shape the name-vs-position binding test needs. Public for the same reason as
     * {@link SeededHandleHolder}.
     */
    public static final class EditableInputsHolder extends BaseNode {
        final NodeVariable<Float> v1 = new NodeVariable<>("V1", Float.class, true);
        final NodeVariable<Float> v2 = new NodeVariable<>("V2", Float.class, true);
        final NodeVariable<Float> sum = new NodeVariable<>("Sum", Float.class);

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
            addInput(v1);
            addInput(v2);
        }

        @Override
        public void configureOutputs() {
            addOutput(sum);
        }
    }

    /**
     * A node with one manually-editable input carrying an author default — for checking that a value
     * the user cleared reloads cleared rather than reverting. Public for the same reason as
     * {@link SeededHandleHolder}.
     */
    public static final class DefaultedInputHolder extends BaseNode {
        final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true);

        public DefaultedInputHolder() {
            text.setValue("author default");
        }

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
            addInput(text);
        }

        @Override
        public void configureOutputs() {
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
