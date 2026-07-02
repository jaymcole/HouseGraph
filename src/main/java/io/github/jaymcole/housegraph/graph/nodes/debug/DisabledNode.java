package io.github.jaymcole.housegraph.graph.nodes.debug;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;

/* Exists to test Node.Disable annotation - this Node should not appear as an option in the context menu */
@Display.Name("DisabledNode - should not appear")
@Node.Disabled
public class DisabledNode extends BaseNode {
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
