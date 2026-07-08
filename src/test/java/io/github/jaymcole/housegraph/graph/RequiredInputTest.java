package io.github.jaymcole.housegraph.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headless half of the "misconfigured node" feature: a {@link NodeVariable#required() required}
 * input leaves its node {@link BaseNode#isMisconfigured() misconfigured} until it has a value
 * source — an incoming data edge or a non-null authored value. The UI (node view / port tint)
 * merely renders what these methods report, so this is where the rule is pinned down.
 */
class RequiredInputTest {

    /** A minimal node: one required input, one optional input, one output. */
    private static class TwoInputNode extends BaseNode {
        final NodeVariable<Float> required = new NodeVariable<>("Required", Float.class, true).required();
        final NodeVariable<Float> optional = new NodeVariable<>("Optional", Float.class, true);
        final NodeVariable<Float> out = new NodeVariable<>("Out", Float.class);

        @Override public void process() { }
        @Override public void configureInputs() { addInput(required); addInput(optional); }
        @Override public void configureOutputs() { addOutput(out); }
    }

    private static class SourceNode extends BaseNode {
        final NodeVariable<Float> out = new NodeVariable<>("Out", Float.class);

        @Override public void process() { }
        @Override public void configureInputs() { }
        @Override public void configureOutputs() { addOutput(out); }
    }

    @Test
    void unwiredRequiredInputMakesNodeMisconfigured() {
        NodeGraph graph = new NodeGraph();
        TwoInputNode node = new TwoInputNode();
        graph.addNode(node);

        assertTrue(node.isMisconfigured());
        assertEquals(1, node.getUnsatisfiedRequiredInputs().size());
        assertEquals(node.required, node.getUnsatisfiedRequiredInputs().get(0));
    }

    @Test
    void optionalInputIsNeverAMisconfiguration() {
        NodeGraph graph = new NodeGraph();
        TwoInputNode node = new TwoInputNode();
        graph.addNode(node);

        // Only the required input is unsatisfied; the empty optional one never counts.
        assertFalse(node.getUnsatisfiedRequiredInputs().contains(node.optional));
    }

    @Test
    void incomingEdgeSatisfiesRequiredInput() {
        NodeGraph graph = new NodeGraph();
        SourceNode source = new SourceNode();
        TwoInputNode node = new TwoInputNode();
        graph.addNode(source);
        graph.addNode(node);

        graph.registerEdge(new Edge(source, source.out, node, node.required));

        assertFalse(node.isMisconfigured());
    }

    @Test
    void manualValueSatisfiesRequiredInput() {
        NodeGraph graph = new NodeGraph();
        TwoInputNode node = new TwoInputNode();
        graph.addNode(node);

        node.required.setValue(3f);

        assertFalse(node.isMisconfigured());
    }

    @Test
    void clearingTheManualValueMakesItMisconfiguredAgain() {
        NodeGraph graph = new NodeGraph();
        TwoInputNode node = new TwoInputNode();
        graph.addNode(node);

        node.required.setValue(3f);
        assertFalse(node.isMisconfigured());

        node.required.setValue(null);
        assertTrue(node.isMisconfigured());
    }
}
