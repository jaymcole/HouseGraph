package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.Button;

/**
 * Simple entry-point node: no data ports, just a flow-out port used to kick off
 * execution of downstream flow-connected nodes. Its UI is a button that calls
 * {@link #execute()} directly — see {@link NodeContentProvider}.
 */
@Display.Name("Trigger")
@Display.Description("Starts a run when you press its button.")
@Kind(NodeKind.CONTROL)
@Keywords({"start", "run", "fire", "button", "manual", "entry point"})
public class TriggerNode extends BaseNode implements NodeContentProvider {

    @Override
    public void process(ProcessContext ctx) {
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }

    @Override
    public Node createNodeContent() {
        Button triggerButton = new Button("Start");
        triggerButton.setOnAction(e -> execute());
        return triggerButton;
    }
}
