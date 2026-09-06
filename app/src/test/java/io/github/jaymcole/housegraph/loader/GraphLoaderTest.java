package io.github.jaymcole.housegraph.loader;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import io.github.jaymcole.housegraph.graph.nodes.MissingNode;
import io.github.jaymcole.housegraph.graph.nodes.control.IfNode;
import io.github.jaymcole.housegraph.graph.nodes.control.TriggerNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.saveformat.ClipboardDataEdge;
import io.github.jaymcole.housegraph.saveformat.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import javafx.geometry.Point2D;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the snapshot loader with no canvas, no scene and no node view — which is the
 * point of the class. Nothing here starts a JavaFX toolkit; {@code Point2D} is only ever
 * carried through as data.
 */
class GraphLoaderTest {

    /** Load the snapshot as a file load does: the nodes are already built, so the factory just unwraps them. */
    private static LoadedGraph load(NodeGraph graph, GraphSnapshot snapshot) {
        return GraphLoader.load(snapshot, ClipboardNode::node, graph);
    }

    private static ClipboardNode at(BaseNode node, double x, double y) {
        return new ClipboardNode(node, x, y);
    }

    @Test
    void registersEveryNodeInSnapshotOrder() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        TriggerNode trigger = new TriggerNode();

        LoadedGraph loaded = load(graph, new GraphSnapshot(
                List.of(at(constant, 10, 20), at(add, 100, 20), at(trigger, 200, 20)),
                List.of(), List.of()));

