package io.github.jaymcole.housegraph.graph.fixture.libb.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/** Unique to libB, so its presence or absence proves whether a root is actually being scanned. */
@Display.Name("Only In LibB")
public class OnlyInLibBNode extends BaseNode {

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
