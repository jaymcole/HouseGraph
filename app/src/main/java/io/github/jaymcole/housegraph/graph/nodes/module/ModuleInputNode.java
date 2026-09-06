package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Disabled;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.NodeVariable;

/**
 * Declares a named, typed value the graph receives, and is where that value surfaces inside it.
 *
 * <h2>The direction is inverted, and everyone reads it backwards once</h2>
 * This node carries a single data <b>output</b> port, even though it is called an input. It is an
 * input as seen from outside the graph; from inside, it is a source. A value handed into the
 * graph under this name appears on that output, so the interior wires <em>from</em> it. There is
 * no input port because the value does not come from anywhere in this graph — supplying it is the
 * caller's half of the declaration.
 *
 * <h2>What it declares</h2>
 * A name and a type. The name identifies the value where the graph is used; the type is what the
 * output port is built with, so the interior can only wire this value into somewhere that accepts
 * it. Both survive a save and reload with nothing wired to the node, because the declaration is
 * the node's content, not something inferred from its surroundings. A blank name leaves the node
 * {@linkplain #isMisconfigured() misconfigured}. See {@link ModuleDataBoundaryNode} for how the
 * type is stored and what happens when it is a type this machine does not have.
 *
 * <h2>No flow port</h2>
 * A value is pulled when something needs it, not pushed on a schedule, so there is nothing to
 * trigger here. A control signal crossing the same boundary is a {@link ModuleEntryNode}.
 */
@Display.Name("Module Input")
@Display.Description("Declares a named, typed value this graph receives; its output is where that value appears inside the graph.")
@Kind(NodeKind.DATA)
@Keywords({"module", "input", "parameter", "argument", "in", "receive", "boundary", "subgraph", "interface", "data"})
@Disabled("Boundary marker: nothing consumes a graph as a module yet")
public class ModuleInputNode extends ModuleDataBoundaryNode {

    /**
     * Declares the single data OUT port the received value appears on. The inversion lives here:
     * an <em>input</em> node's own port is an output.
     */
    @Override
    public void configureOutputs() {
        addOutput(newBoundaryPort());
    }

    @Override
    public NodeVariable<?> getBoundaryPort() {
        return getOutputs().get(0);
    }
}
