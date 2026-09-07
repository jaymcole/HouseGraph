package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.FlowPort;

/**
 * Declares a named way <em>out of</em> a graph's control flow: control reaching this node means
 * the graph is finished by that exit.
 *
 * <h2>The direction is inverted, and everyone reads it backwards once</h2>
 * This node carries a single flow <b>IN</b> port and no flow output, even though it is called an
 * exit. It is an exit as seen from outside the graph; from inside, it is a sink. The interior
 * wires <em>into</em> it, and control leaves the graph from there. There is nothing downstream of
 * it to fire, because leaving is what this node <em>is</em>.
 *
 * <h2>Named, because a graph may finish in more than one way</h2>
 * The declared name distinguishes one exit from another — a Found exit and a Not Found exit are
 * the shape of it — which is what lets whatever runs the graph tell which way it ended. A blank
 * name leaves the node {@linkplain #isMisconfigured() misconfigured}, since there is then nothing
 * to identify the exit by. See {@link ModuleBoundaryNode} for why the name is stored verbatim.
 *
 * <h2>Flow and data are declared separately</h2>
 * This node carries no data port. Reaching it says the graph is done, not what it produced; a
 * value crossing the same boundary is a {@link ModuleOutputNode}. Keeping them apart is what lets
 * a graph have two exits and one result, or one exit and three results, without either
 * declaration implying the other.
 */
@Display.Name("Module Exit")
@Display.Description("Declares a named way out of this graph's control flow; reaching it means the graph is done that way.")
@Kind(NodeKind.CONTROL)
@Keywords({"module", "exit", "leave", "end", "finish", "return", "done", "out", "boundary", "subgraph", "interface", "flow"})
public class ModuleExitNode extends ModuleBoundaryNode {

    /**
     * Declares the single flow IN port control arrives at. The inversion lives here: an
     * <em>exit</em> node's own port points inward, receiving from the graph.
     */
    @Override
    public void configureFlowInputs() {
        addFlowInput(new FlowPort("", FlowPort.Direction.IN));
    }
}
