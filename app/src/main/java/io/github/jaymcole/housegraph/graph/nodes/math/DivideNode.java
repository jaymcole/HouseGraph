package io.github.jaymcole.housegraph.graph.nodes.math;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Divide")
@Display.Description("Divides the first number by the second.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"divide", "division", "quotient", "/", "arithmetic", "math"})
public class DivideNode extends BaseNode {

    private final NodeVariable<Float> v1 = new NodeVariable<>("V1", Float.class)
            .describedAs("The number being divided. Order matters: Quotient is V1 divided by V2. "
                    + "Unwired counts as 0.");
    private final NodeVariable<Float> v2 = new NodeVariable<>("V2", Float.class)
            .describedAs("What V1 is divided by. Unwired counts as 0, and dividing by 0 gives infinity "
                    + "(or NaN when V1 is 0 too) rather than failing the run.");
    private final NodeVariable<Float> quotient = new NodeVariable<>("Quotient", Float.class)
            .describedAs("V1 divided by V2.");

    @Override
    public void process(ProcessContext ctx) {
        quotient.setValue(ctx.get(v1, 0f) / ctx.get(v2, 0f));
    }

    @Override
    public void configureInputs() {
        addInput(v1);
        addInput(v2);
    }

    @Override
    public void configureOutputs() {
        addOutput(quotient);
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
