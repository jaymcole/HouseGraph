package io.github.jaymcole.housegraph.graph.fixture.liba.nodes.dup;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/**
 * Shares its simple name — and therefore its default type id — with the class of the same name in
 * {@code fixture.libb.nodes.dup}. Two independently-written node libraries can absolutely do this,
 * and the registry has to resolve it by owning library rather than guessing.
 */
public class SharedNameNode extends BaseNode {

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
