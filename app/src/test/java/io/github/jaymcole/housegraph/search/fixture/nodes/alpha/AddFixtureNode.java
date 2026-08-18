package io.github.jaymcole.housegraph.search.fixture.nodes.alpha;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/** Its keywords carry terms its name does not, which is the discovery case worth testing. */
@Display.Name("Add Numbers")
@Display.Description("Adds two numbers together.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"plus", "sum", "+", "arithmetic"})
public class AddFixtureNode extends BaseNode {

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
