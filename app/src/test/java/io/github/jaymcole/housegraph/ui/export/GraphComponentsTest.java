package io.github.jaymcole.housegraph.ui.export;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import io.github.jaymcole.housegraph.graph.nodes.control.TriggerNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless — {@link GraphComponents} is deliberately free of JavaFX, so the splitting rule that
 * decides what lands in which exported image is testable without a display.
 */
class GraphComponentsTest {

    @Test
    void emptyGraphHasNoComponents() {
        assertEquals(List.of(), GraphComponents.connectedComponents(new NodeGraph()));
    }

    @Test
    void anUnwiredNodeIsItsOwnComponent() {
        NodeGraph graph = new NodeGraph();
        AddNode lonely = new AddNode();
        graph.addNode(lonely);

        List<Set<BaseNode>> components = GraphComponents.connectedComponents(graph);

        assertEquals(1, components.size());
        assertEquals(Set.of(lonely), components.get(0));
    }

    @Test
    void nodesJoinedByADataEdgeShareAComponent() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);
        graph.registerEdge(new Edge(constant, output(constant), add, input(add, "V1")));

        List<Set<BaseNode>> components = GraphComponents.connectedComponents(graph);

        assertEquals(1, components.size());
        assertEquals(Set.of(constant, add), components.get(0));
    }

    @Test
    void aFlowEdgeConnectsJustAsADataEdgeDoes() {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        AddNode add = new AddNode();
        graph.addNode(trigger);
        graph.addNode(add);
        graph.registerFlowEdge(new FlowEdge(trigger, flowOut(trigger), add, flowIn(add)));

        List<Set<BaseNode>> components = GraphComponents.connectedComponents(graph);

        assertEquals(1, components.size());
        assertEquals(Set.of(trigger, add), components.get(0));
    }

    @Test
    void unrelatedAutomationsComeBackSeparately() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode firstSource = new ConstantFloatNode();
        AddNode firstSink = new AddNode();
        ConstantFloatNode secondSource = new ConstantFloatNode();
        AddNode secondSink = new AddNode();
        for (BaseNode node : List.of(firstSource, firstSink, secondSource, secondSink)) {
            graph.addNode(node);
        }
        graph.registerEdge(new Edge(firstSource, output(firstSource), firstSink, input(firstSink, "V1")));
        graph.registerEdge(new Edge(secondSource, output(secondSource), secondSink, input(secondSink, "V1")));

        List<Set<BaseNode>> components = GraphComponents.connectedComponents(graph);

        assertEquals(2, components.size());
        assertTrue(components.contains(Set.of(firstSource, firstSink)));
        assertTrue(components.contains(Set.of(secondSource, secondSink)));
    }

    @Test
    void reachabilityIgnoresEdgeDirection() {
        // Two sources feeding one sink: nothing is reachable downstream from either source to the
        // other, so only an undirected walk puts all three in one picture.
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode left = new ConstantFloatNode();
        ConstantFloatNode right = new ConstantFloatNode();
        AddNode sink = new AddNode();
        graph.addNode(left);
        graph.addNode(right);
        graph.addNode(sink);
        graph.registerEdge(new Edge(left, output(left), sink, input(sink, "V1")));
        graph.registerEdge(new Edge(right, output(right), sink, input(sink, "V2")));

        List<Set<BaseNode>> components = GraphComponents.connectedComponents(graph);

        assertEquals(1, components.size());
        assertEquals(Set.of(left, right, sink), components.get(0));
    }

    @Test
    void aDataEdgeAndAFlowEdgeCanBridgeTheSameComponent() {
        // A flow chain and a data chain sharing one node is a single automation, not two.
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        AddNode middle = new AddNode();
        AddNode consumer = new AddNode();
        graph.addNode(trigger);
        graph.addNode(middle);
        graph.addNode(consumer);
        graph.registerFlowEdge(new FlowEdge(trigger, flowOut(trigger), middle, flowIn(middle)));
        graph.registerEdge(new Edge(middle, output(middle), consumer, input(consumer, "V1")));

        List<Set<BaseNode>> components = GraphComponents.connectedComponents(graph);

        assertEquals(1, components.size());
        assertEquals(Set.of(trigger, middle, consumer), components.get(0));
    }

    @Test
    void aCycleTerminates() {
        // Two nodes wired to each other in both directions: the walk must not chase the loop.
        NodeGraph graph = new NodeGraph();
        AddNode first = new AddNode();
        AddNode second = new AddNode();
        graph.addNode(first);
        graph.addNode(second);
        graph.registerEdge(new Edge(first, output(first), second, input(second, "V1")));
        graph.registerFlowEdge(new FlowEdge(second, flowOut(second), first, flowIn(first)));

        List<Set<BaseNode>> components = GraphComponents.connectedComponents(graph);

        assertEquals(1, components.size());
        assertEquals(Set.of(first, second), components.get(0));
    }

    @Test
    void componentsFollowInsertionOrderSoImageNumberingIsStable() {
        // The caller turns a component's index into a filename, so the order must not depend on
        // hash iteration.
        for (int run = 0; run < 5; run++) {
            NodeGraph graph = new NodeGraph();
            AddNode first = new AddNode();
            AddNode second = new AddNode();
            AddNode third = new AddNode();
            graph.addNode(first);
            graph.addNode(second);
            graph.addNode(third);

            List<Set<BaseNode>> components = GraphComponents.connectedComponents(graph);

            assertEquals(3, components.size());
            assertEquals(Set.of(first), components.get(0));
            assertEquals(Set.of(second), components.get(1));
            assertEquals(Set.of(third), components.get(2));
        }
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

    private static FlowPort flowOut(BaseNode node) {
        return node.getFlowOutputs().get(0);
    }

    private static FlowPort flowIn(BaseNode node) {
        return node.getFlowInputs().get(0);
    }
}
