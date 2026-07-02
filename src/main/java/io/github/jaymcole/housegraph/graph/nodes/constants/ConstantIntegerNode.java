package io.github.jaymcole.housegraph.graph.nodes.constants;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Integer Constant")
public class ConstantIntegerNode extends BaseNode {

    private final NodeVariable<Integer> out = new NodeVariable<>("out", Integer.class, true);

    @Override
    public void process() {}

    @Override
    public void configureInputs() {}

    @Override
    public void configureOutputs() {
        addOutput(out);
    }
}
