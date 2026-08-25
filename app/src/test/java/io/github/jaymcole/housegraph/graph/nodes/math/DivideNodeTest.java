package io.github.jaymcole.housegraph.graph.nodes.math;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DivideNodeTest {

    private static void setInputs(DivideNode node, float v1, float v2) {
        node.getInputs().get(0).setValue(v1);
        node.getInputs().get(1).setValue(v2);
    }

    private static float output(DivideNode node) {
        return (Float) node.getOutputs().get(0).getValue();
    }

    @Test
    void dividesFirstBySecond() {
        DivideNode node = new DivideNode();
        setInputs(node, 9f, 3f);

        node.process(ProcessContext.uncancelled());

        assertEquals(3f, output(node));
    }

    @Test
    void nonExactDivisionYieldsFraction() {
        DivideNode node = new DivideNode();
        setInputs(node, 7f, 2f);

        node.process(ProcessContext.uncancelled());

        assertEquals(3.5f, output(node));
    }

    @Test
    void divisionByZeroYieldsInfinity() {
        DivideNode node = new DivideNode();
        setInputs(node, 5f, 0f);

        node.process(ProcessContext.uncancelled());

        assertTrue(Float.isInfinite(output(node)));
    }
}
