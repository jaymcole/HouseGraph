package io.github.jaymcole.housegraph.saveformat;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleNode;
import io.github.jaymcole.housegraph.modules.ModuleDirectory;
import io.github.jaymcole.housegraph.modules.ModuleEntry;
import io.github.jaymcole.housegraph.modules.ModuleInterface;
import io.github.jaymcole.housegraph.modules.ModulePort;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.plugin.PluginDirectory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save format version 3: the root {@code modules} table, the per-node {@code module} key, and what
 * has to stay true of a file that references no module at all.
 */
class GraphFileIOModulesTest {

    private static final NodeRegistry REGISTRY =
            new NodeRegistry(List.of(NodeRegistry.ScanRoot.core(GraphFileIOModulesTest.class.getClassLoader())));

    private static final ModuleInterface DOORBELL = new ModuleInterface(List.of(
            new ModulePort("Temperature", ModulePort.Kind.DATA, ModulePort.Direction.IN, Float.class.getName()),
            new ModulePort("Verdict", ModulePort.Kind.DATA, ModulePort.Direction.OUT, Boolean.class.getName()),
            new ModulePort("Start", ModulePort.Kind.FLOW, ModulePort.Direction.IN, ""),
            new ModulePort("Done", ModulePort.Kind.FLOW, ModulePort.Direction.OUT, "")), List.of());

    private static final GraphDependencyCheck.RequiredPlugin DISCORD = new GraphDependencyCheck.RequiredPlugin(
            "housegraph-discord", "Discord", "0.3.1", "https://github.com/jaymcole/housegraph-discord");

    // --- The table ------------------------------------------------------------------------------

    @Test
    void aReferencedModuleGetsARowAndTheNodeNamesIt() {
        JSONObject root = write(moduleNode("m-1"), directoryOf(List.of()));

        assertEquals(3, root.getInt("version"));
        JSONArray modules = root.getJSONArray("modules");
        assertEquals(1, modules.length());
        assertEquals("m-1", modules.getJSONObject(0).getString("id"));
        assertEquals("Doorbell", modules.getJSONObject(0).getString("name"));
        assertEquals("m-1", root.getJSONArray("nodes").getJSONObject(0).getString("module"));
    }

    @Test
    void twoNodesOnTheSameModuleShareOneRow() {
        JSONObject root = write(new GraphSnapshot(List.of(
                new ClipboardNode(resolvedNode("m-1"), 0.0, 0.0),
                new ClipboardNode(resolvedNode("m-1"), 50.0, 0.0)),
                List.of(), List.of()), directoryOf(List.of()));

        assertEquals(1, root.getJSONArray("modules").length());
    }

    @Test
    void theWholeShapeAndTheReferenceSurviveAFullRoundTrip() {
        JSONObject root = write(moduleNode("m-1"), directoryOf(List.of()));

        GraphSnapshot back = GraphFileIO.fromJson(new JSONObject(root.toString()), REGISTRY);
        ModuleNode loaded = (ModuleNode) back.nodes().get(0).node();

        assertEquals("m-1", loaded.getModuleId());
        assertEquals(DOORBELL.ports(), loaded.getModulePorts());
        assertEquals(List.of("Temperature"), loaded.getInputs().stream().map(v -> v.name).toList());
        assertEquals(List.of("Done"), loaded.getFlowOutputs().stream().map(p -> p.name).toList());
    }

    // --- Nothing changes for a graph with no modules ---------------------------------------------

