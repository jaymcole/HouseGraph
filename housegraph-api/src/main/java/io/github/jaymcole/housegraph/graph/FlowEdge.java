package io.github.jaymcole.housegraph.graph;

import java.util.Objects;

/**
 * A control-flow connection from one node's OUT {@link FlowPort} to another node's IN
 * {@link FlowPort}: when the source node finishes executing and fires that specific
 * out-port, the target node is triggered. Unlike {@link Edge}, this carries no data —
 * it's purely an execution-order link.
 * <p>
 * The source port matters because a node may have several out-ports and fire only a
 * subset (see {@link BaseNode#activate}); the engine uses {@link #getSourcePort()} to
 * tell which edges belong to a port that actually fired. The target port matters
 * symmetrically: the engine records it on arrival and the target's {@code process()}
 * reads it back through {@link ProcessContext#triggeredVia()}, so a node with several
 * named entry points can tell which one it was reached through.
 * <p>
 * Several flow edges may target the same IN port — unlike a data input, a flow port has
 * no value and so no ambiguous source to resolve. See {@link NodeGraph}'s class Javadoc.
 */
public class FlowEdge {

    private final BaseNode sourceNode;
    private final FlowPort sourcePort;
    private final BaseNode targetNode;
    private final FlowPort targetPort;

    public FlowEdge(BaseNode sourceNode, FlowPort sourcePort, BaseNode targetNode, FlowPort targetPort) {
        this.sourceNode = Objects.requireNonNull(sourceNode, "sourceNode");
        this.sourcePort = Objects.requireNonNull(sourcePort, "sourcePort");
        this.targetNode = Objects.requireNonNull(targetNode, "targetNode");
        this.targetPort = Objects.requireNonNull(targetPort, "targetPort");
    }

    public BaseNode getSourceNode() {
        return sourceNode;
    }

    public FlowPort getSourcePort() {
        return sourcePort;
    }

    public BaseNode getTargetNode() {
        return targetNode;
    }

    public FlowPort getTargetPort() {
        return targetPort;
    }
}
