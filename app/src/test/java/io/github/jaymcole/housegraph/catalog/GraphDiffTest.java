package io.github.jaymcole.housegraph.catalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDiffTest {

    private static JSONObject node(String type, double x, double y) {
        return new JSONObject().put("type", type).put("x", x).put("y", y);
    }

    private static JSONObject dataEdge(int sourceNode, Object sourceVariable, int targetNode, Object targetVariable) {
        return new JSONObject()
                .put("sourceNode", sourceNode).put("sourceVariable", sourceVariable)
                .put("targetNode", targetNode).put("targetVariable", targetVariable);
    }

    private static JSONObject root(JSONArray nodes, JSONArray dataEdges) {
        return new JSONObject().put("version", 2).put("nodes", nodes)
                .put("dataEdges", dataEdges).put("flowEdges", new JSONArray());
    }

    @Test
    void identicalRootsReportNoChange() {
        JSONObject root = root(new JSONArray(List.of(node("AddNode", 0, 0))), new JSONArray());

        GraphDiff.Report report = GraphDiff.compare(root, new JSONObject(root.toString()));

        assertTrue(report.isUnchanged());
    }

    @Test
    void reportsAScalarFieldChangeByPointer() {
        JSONObject current = root(new JSONArray(List.of(node("AddNode", 0, 0))), new JSONArray());
        JSONObject proposed = root(new JSONArray(List.of(node("AddNode", 5, 0))), new JSONArray());

        GraphDiff.Report report = GraphDiff.compare(current, proposed);

        assertEquals(1, report.changes().size());
        GraphDiff.Change change = report.changes().get(0);
        assertEquals(GraphDiff.ChangeType.MODIFIED, change.type());
        assertEquals("/nodes/0/x", change.pointer());
        assertEquals(0, ((Number) change.before()).intValue());
        assertEquals(5, ((Number) change.after()).intValue());
    }

    @Test
    void integerAndDoubleFormsOfTheSameNumberAreNotAChange() {
        JSONObject current = root(new JSONArray(List.of(node("AddNode", 5, 0))), new JSONArray());
        // A hand-written proposal spells the coordinate "5" rather than org.json's own "5.0".
        JSONObject proposed = new JSONObject(current.toString().replace("5.0", "5"));

        GraphDiff.Report report = GraphDiff.compare(current, proposed);

        assertTrue(report.isUnchanged(), report.changes().toString());
    }

    @Test
    void aNodeAppendedAtTheEndIsReportedAsAdded() {
        JSONObject current = root(new JSONArray(List.of(node("AddNode", 0, 0))), new JSONArray());
        JSONObject proposed = root(new JSONArray(List.of(node("AddNode", 0, 0), node("SubtractNode", 10, 10))), new JSONArray());

        GraphDiff.Report report = GraphDiff.compare(current, proposed);

        assertEquals(1, report.changes().size());
        GraphDiff.Change change = report.changes().get(0);
        assertEquals(GraphDiff.ChangeType.ADDED, change.type());
        assertEquals("/nodes/1", change.pointer());
    }

    @Test
    void aNodeRemovedFromTheEndIsReportedAsRemoved() {
        JSONObject current = root(new JSONArray(List.of(node("AddNode", 0, 0), node("SubtractNode", 10, 10))), new JSONArray());
        JSONObject proposed = root(new JSONArray(List.of(node("AddNode", 0, 0))), new JSONArray());

        GraphDiff.Report report = GraphDiff.compare(current, proposed);

        assertEquals(1, report.changes().size());
        GraphDiff.Change change = report.changes().get(0);
        assertEquals(GraphDiff.ChangeType.REMOVED, change.type());
        assertEquals("/nodes/1", change.pointer());
    }

    @Test
    void reorderingDataEdgesAloneReportsNoChange() {
        JSONArray currentEdges = new JSONArray(List.of(dataEdge(0, "A", 1, "B"), dataEdge(1, "C", 2, "D")));
        JSONArray proposedEdges = new JSONArray(List.of(dataEdge(1, "C", 2, "D"), dataEdge(0, "A", 1, "B")));

        GraphDiff.Report report = GraphDiff.compare(root(new JSONArray(), currentEdges), root(new JSONArray(), proposedEdges));

        assertTrue(report.isUnchanged(), report.changes().toString());
    }

    @Test
    void anAddedDataEdgeIsReportedOnce() {
        JSONArray currentEdges = new JSONArray(List.of(dataEdge(0, "A", 1, "B")));
        JSONArray proposedEdges = new JSONArray(List.of(dataEdge(0, "A", 1, "B"), dataEdge(1, "C", 2, "D")));

        GraphDiff.Report report = GraphDiff.compare(root(new JSONArray(), currentEdges), root(new JSONArray(), proposedEdges));

        assertEquals(1, report.changes().size());
        GraphDiff.Change change = report.changes().get(0);
        assertEquals(GraphDiff.ChangeType.ADDED, change.type());
        assertEquals("/dataEdges/1", change.pointer());
    }

    @Test
    void aChangedDataEdgeIsRemoveAndAddRatherThanAModification() {
        JSONArray currentEdges = new JSONArray(List.of(dataEdge(0, "A", 1, "B")));
        JSONArray proposedEdges = new JSONArray(List.of(dataEdge(0, "A", 1, "Different")));

        GraphDiff.Report report = GraphDiff.compare(root(new JSONArray(), currentEdges), root(new JSONArray(), proposedEdges));

        assertEquals(2, report.changes().size());
        assertTrue(report.changes().stream().anyMatch(c -> c.type() == GraphDiff.ChangeType.REMOVED));
        assertTrue(report.changes().stream().anyMatch(c -> c.type() == GraphDiff.ChangeType.ADDED));
    }

    @Test
    void aPluginRowIsMatchedByIdSoAVersionBumpIsAModification() {
        JSONObject current = new JSONObject().put("version", 2).put("nodes", new JSONArray())
                .put("dataEdges", new JSONArray()).put("flowEdges", new JSONArray())
                .put("plugins", new JSONArray(List.of(
                        new JSONObject().put("id", "housegraph-discord").put("version", "1.0.0"))));
        JSONObject proposed = new JSONObject().put("version", 2).put("nodes", new JSONArray())
                .put("dataEdges", new JSONArray()).put("flowEdges", new JSONArray())
                .put("plugins", new JSONArray(List.of(
                        new JSONObject().put("id", "housegraph-discord").put("version", "1.1.0"))));

        GraphDiff.Report report = GraphDiff.compare(current, proposed);

        assertEquals(1, report.changes().size());
        GraphDiff.Change change = report.changes().get(0);
        assertEquals(GraphDiff.ChangeType.MODIFIED, change.type());
        assertEquals("/plugins/0/version", change.pointer());
        assertEquals("1.0.0", change.before());
        assertEquals("1.1.0", change.after());
    }
}
