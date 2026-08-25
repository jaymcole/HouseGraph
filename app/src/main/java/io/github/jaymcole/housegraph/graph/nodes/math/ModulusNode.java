package io.github.jaymcole.housegraph.graph.nodes.math;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Modulus")
@Display.Description("Divides two numbers and outputs the remainder.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"modulus", "modulo", "mod", "remainder", "%", "arithmetic", "math"})
public class ModulusNode extends BaseNode {

    private final NodeVariable<Float> v1 = new NodeVariable<>("V1", Float.class);
    private final NodeVariable<Float> v2 = new NodeVariable<>("V2", Float.class);
    private final NodeVariable<Float> remainder = new NodeVariable<>("Remainder", Float.class);

    @Override
    public void process(ProcessContext ctx) {
        remainder.setValue(ctx.get(v1, 0f) % ctx.get(v2, 0f));
    }

    @Override
    public void configureInputs() {
        addInput(v1);
        addInput(v2);
    }

    @Override
    public void configureOutputs() {
        addOutput(remainder);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(new FlowPort("", FlowPort.Direction.IN));
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }
}
