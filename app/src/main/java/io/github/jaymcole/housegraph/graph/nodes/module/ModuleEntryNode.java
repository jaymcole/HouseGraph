package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.FlowPort;

/**
 * Declares a named way <em>into</em> a graph's control flow, and is where control that arrives
 * that way emerges inside it.
 *
 * <h2>The direction is inverted, and everyone reads it backwards once</h2>
 * This node carries a single flow <b>OUT</b> port and no flow input, even though it is called an
 * entry. It is an entry as seen from outside the graph; from inside, it is a source. Control
 * arriving under this name emerges here and cascades into the graph from this node, so the
 * interior wires <em>away from</em> it. There is nothing for a flow edge to arrive at, because
 * arrival is what this node <em>is</em>.
 * <p>
 * The consequence is that this reads as an execution entry point by the ordinary structural rule
 * — a flow output with no flow input — with no special-casing needed.
 *
 * <h2>Named, because a graph may have several</h2>
 * The declared name distinguishes one entry from another; a graph with a Start entry and a Stop
 * entry is the shape of it. A blank name leaves the node {@linkplain #isMisconfigured()
 * misconfigured}, since there is then nothing to identify the entry by. See
 * {@link ModuleBoundaryNode} for why the name is stored verbatim.
 *
 * <h2>Flow and data are declared separately</h2>
 * This node carries no data port. A value crossing the same boundary is a {@link ModuleInputNode}
 * — a separate marker, because a control signal carries no value and a value implies no ordering.
 */
@Display.Name("Module Entry")
@Display.Description("Declares a named way into this graph's control flow; control arriving that way emerges here.")
@Kind(NodeKind.CONTROL)
@Keywords({"module", "entry", "enter", "start", "begin", "in", "boundary", "subgraph", "interface", "flow"})
public class ModuleEntryNode extends ModuleBoundaryNode {

    /**
     * Declares the single flow OUT port control emerges from. The inversion lives here: an
     * <em>entry</em> node's own port points outward, into the graph.
     */
    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }
}
