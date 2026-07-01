package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.graph.nodes.control.TriggerNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.math.ConstantFloatNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeGraphTest {

    @Test
    void dataEdgePropagatesValueAcrossNodes() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);

        output(constant).setValue(5f);
        graph.registerEdge(new Edge(constant, output(constant), add, input(add, "V1")));

        add.beginProcessing();

        assertEquals(5f, output(add).getValue());
    }

    @Test
    void resolveIsFreshEveryCallNotAStaleCache() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);
        graph.registerEdge(new Edge(constant, output(constant), add, input(add, "V1")));

        output(constant).setValue(2f);
        add.beginProcessing();
        assertEquals(2f, output(add).getValue());

        output(constant).setValue(9f);
        add.beginProcessing();
        assertEquals(9f, output(add).getValue(), "a second beginProcessing() call must not serve a stale cached value");
    }

    @Test
    void dataCycleThrowsInsteadOfOverflowingTheStack() {
        NodeGraph graph = new NodeGraph();
        AddNode a = new AddNode();
        AddNode b = new AddNode();
        graph.addNode(a);
        graph.addNode(b);

        graph.registerEdge(new Edge(a, output(a), b, input(b, "V1")));
        graph.registerEdge(new Edge(b, output(b), a, input(a, "V1")));

        assertThrows(IllegalStateException.class, a::beginProcessing);
    }

    @Test
    void triggerCascadesAlongFlowEdgesAndRunsProcess() {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(trigger);
        graph.addNode(constant);
        graph.addNode(add);

        output(constant).setValue(4f);
        graph.registerEdge(new Edge(constant, output(constant), add, input(add, "V1")));
        graph.registerFlowEdge(new FlowEdge(trigger, add));

        trigger.execute();

        assertEquals(4f, output(add).getValue(), "add was reached only via a flow edge, with no direct trigger->add data edge");
    }

    @Test
    void flowCycleIsDedupedInsteadOfInfiniteLooping() {
        NodeGraph graph = new NodeGraph();
        AddNode a = new AddNode();
        AddNode b = new AddNode();
        graph.addNode(a);
        graph.addNode(b);

        graph.registerFlowEdge(new FlowEdge(a, b));
        graph.registerFlowEdge(new FlowEdge(b, a));

        assertDoesNotThrow(a::execute);
    }

    @Test
    void removingANodePurgesItsEdges() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);
        graph.registerEdge(new Edge(constant, output(constant), add, input(add, "V1")));

        graph.removeNode(constant);

        assertTrue(graph.getIncomingDataEdges(add).isEmpty());
        assertFalse(graph.getNodes().contains(constant));
    }

    @Test
    void registeringAnEdgeForAnUnregisteredNodeFails() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(add); // constant deliberately never added

        Edge edge = new Edge(constant, output(constant), add, input(add, "V1"));
        assertThrows(IllegalStateException.class, () -> graph.registerEdge(edge));
    }

    @Test
    void mismatchedVariableTypesAreRejected() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);

        NodeVariable<Integer> notAFloat = new NodeVariable<>("bogus", Integer.class);
        Edge edge = new Edge(constant, notAFloat, add, input(add, "V1"));

        assertThrows(IllegalArgumentException.class, () -> graph.registerEdge(edge));
    }

    @Test
    void runningBeforeBeingAddedToAGraphFailsClearly() {
        AddNode add = new AddNode();
        assertThrows(IllegalStateException.class, add::beginProcessing);
    }

    @SuppressWarnings("unchecked")
    private static NodeVariable<Float> output(BaseNode node) {
        return node.getOutputs().get(0);
    }

    @SuppressWarnings("unchecked")
    private static NodeVariable<Float> input(BaseNode node, String name) {
        for (NodeVariable variable : node.getInputs()) {
            if (variable.name.equals(name)) {
                return variable;
            }
        }
        throw new IllegalArgumentException("No input named " + name + " on " + node.getName());
    }
}
