package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                List.of(new GraphCanvas.ClipboardDataEdge(0, 0, 1, 0)),
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

        GraphCanvas.GraphSnapshot snapshot = new GraphCanvas.GraphSnapshot(
                List.of(
                        new GraphCanvas.ClipboardNode(a, 0.0, 0.0),
                        new GraphCanvas.ClipboardNode(b, 50.0, 0.0)),
                List.of(),
                List.of(new GraphCanvas.ClipboardFlowEdge(0, 1)));

        GraphCanvas.GraphSnapshot roundTripped = roundTrip(snapshot);

        assertEquals(1, roundTripped.flowEdges().size());
        assertEquals(0, roundTripped.flowEdges().get(0).sourceNodeIndex());
        assertEquals(1, roundTripped.flowEdges().get(0).targetNodeIndex());
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

    private static GraphCanvas.GraphSnapshot roundTrip(GraphCanvas.GraphSnapshot snapshot) {
        String text = GraphFileIO.toJson(snapshot).toString();
        return GraphFileIO.fromJson(new JSONObject(new JSONTokener(text)));
    }
}
