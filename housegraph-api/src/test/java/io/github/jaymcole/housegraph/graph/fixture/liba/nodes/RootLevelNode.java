package io.github.jaymcole.housegraph.graph.fixture.liba.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/** Sits directly in the scan root, so its category path is empty. */
public class RootLevelNode extends BaseNode {

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void process(ProcessContext ctx) {
    }
}
