package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

public class IfBoolNode extends BaseNode {

    private final NodeVariable<Boolean> condition = new NodeVariable<>("Condition", Boolean.class, true).required();
    private final FlowPort truePort = new FlowPort("True", FlowPort.Direction.OUT);
    private final FlowPort falsePort = new FlowPort("False", FlowPort.Direction.OUT);

    @Override
    public void process() {
        activate(condition.getValue() ? truePort : falsePort);
    }

    @Override
    public void configureInputs() {
        addInput(condition);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(new FlowPort("", FlowPort.Direction.IN));
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(truePort);
        addFlowOutput(falsePort);
    }
}