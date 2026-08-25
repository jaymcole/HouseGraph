package io.github.jaymcole.housegraph.graph.nodes.math;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubtractNodeTest {

    private static void setInputs(SubtractNode node, float v1, float v2) {
        node.getInputs().get(0).setValue(v1);
        node.getInputs().get(1).setValue(v2);
    }

    private static float output(SubtractNode node) {
        return (Float) node.getOutputs().get(0).getValue();
    }

    @Test
    void subtractsSecondFromFirst() {
        SubtractNode node = new SubtractNode();
        setInputs(node, 7f, 3f);

        node.process(ProcessContext.uncancelled());

        assertEquals(4f, output(node));
    }

    @Test
    void resultCanBeNegative() {
        SubtractNode node = new SubtractNode();
        setInputs(node, 3f, 7f);

        node.process(ProcessContext.uncancelled());

        assertEquals(-4f, output(node));
    }

    @Test
    void missingInputsDefaultToZero() {
        SubtractNode node = new SubtractNode();

        node.process(ProcessContext.uncancelled());

        assertEquals(0f, output(node));
    }
}