        assertEquals(List.of(constant, add, trigger), loaded.nodes());
        assertEquals(List.of(constant, add, trigger), new ArrayList<>(graph.getNodes()));
    }

    @Test
    void keepsANullSlotSoLaterIndicesStillResolve() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();

        // Index 0 is a node the factory could not build. Dropping the slot instead of holding it
        // would shift both later nodes down one and misdirect the edge.
        GraphSnapshot snapshot = new GraphSnapshot(
                List.of(at(null, 0, 0), at(constant, 10, 20), at(add, 100, 20)),
                List.of(new ClipboardDataEdge(1, 0, 2, 0, List.of())),
                List.of());

        LoadedGraph loaded = GraphLoader.load(snapshot, ClipboardNode::node, graph);

        assertEquals(3, loaded.nodes().size());
        assertNull(loaded.nodes().get(0));
        assertEquals(2, graph.getNodes().size());
        assertEquals(1, loaded.dataEdges().size());
        Edge edge = loaded.dataEdges().get(0).edge();
        assertSame(constant, edge.getSourceNode());
        assertSame(add, edge.getTargetNode());
    }

    @Test
    void resolvesDataEdgePortsByIndexIntoTheNodesOwnLists() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();

        // Into the second input, not the first.
        LoadedGraph loaded = load(graph, new GraphSnapshot(
                List.of(at(constant, 0, 0), at(add, 100, 0)),
                List.of(new ClipboardDataEdge(0, 0, 1, 1, List.of())),
                List.of()));

        Edge edge = loaded.dataEdges().get(0).edge();
        assertSame(constant.getOutputs().get(0), edge.getSourceVariable());
        assertSame(add.getInputs().get(1), edge.getTargetVariable());
        assertEquals(1, graph.getIncomingDataEdges(add).size());
    }

    @Test
    void resolvesFlowEdgePortsByIndexIntoTheNodesOwnLists() {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        IfNode branch = new IfNode();
        IfNode downstream = new IfNode();

        LoadedGraph loaded = load(graph, new GraphSnapshot(
                List.of(at(trigger, 0, 0), at(branch, 100, 0), at(downstream, 200, 0)),
                List.of(),
                List.of(
                        new ClipboardFlowEdge(0, 0, 1, 0, List.of()),
                        // The False branch (out-port 1), which is the whole reason the index is saved.
                        new ClipboardFlowEdge(1, 1, 2, 0, List.of()))));

        assertEquals(2, loaded.flowEdges().size());
        FlowEdge fromTrigger = loaded.flowEdges().get(0).edge();
        assertSame(trigger.getFlowOutputs().get(0), fromTrigger.getSourcePort());
        assertSame(branch.getFlowInputs().get(0), fromTrigger.getTargetPort());

        FlowEdge fromFalseBranch = loaded.flowEdges().get(1).edge();
        assertSame(branch.getFlowOutputs().get(1), fromFalseBranch.getSourcePort());
        assertEquals("False", fromFalseBranch.getSourcePort().name);
        assertSame(downstream, fromFalseBranch.getTargetNode());
        assertEquals(1, graph.getOutgoingFlowEdges(branch).size());
    }

    @Test
    void dropsAnEdgeWithAnOutOfRangePortIndexAndKeepsTheRest() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        TriggerNode trigger = new TriggerNode();
        IfNode branch = new IfNode();

        LoadedGraph loaded = load(graph, new GraphSnapshot(
                List.of(at(constant, 0, 0), at(add, 100, 0), at(trigger, 0, 100), at(branch, 100, 100)),
                List.of(
                        // A save file written when AddNode had a third input.
                        new ClipboardDataEdge(0, 0, 1, 2, List.of()),
                        new ClipboardDataEdge(0, 0, 1, 0, List.of())),
                List.of(
                        new ClipboardFlowEdge(2, 3, 3, 0, List.of()),
                        new ClipboardFlowEdge(2, 0, 3, 0, List.of()))));

        assertEquals(1, loaded.dataEdges().size());
        assertSame(add.getInputs().get(0), loaded.dataEdges().get(0).edge().getTargetVariable());
        assertEquals(1, loaded.flowEdges().size());
        assertSame(trigger.getFlowOutputs().get(0), loaded.flowEdges().get(0).edge().getSourcePort());
    }

    @Test
    void dropsAnEdgeReferencingANullSlotOrAMissingNodeIndex() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();

        LoadedGraph loaded = load(graph, new GraphSnapshot(
                List.of(at(null, 0, 0), at(constant, 10, 0), at(add, 100, 0)),
                List.of(
                        new ClipboardDataEdge(0, 0, 2, 0, List.of()),   // from the null slot
                        new ClipboardDataEdge(1, 0, 7, 0, List.of()),   // past the end
                        new ClipboardDataEdge(1, 0, 2, 1, List.of())),  // the one good edge
                List.of(new ClipboardFlowEdge(0, 0, 2, 0, List.of()))));

        assertEquals(1, loaded.dataEdges().size());
        assertSame(add.getInputs().get(1), loaded.dataEdges().get(0).edge().getTargetVariable());
        assertTrue(loaded.flowEdges().isEmpty());
        assertEquals(1, graph.getIncomingDataEdges(add).size());
    }

    @Test
    void pairsEachEdgeWithTheSnapshotEntryThatProducedIt() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        ClipboardDataEdge entry = new ClipboardDataEdge(0, 0, 1, 0, List.of(new Point2D(30, 40)));

        LoadedGraph loaded = load(graph, new GraphSnapshot(
                List.of(at(constant, 0, 0), at(add, 100, 0)), List.of(entry), List.of()));

        // The waypoints have nowhere to live on an Edge; the pairing is how a host gets them
        // onto the right one.
        assertSame(entry, loaded.dataEdges().get(0).entry());
        assertEquals(List.of(new Point2D(30, 40)), loaded.dataEdges().get(0).entry().waypoints());
    }

    @Test
    void tellsTheListenerAboutEachNodeBeforeItJoinsTheGraph() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        List<BaseNode> seen = new ArrayList<>();

        GraphLoader.load(
                new GraphSnapshot(List.of(at(constant, 10, 20), at(add, 100, 20)), List.of(), List.of()),
                ClipboardNode::node,
                graph,
                (entry, node) -> {
                    // A node's view is built from here, and NodeGraph.addNode is what activates it.
                    assertFalse(graph.getNodes().contains(node));
                    assertEquals(node == constant ? 10.0 : 100.0, entry.x());
                    seen.add(node);
                });

        assertEquals(List.of(constant, add), seen);
    }

    @Test
    void wiringAnInputThatIsAlreadyFedReplacesTheEdgeFeedingIt() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode first = new ConstantFloatNode();
        ConstantFloatNode second = new ConstantFloatNode();
        AddNode add = new AddNode();

        // NodeGraph allows one data edge per input and throws on a second, so a snapshot that
        // targets an input twice has to replace rather than stack - as the interactive path does.
        LoadedGraph loaded = load(graph, new GraphSnapshot(
                List.of(at(first, 0, 0), at(second, 0, 100), at(add, 100, 0)),
                List.of(
                        new ClipboardDataEdge(0, 0, 2, 0, List.of()),
                        new ClipboardDataEdge(1, 0, 2, 0, List.of())),
                List.of()));

        assertEquals(2, loaded.dataEdges().size());
        assertEquals(1, graph.getIncomingDataEdges(add).size());
        assertSame(second, graph.getIncomingDataEdges(add).iterator().next().getSourceNode());
    }

    @Test
    void reusesOneFlowEdgeForAPairSavedTwice() {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        IfNode branch = new IfNode();

        LoadedGraph loaded = load(graph, new GraphSnapshot(
                List.of(at(trigger, 0, 0), at(branch, 100, 0)),
                List.of(),
                List.of(
                        new ClipboardFlowEdge(0, 0, 1, 0, List.of()),
                        new ClipboardFlowEdge(0, 0, 1, 0, List.of()))));

        assertEquals(2, loaded.flowEdges().size());
        assertSame(loaded.flowEdges().get(0).edge(), loaded.flowEdges().get(1).edge());
        assertEquals(1, graph.getOutgoingFlowEdges(trigger).size());
    }

    @Test
    void wiresEdgesToThePortsAPlaceholderNodeWasGivenWhileTheFileWasRead() {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        // What GraphFileIO hands over for a node whose library isn't installed: a MissingNode whose
        // ports were back-filled from the edge lists (flow ports are never persisted on the node).
        MissingNode placeholder = MissingNode.from(
                new JSONObject().put("type", "com.example.NotARealNode").put("x", 0).put("y", 0), null);
        placeholder.ensureFlowPort(0, false);

        LoadedGraph loaded = load(graph, new GraphSnapshot(
                List.of(at(trigger, 0, 0), at(placeholder, 100, 0)),
                List.of(),
                List.of(new ClipboardFlowEdge(0, 0, 1, 0, List.of()))));

        assertEquals(1, loaded.flowEdges().size());
        assertSame(placeholder.getFlowInputs().get(0), loaded.flowEdges().get(0).edge().getTargetPort());
    }
}
