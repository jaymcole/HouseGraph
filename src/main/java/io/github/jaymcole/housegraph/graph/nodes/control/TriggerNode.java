package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.Button;

/**
 * Simple entry-point node: no data ports, just a flow-out port used to kick off
 * execution of downstream flow-connected nodes. Its UI is a button that calls
 * {@link #execute()} directly — see {@link NodeContentProvider}.
 */
@Display.Name("Trigger")
public class TriggerNode extends BaseNode implements NodeContentProvider {

    @Override
    public void process() {
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
