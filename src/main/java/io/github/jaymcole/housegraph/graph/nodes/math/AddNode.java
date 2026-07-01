package io.github.jaymcole.housegraph.graph.nodes.math;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Executable;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Add")
@Executable.ExecutableIn
@Executable.ExecutableOut
public class AddNode extends BaseNode {

    private final NodeVariable<Float> v1 = new NodeVariable<>("V1", Float.class);
    private final NodeVariable<Float> v2 = new NodeVariable<>("V2", Float.class);
    private final NodeVariable<Float> sum = new NodeVariable<>("Sum", Float.class);

    @Override
    public void process() {
        sum.setValue(getSafeValue(v1) + getSafeValue(v2));
    }

    @Override
    public void configureInputs() {
        addInput(v1);
        addInput(v2);
    }

    @Override
    public void configureOutputs() {
        addOutput(sum);
    }

    private float getSafeValue(NodeVariable<Float> variable) {
        if (variable == null || variable.getValue() == null) {
            return 0f;
        }
        return variable.getValue();
    }
}
