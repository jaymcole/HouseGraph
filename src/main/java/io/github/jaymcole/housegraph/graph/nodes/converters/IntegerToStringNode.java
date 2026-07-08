package io.github.jaymcole.housegraph.graph.nodes.converters;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Integer to String")
public class IntegerToStringNode extends BaseNode {

    private final NodeVariable<Integer> in = new NodeVariable<>("in", Integer.class, false).required();
    private final NodeVariable<String> out = new NodeVariable<>("out", String.class, false);


    @Override
    public void process() {
        out.setValue(in.getValue().toString());
    }

    @Override
    public void configureInputs() {
        addInput(in);
    }

    @Override
    public void configureOutputs() {
        addOutput(out);
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
