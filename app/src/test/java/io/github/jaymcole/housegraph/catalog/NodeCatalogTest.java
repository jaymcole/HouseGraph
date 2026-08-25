package io.github.jaymcole.housegraph.catalog;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.plugin.PluginDirectory;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the catalog against the app's own real node library — the same fixture-free approach
 * {@code GraphFileIOTest} uses — rather than a synthetic registry, since the whole point is
 * describing nodes exactly as they really are.
 */
class NodeCatalogTest {

    private static final NodeRegistry REGISTRY =
            new NodeRegistry(List.of(NodeRegistry.ScanRoot.core(NodeCatalogTest.class.getClassLoader())));

    private static NodeCatalog.Entry entryFor(String type) {
        return NodeCatalog.discover(REGISTRY, PluginDirectory.EMPTY).stream()
                .filter(entry -> entry.type().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No catalog entry for " + type));
    }

    @Test
    void describesEveryCoreNodeWithNoInstantiationErrors() {
        List<NodeCatalog.Entry> entries = NodeCatalog.discover(REGISTRY, PluginDirectory.EMPTY);

        assertFalse(entries.isEmpty());
        entries.forEach(entry -> assertEquals(null, entry.error(),
                entry.type() + " failed to describe: " + entry.error()));
    }

    @Test
    void reportsATypedPortShapeMatchingTheRealClass() {
        NodeCatalog.Entry add = entryFor("AddNode");

        assertEquals("core", add.pluginId());
        assertEquals("DATA", add.kind());
        assertEquals(List.of("V1", "V2"), add.inputs().stream().map(NodeCatalog.Port::name).toList());
        assertEquals("java.lang.Float", add.inputs().get(0).type());
        assertEquals(List.of("Sum"), add.outputs().stream().map(NodeCatalog.Port::name).toList());
        assertEquals(1, add.flowInputs().size());
        assertEquals(1, add.flowOutputs().size());
        assertTrue(add.signature().matches("^[0-9a-f]{16}$"));
    }

    @Test
    void aManuallyEditableOutputIsFlaggedAsSuch() {
        // ConstantFloatNode's single output is the value the user types in, not something
        // computed — the catalog's manuallyEditable flag is what tells a harness which ports it
        // can safely set before running the graph.
        NodeCatalog.Entry constant = entryFor("ConstantFloatNode");

        assertTrue(constant.outputs().get(0).manuallyEditable());
    }

    @Test
    void toJsonRoundTripsThroughOrgJson() {
        List<NodeCatalog.Entry> entries = NodeCatalog.discover(REGISTRY, PluginDirectory.EMPTY);

        JSONObject root = new JSONObject(NodeCatalog.toJson(entries).toString());

        assertEquals(NodeCatalog.CATALOG_VERSION, root.getInt("catalogVersion"));
        assertEquals(entries.size(), root.getJSONArray("nodes").length());
    }

    @Test
    void aLibraryIdResolvesThroughTheGivenPluginDirectory() {
        PluginDirectory unknown = id -> Optional.empty();

        NodeCatalog.Entry add = NodeCatalog.discover(REGISTRY, unknown).stream()
                .filter(entry -> entry.type().equals("AddNode"))
                .findFirst()
                .orElseThrow();

        // A core node is never looked up in the plugin directory at all.
        assertEquals(NodeRegistry.CORE_PLUGIN_ID, add.pluginId());
        assertEquals(null, add.pluginVersion());
    }
}
