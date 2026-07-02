package io.github.jaymcole.housegraph.graph.nodes.constants;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Float Constant")
public class ConstantFloatNode extends BaseNode {

    private final NodeVariable<Float> out = new NodeVariable<>("out", Float.class, true);

    @Override
    public void process() {}

    @Override
    public void configureInputs() {}

    @Override
    public void configureOutputs() {
        addOutput(out);
    }
}
