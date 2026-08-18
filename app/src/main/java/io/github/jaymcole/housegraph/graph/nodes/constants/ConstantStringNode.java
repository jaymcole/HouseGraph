package io.github.jaymcole.housegraph.graph.nodes.constants;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("String Constant")

@Display.Description("A fixed piece of text you type in, supplied to whatever you wire it to.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"string", "text", "literal", "value"})
public class ConstantStringNode extends BaseNode {

    private final NodeVariable<String> out = new NodeVariable<>("out", String.class, true);

    @Override
    public void process(ProcessContext ctx) {}

    @Override
    public void configureInputs() {}

    @Override
    public void configureOutputs() {
        addOutput(out);
    }
}
