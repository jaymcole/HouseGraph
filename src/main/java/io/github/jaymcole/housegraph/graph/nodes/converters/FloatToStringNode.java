package io.github.jaymcole.housegraph.graph.nodes.converters;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Executable;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Float to String")
@Executable.ExecutableIn
@Executable.ExecutableOut
public class FloatToStringNode extends BaseNode {

    private final NodeVariable<Float> in = new NodeVariable<>("out", Float.class, true);
    private final NodeVariable<String> out = new NodeVariable<>("in", String.class, true);


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
}
