package io.github.jaymcole.housegraph.graph;

import java.util.Objects;

/**
 * A data connection from one node's output variable to another node's input
 * variable. Registering it with a {@link NodeGraph} is what causes the target
 * variable's value to be overwritten with the source variable's value whenever
 * the graph resolves.
 */
public class Edge {

    private final BaseNode sourceNode;
    private final NodeVariable sourceVariable;
    private final BaseNode targetNode;
    private final NodeVariable targetVariable;

    public Edge(BaseNode sourceNode, NodeVariable sourceVariable, BaseNode targetNode, NodeVariable targetVariable) {
        this.sourceNode = Objects.requireNonNull(sourceNode, "sourceNode");
        this.sourceVariable = Objects.requireNonNull(sourceVariable, "sourceVariable");
        this.targetNode = Objects.requireNonNull(targetNode, "targetNode");
        this.targetVariable = Objects.requireNonNull(targetVariable, "targetVariable");
    }

    public BaseNode getSourceNode() {
        return sourceNode;
    }

    public NodeVariable getSourceVariable() {
        return sourceVariable;
    }

    public BaseNode getTargetNode() {
        return targetNode;
    }

    public NodeVariable getTargetVariable() {
        return targetVariable;
    }
}
