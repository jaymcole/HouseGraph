package io.github.jaymcole.housegraph.graph.nodes.debug;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

/**
 * Blocks process() for the given number of milliseconds before returning. Useful for
 * simulating an expensive/slow node: dropping this into a flow chain shows how the
 * rest of the graph behaves around a slow node - in particular, any sibling
 * control-flow branch keeps running independently rather than waiting on this one
 * (see {@link io.github.jaymcole.housegraph.graph.NodeGraph}'s class Javadoc).
 */
@Display.Name("Debug Delay")
public class DebugDelayNode extends BaseNode {

    private final NodeVariable<Integer> delayMillis = new NodeVariable<>("Delay (ms)", Integer.class, true);

    @Override
    public void process() {
        Integer millis = delayMillis.getValue();
        if (millis == null || millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void configureInputs() {
        addInput(delayMillis);
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
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }
}
