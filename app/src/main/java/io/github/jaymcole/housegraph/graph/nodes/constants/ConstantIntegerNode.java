package io.github.jaymcole.housegraph.graph.nodes.constants;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Integer Constant")
@Display.Description("A fixed whole number you type in, supplied to whatever you wire it to.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"integer", "int", "number", "whole", "literal", "value"})
public class ConstantIntegerNode extends BaseNode {

    private final NodeVariable<Integer> out = new NodeVariable<>("out", Integer.class, true);

    @Override
    public void process(ProcessContext ctx) {}

    @Override
    public void configureInputs() {}

    @Override
    public void configureOutputs() {
        addOutput(out);
    }
}
