package io.github.jaymcole.housegraph.graph.fixture.liba.nodes.math;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/** Unannotated type id (so it persists under its simple name) in a subpackage (so it has a category). */
@Display.Name("Fixture Add")
public class FixtureAddNode extends BaseNode {

    private final NodeVariable<Float> left = new NodeVariable<>("Left", Float.class);
    private final NodeVariable<Float> right = new NodeVariable<>("Right", Float.class);
    private final NodeVariable<Float> sum = new NodeVariable<>("Sum", Float.class);

    @Override
    public void configureInputs() {
        addInput(left);
        addInput(right);
    }

    @Override
    public void configureOutputs() {
        addOutput(sum);
    }

    @Override
    public void process(ProcessContext ctx) {
        sum.setValue(ctx.get(left, 0f) + ctx.get(right, 0f));
    }
}
