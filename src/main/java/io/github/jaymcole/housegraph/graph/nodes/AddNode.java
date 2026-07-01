package io.github.jaymcole.housegraph.graph.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

public class AddNode extends BaseNode {

    private final NodeVariable<Float> v1 = new NodeVariable<>("V1");
    private final NodeVariable<Float> v2 = new NodeVariable<>("V2");
    private final NodeVariable<Float> sum = new NodeVariable<>("Sum");

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
        if (variable == null) {
            return 0f;
        }
        return variable.getValue();
    }
}
