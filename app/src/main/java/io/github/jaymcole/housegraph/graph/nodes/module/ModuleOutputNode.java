package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.NodeVariable;

/**
 * Declares a named, typed value the graph produces, and is where the interior hands that value
 * over.
 *
 * <h2>The direction is inverted, and everyone reads it backwards once</h2>
 * This node carries a single data <b>input</b> port, even though it is called an output. It is an
 * output as seen from outside the graph; from inside, it is a sink. The interior wires
 * <em>into</em> it, and the value leaves the graph from there. There is no output port because
 * nothing in this graph reads the value back — consuming it is the caller's half of the
 * declaration.
 *
 * <h2>What it declares</h2>
 * A name and a type, exactly as {@link ModuleInputNode} does; only the side the port sits on
 * differs. Both survive a save and reload with nothing wired to the node. A blank name leaves the
 * node {@linkplain #isMisconfigured() misconfigured}. See {@link ModuleDataBoundaryNode} for how
 * the type is stored and what happens when it is a type this machine does not have.
 * <p>
 * The input is deliberately <em>not</em> marked required. A graph whose result is genuinely
 * optional on some paths is ordinary, and flagging every unwired result as broken would make the
 * signal meaningless; the missing declaration a boundary marker really can detect is a blank
 * name.
 *
 * <h2>No flow port</h2>
 * Reaching this node is not what makes the value available — resolving it is. Its value is pulled
 * through the incoming edge like any other data dependency, and the engine commits the resolved
 * input after {@code process()}, which is what makes it readable afterwards. Saying that the
 * graph has <em>finished</em> is a separate declaration, and a separate node: {@link
 * ModuleExitNode}.
 */
@Display.Name("Module Output")
@Display.Description("Declares a named, typed value this graph produces; wire the interior into its input.")
@Kind(NodeKind.DATA)
@Keywords({"module", "output", "result", "return", "out", "produce", "boundary", "subgraph", "interface", "data"})
public class ModuleOutputNode extends ModuleDataBoundaryNode {

    /**
     * Declares the single data IN port the produced value arrives on. The inversion lives here:
     * an <em>output</em> node's own port is an input.
     */
    @Override
    public void configureInputs() {
        addInput(newBoundaryPort());
    }

    @Override
    public NodeVariable<?> getBoundaryPort() {
        return getInputs().get(0);
    }
}
