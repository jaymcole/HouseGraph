package io.github.jaymcole.housegraph.graph.fixture.liba.nodes.values;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/**
 * One node carrying every persistence flavour at once, so the {@code duplicate} rules can be
 * asserted without reaching for four different real node types:
 * manually-authored (copied), computed (not), secret (not), transient runtime handle (not).
 */
public class ValuesNode extends BaseNode {

    public final NodeVariable<String> authored = new NodeVariable<>("Authored", String.class, true);
    public final NodeVariable<String> computed = new NodeVariable<>("Computed", String.class);
    public final NodeVariable<String> secret = new NodeVariable<String>("Secret", String.class, true).markSecret();
    public final NodeVariable<String> handle = new NodeVariable<String>("Handle", String.class, true).transientValue();

    @Override
    public void configureInputs() {
        addInput(authored);
        addInput(computed);
    }

    @Override
    public void configureOutputs() {
        addOutput(secret);
        addOutput(handle);
    }

    @Override
    public void process(ProcessContext ctx) {
    }
}
