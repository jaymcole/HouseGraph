package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

/**
 * The canonical branch/decider node: when triggered, it sends control out one of two
 * flow ports depending on its Condition input. A non-zero (and non-null) condition
 * fires <b>True</b>; anything else fires <b>False</b>. Only the chosen branch's
 * downstream nodes run — see {@link BaseNode#activate} and {@code NodeGraph}'s cascade.
 * <p>
 * The condition is manually editable, so it can be tried out with a typed-in value
 * before wiring anything into it.
 */
@Display.Name("If")
public class IfNode extends BaseNode {

    private final NodeVariable<Float> condition = new NodeVariable<>("Condition", Float.class, true).required();
    private final FlowPort truePort = new FlowPort("True", FlowPort.Direction.OUT);
    private final FlowPort falsePort = new FlowPort("False", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        Float value = condition.getValue();
        boolean truthy = value != null && value != 0f;
        activate(truthy ? truePort : falsePort);
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
