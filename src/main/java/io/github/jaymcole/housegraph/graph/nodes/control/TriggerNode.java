package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.annotations.Executable;
import io.github.jaymcole.housegraph.graph.BaseNode;

/**
 * Simple entry-point node: no data ports, just a flow-out port used to kick off
 * execution of downstream flow-connected nodes. Its UI adds a button that calls
 * {@link #execute()} directly.
 */
@Executable.ExecutableOut
public class TriggerNode extends BaseNode {

    @Override
    public void process() {
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }
}
