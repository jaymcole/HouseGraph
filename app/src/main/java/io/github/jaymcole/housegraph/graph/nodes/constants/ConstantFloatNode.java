package io.github.jaymcole.housegraph.graph.nodes.constants;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Float Constant")
@Display.Description("A fixed decimal number you type in, supplied to whatever you wire it to.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"float", "number", "decimal", "literal", "value"})
public class ConstantFloatNode extends BaseNode {

    private final NodeVariable<Float> out = new NodeVariable<>("out", Float.class, true);

    @Override
    public void process(ProcessContext ctx) {}

    @Override
    public void configureInputs() {}

    @Override
    public void configureOutputs() {
        addOutput(out);
    }
}
