package io.github.jaymcole.housegraph.search.fixture.nodes.beta;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
/** Declares no kind but implements AutoStartable, so it should infer RESOURCE. */
@Display.Name("Auto Start Thing")
public class AutoStartFixtureNode extends BaseNode implements AutoStartable {
    @Override
    public void autoStartIfWasRunning() {
    }


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
