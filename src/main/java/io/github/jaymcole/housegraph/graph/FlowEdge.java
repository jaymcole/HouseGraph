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
 * tell which edges belong to a port that actually fired. The target port is carried
 * symmetrically so a node could later distinguish which entry point it was reached
 * through, though nothing depends on that yet.
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
