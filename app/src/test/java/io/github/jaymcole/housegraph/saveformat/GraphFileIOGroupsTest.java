package io.github.jaymcole.housegraph.saveformat;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code groups} table: what a save writes, what a load forgives, and that the schema still matches. */
class GraphFileIOGroupsTest {

    private static final NodeRegistry REGISTRY =
            new NodeRegistry(List.of(NodeRegistry.ScanRoot.core(GraphFileIOGroupsTest.class.getClassLoader())));

    private static final NodeGroup KITCHEN = new NodeGroup("Kitchen", 10.5, -20.0, 400.0, 300.0, "#98c379");
    private static final NodeGroup PORCH = new NodeGroup("Porch", 500.0, 0.0, 200.0, 150.0, "#e06c75");

    private static GraphSnapshot snapshotWith(NodeGroup... groups) {
        return new GraphSnapshot(List.of(new ClipboardNode(new AddNode(), 0.0, 0.0)),
                List.of(), List.of(), List.of(groups));
    }

    private static JSONObject write(GraphSnapshot snapshot) {
        return GraphFileIO.toJson(snapshot, REGISTRY);
    }

    // --- Round trip ------------------------------------------------------------------------------

    @Test
    void everyFieldOfAFrameSurvivesARoundTrip() {
        // Through the text, not just the object: that is the path a real save and open take.
        JSONObject root = new JSONObject(write(snapshotWith(KITCHEN, PORCH)).toString());

        assertEquals(List.of(KITCHEN, PORCH), GraphFileIO.fromJson(root, REGISTRY).groups());
    }

    @Test
    void frameOrderIsPreservedSoAnUnchangedCanvasReSavesIdentically() {
        JSONObject once = write(snapshotWith(PORCH, KITCHEN));
        JSONObject twice = write(GraphFileIO.fromJson(new JSONObject(once.toString()), REGISTRY));

        assertEquals(once.toString(2), twice.toString(2));
    }

    @Test
    void aCanvasWithNoFrameWritesNoTable() {
        assertFalse(write(snapshotWith()).has("groups"),
                "a graph nobody has framed must produce a file that differs from its v3 form"
                        + " by exactly the version number");
    }

    // --- Forgiving reads -------------------------------------------------------------------------

    @Test
    void aFileWithNoGroupsTableLoadsAsAGraphWithNoFrames() {
        JSONObject root = write(snapshotWith());
        root.remove("groups");

        assertEquals(List.of(), GraphFileIO.fromJson(root, REGISTRY).groups());
    }

    @Test
    void aHandWrittenFrameNamingOnlyItsRectangleLoads() {
        JSONObject root = write(snapshotWith());
        root.put("groups", new JSONArray().put(new JSONObject()
                .put("x", 0).put("y", 0).put("width", 400).put("height", 300)));

        NodeGroup loaded = GraphFileIO.fromJson(root, REGISTRY).groups().get(0);

        assertEquals("", loaded.title());
        assertEquals(NodeGroup.DEFAULT_COLOR, loaded.color());
    }

    @Test
    void aFrameSavedSmallerThanTheMinimumLoadsGrabbableRatherThanUnusable() {
        JSONObject root = write(snapshotWith());
        root.put("groups", new JSONArray().put(new JSONObject()
                .put("x", 0).put("y", 0).put("width", 1).put("height", 1)));

        NodeGroup loaded = GraphFileIO.fromJson(root, REGISTRY).groups().get(0);

        assertEquals(NodeGroup.MIN_WIDTH, loaded.width());
        assertEquals(NodeGroup.MIN_HEIGHT, loaded.height());
    }

    // --- The frames are inert ---------------------------------------------------------------------

    @Test
    void framesDoNotDisturbTheNodesAndEdgesAroundThem() {
        GraphSnapshot loaded = GraphFileIO.fromJson(
                new JSONObject(write(snapshotWith(KITCHEN)).toString()), REGISTRY);

        assertEquals(1, loaded.nodes().size());
        assertEquals(List.of(), loaded.dataEdges());
        assertEquals(List.of(), loaded.flowEdges());
    }

    // --- The schema and the writer stay in step ---------------------------------------------------

    @Test
    void theBundledSchemaDescribesEveryKeyAFrameWrites() throws Exception {
        JSONObject schema = new JSONObject(new String(
                GraphFileIO.class.getResourceAsStream("/schema/graph-save.v4.schema.json").readAllBytes(),
                StandardCharsets.UTF_8));

        JSONObject root = write(snapshotWith(KITCHEN));
        assertTrue(schema.getJSONObject("properties").has("groups"),
                "the schema does not describe the root groups table");

        JSONObject groupSchema = schema.getJSONObject("$defs").getJSONObject("group");
        for (String key : root.getJSONArray("groups").getJSONObject(0).keySet()) {
            assertTrue(groupSchema.getJSONObject("properties").has(key),
                    "the schema does not describe the groups[] key \"" + key + "\"");
        }
    }
}