    @Test
    void aModulesFreeGraphDiffersFromItsV2FormOnlyByTheVersionNumber() {
        JSONObject v3 = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(new AddNode(), 10.0, 20.0)), List.of(), List.of()), REGISTRY);

        JSONObject asV2 = new JSONObject(v3.toString());
        asV2.put("version", 2);

        assertEquals(2, asV2.getInt("version"));
        assertEquals(3, v3.getInt("version"));
        // toString() is a pure function of the put sequence and the key set, so changing only the
        // version's value has to leave the rest of the text identical.
        assertEquals(asV2.toString(2).replace("\"version\": 2", "\"version\": 3"), v3.toString(2));
        assertFalse(v3.has("modules"));
    }

    @Test
    void aV2FileWithNoModulesTableLoadsUnchanged() {
        JSONObject v2 = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(new AddNode(), 10.0, 20.0)), List.of(), List.of()), REGISTRY);
        v2.put("version", 2);

        GraphSnapshot loaded = GraphFileIO.fromJson(v2, REGISTRY);

        assertEquals(1, loaded.nodes().size());
        assertTrue(loaded.nodes().get(0).node() instanceof AddNode);
        assertEquals(10.0, loaded.nodes().get(0).x());
    }

    @Test
    void writingTheSameGraphTwiceProducesTheSameText() {
        assertEquals(write(moduleNode("m-1"), directoryOf(List.of(DISCORD))).toString(2),
                write(moduleNode("m-1"), directoryOf(List.of(DISCORD))).toString(2));
    }

    // --- Transitive library requirements ---------------------------------------------------------

    @Test
    void aModulesOwnLibrariesAreRecordedInTheConsumersFile() {
        JSONObject root = write(moduleNode("m-1"), directoryOf(List.of(DISCORD)));

        assertFalse(root.has("plugins"),
                "the consumer's own canvas holds no plugin node, so its own table stays empty");
        JSONArray required = root.getJSONArray("modules").getJSONObject(0).getJSONArray("plugins");
        assertEquals("housegraph-discord", required.getJSONObject(0).getString("id"));
        assertEquals("https://github.com/jaymcole/housegraph-discord",
                required.getJSONObject(0).getString("repository"),
                "the repository is what turns 'a library is missing' into an install offer");
    }

    @Test
    void theDependencyCheckSeesAModulesLibrariesWithoutOpeningAnything() {
        JSONObject root = write(moduleNode("m-1"), directoryOf(List.of(DISCORD)));

        List<GraphDependencyCheck.RequiredPlugin> required = GraphDependencyCheck.requiredBy(root);

        assertEquals(List.of("housegraph-discord"), required.stream()
                .map(GraphDependencyCheck.RequiredPlugin::id).toList());
    }

    // --- An unresolvable module ------------------------------------------------------------------

    @Test
    void aModuleThatCannotBeResolvedStillWritesEverythingItCarried() {
        JSONObject saved = write(moduleNode("m-1"), directoryOf(List.of(DISCORD)));

        // Reopen and re-save on a machine that has never seen the module file.
        GraphSnapshot reopened = GraphFileIO.fromJson(new JSONObject(saved.toString()), REGISTRY);
        JSONObject rewritten = GraphFileIO.toJson(reopened, REGISTRY, PluginDirectory.EMPTY,
                ModuleDirectory.EMPTY, CameraState.DEFAULT);

        assertEquals(saved.toString(2), rewritten.toString(2),
                "a re-save without the module file must lose nothing — not the ports, not the "
                        + "reference, and not the libraries the module needs");
    }

    @Test
    void anUnresolvableModuleNodeKeepsItsPortsAndItsEdges() {
        ModuleNode node = new ModuleNode();
        node.setModuleId("m-1");
        node.adopt(DOORBELL);
        AddNode add = new AddNode();

        JSONObject saved = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(node, 0.0, 0.0), new ClipboardNode(add, 100.0, 0.0)),
                // Verdict (Boolean out) into the add node would not type-check; wire the module's
                // flow output instead, which is what an exit port is for.
                List.of(),
                List.of(new ClipboardFlowEdge(0, 0, 1, 0, List.of()))),
                REGISTRY, PluginDirectory.EMPTY, ModuleDirectory.EMPTY, CameraState.DEFAULT);

        GraphSnapshot loaded = GraphFileIO.fromJson(new JSONObject(saved.toString()), REGISTRY);
        ModuleNode reloaded = (ModuleNode) loaded.nodes().get(0).node();

        assertEquals(ModuleNode.Resolution.UNCHECKED, reloaded.getResolution());
        assertTrue(reloaded.isMisconfigured());
        assertEquals(DOORBELL.ports(), reloaded.getModulePorts());
        assertEquals(1, loaded.flowEdges().size(), "the edge binds by name against a rebuilt port");
        assertEquals(0, loaded.flowEdges().get(0).sourcePortIndex());
    }

    @Test
    void aRowWithNoDirectoryToHandIsRebuiltFromWhatTheNodeItselfRemembers() {
        ModuleNode node = new ModuleNode();
        node.setModuleId("m-1");
        node.adopt(DOORBELL);

        JSONObject root = GraphFileIO.toJson(new GraphSnapshot(
                List.of(new ClipboardNode(node, 0.0, 0.0)), List.of(), List.of()),
                REGISTRY, PluginDirectory.EMPTY, ModuleDirectory.EMPTY, CameraState.DEFAULT);

        JSONObject row = root.getJSONArray("modules").getJSONObject(0);
        assertEquals("m-1", row.getString("id"));
        assertFalse(row.has("plugins"), "nothing knows what the module needs, so nothing is claimed");
        assertNotNull(GraphFileIO.fromJson(root, REGISTRY));
    }

    // --- The schema and the writer stay in step ---------------------------------------------------

    @Test
    void theBundledSchemaDescribesExactlyWhatThisBuildWrites() throws Exception {
        JSONObject schema = new JSONObject(new String(
                GraphFileIO.class.getResourceAsStream("/schema/graph-save.v3.schema.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(SaveFileFixture.currentVersion(),
                schema.getJSONObject("properties").getJSONObject("version").getInt("const"),
                "the schema names a version this build does not write");

        // A schema that drifts from what GraphFileIO writes is worse than no schema at all, and the
        // root is where it drifts silently: additionalProperties is false there, so a key added to
        // the writer and not to the schema turns every new file invalid.
        JSONObject root = write(moduleNode("m-1"), directoryOf(List.of(DISCORD)));
        for (String key : root.keySet()) {
            assertTrue(schema.getJSONObject("properties").has(key),
                    "the v3 schema does not describe the root key \"" + key + "\"");
        }
        JSONObject moduleRowSchema = schema.getJSONObject("$defs").getJSONObject("moduleRow");
        for (String key : root.getJSONArray("modules").getJSONObject(0).keySet()) {
            assertTrue(moduleRowSchema.getJSONObject("properties").has(key),
                    "the v3 schema does not describe the modules[] key \"" + key + "\"");
        }
        JSONObject nodeSchema = schema.getJSONObject("$defs").getJSONObject("node");
        for (String key : root.getJSONArray("nodes").getJSONObject(0).keySet()) {
            assertTrue(nodeSchema.getJSONObject("properties").has(key),
                    "the v3 schema does not describe the node key \"" + key + "\"");
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------

    /** A one-node graph holding a module node already resolved against {@link #directoryOf}. */
    private static GraphSnapshot moduleNode(String id) {
        return new GraphSnapshot(List.of(new ClipboardNode(resolvedNode(id), 0.0, 0.0)), List.of(), List.of());
    }

    private static ModuleNode resolvedNode(String id) {
        ModuleNode node = new ModuleNode();
        node.setModuleId(id);
        node.bindTo(directoryOf(List.of(DISCORD)));
        return node;
    }

    private static JSONObject write(GraphSnapshot snapshot, ModuleDirectory modules) {
        return GraphFileIO.toJson(snapshot, REGISTRY, PluginDirectory.EMPTY, modules, CameraState.DEFAULT);
    }

    /** A directory that resolves "m-1" to the doorbell module, needing {@code plugins}. */
    private static ModuleDirectory directoryOf(List<GraphDependencyCheck.RequiredPlugin> plugins) {
        return id -> "m-1".equals(id)
                ? Optional.of(new ModuleEntry("m-1", "Doorbell", "/modules/doorbell.json", DOORBELL, plugins))
                : Optional.empty();
    }
}
