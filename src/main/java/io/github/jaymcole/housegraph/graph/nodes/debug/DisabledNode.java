package io.github.jaymcole.housegraph.graph.nodes.debug;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;

/*
 * Exists to test Node.Disable annotation - this Node should not appear as an option in the context
 * menu. It also carries a @Node.Type id and alias so NodeRegistry's stable-id resolution has a
 * discovered, annotated fixture to exercise (a disabled type that stays loadable for old saves is
 * exactly the case type ids/aliases exist for).
 */
@Display.Name("DisabledNode - should not appear")
@Node.Disabled
@Node.Type(value = "disabled-fixture", aliases = {"legacy.disabled.fixture.id"})
public class DisabledNode extends BaseNode {
    @Override
    public void process(ProcessContext ctx) {

    }

    @Override
    public void configureInputs() {

    }

    @Override
    public void configureOutputs() {

    }
}
