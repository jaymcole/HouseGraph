package io.github.jaymcole.housegraph.search.fixture.nodes.beta;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/** Its simple name carries an acronym that only tokenises correctly if the splitter is right. */
@Display.Name("HTTP Server")
@Node.Kind(NodeKind.RESOURCE)
@Node.Keywords({"web", "listen"})
public class HTTPServerFixtureNode extends BaseNode {

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
