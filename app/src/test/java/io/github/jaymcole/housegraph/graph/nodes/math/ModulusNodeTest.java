package io.github.jaymcole.housegraph.graph.nodes.math;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModulusNodeTest {

    private static void setInputs(ModulusNode node, float v1, float v2) {
        node.getInputs().get(0).setValue(v1);
        node.getInputs().get(1).setValue(v2);
    }

    private static float output(ModulusNode node) {
        return (Float) node.getOutputs().get(0).getValue();
    }

    @Test
    void remainderOfPositiveNumbers() {
        ModulusNode node = new ModulusNode();
        setInputs(node, 7f, 3f);

        node.process(ProcessContext.uncancelled());

        assertEquals(1f, output(node));
    }

    @Test
    void divisibleNumbersYieldZero() {
        ModulusNode node = new ModulusNode();
        setInputs(node, 9f, 3f);

        node.process(ProcessContext.uncancelled());

        assertEquals(0f, output(node));
    }

    @Test
    void divisionByZeroYieldsNaN() {
        ModulusNode node = new ModulusNode();
        setInputs(node, 5f, 0f);

        node.process(ProcessContext.uncancelled());

        assertTrue(Float.isNaN(output(node)));
    }
}
