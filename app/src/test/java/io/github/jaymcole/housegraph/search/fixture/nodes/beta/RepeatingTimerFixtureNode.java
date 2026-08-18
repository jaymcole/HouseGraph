package io.github.jaymcole.housegraph.search.fixture.nodes.beta;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
/** Declares CONTROL while also implementing AutoStartable, so declaration must win. */
@Display.Name("Repeating Timer")
@Display.Description("Fires over and over on an interval.")
@Node.Kind(NodeKind.CONTROL)
@Node.Keywords({"interval", "schedule"})
public class RepeatingTimerFixtureNode extends BaseNode implements AutoStartable {
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
