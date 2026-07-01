package io.github.jaymcole.housegraph.graph;

import java.util.Objects;

/**
 * A control-flow connection between two nodes: when the source node finishes
 * executing, the target node is triggered. Unlike {@link Edge}, this carries no
 * data — it's purely an execution-order link.
 */
public class FlowEdge {

    private final BaseNode sourceNode;
    private final BaseNode targetNode;

    public FlowEdge(BaseNode sourceNode, BaseNode targetNode) {
        this.sourceNode = Objects.requireNonNull(sourceNode, "sourceNode");
        this.targetNode = Objects.requireNonNull(targetNode, "targetNode");
    }

    public BaseNode getSourceNode() {
        return sourceNode;
    }

    public BaseNode getTargetNode() {
        return targetNode;
    }
}
