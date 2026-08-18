package io.github.jaymcole.housegraph.graph.nodes.converters;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Double to String")
@Display.Description("Turns a double-precision number into text.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"double", "string", "text", "convert", "cast", "number", "to string"})
public class DoubleToStringNode extends BaseNode {

    private final NodeVariable<Double> in = new NodeVariable<>("in", Double.class, false).required();
    private final NodeVariable<String> out = new NodeVariable<>("out", String.class, false);


    @Override
    public void process(ProcessContext ctx) {
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
