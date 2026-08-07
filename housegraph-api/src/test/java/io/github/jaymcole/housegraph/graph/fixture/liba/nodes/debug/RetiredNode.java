package io.github.jaymcole.housegraph.graph.fixture.liba.nodes.debug;

import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/**
 * Disabled, so it must stay out of the Add-Node menu while remaining resolvable — a graph saved
 * while the type was enabled still has to load. Also pins an explicit id and a legacy alias, which
 * is the path that keeps old saves working across a class rename.
 */
@Node.Disabled("Fixture for the disabled-but-still-loadable path")
@Node.Type(value = "retired-fixture", aliases = {"legacy.retired.fixture.id"})
public class RetiredNode extends BaseNode {

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
