package io.github.jaymcole.housegraph.graph.nodes.constants;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("String Constant")

public class ConstantStringNode extends BaseNode {

    private final NodeVariable<String> out = new NodeVariable<>("out", String.class, true);

    @Override
    public void process() {}

    @Override
    public void configureInputs() {}

    @Override
    public void configureOutputs() {
        addOutput(out);
    }
}
