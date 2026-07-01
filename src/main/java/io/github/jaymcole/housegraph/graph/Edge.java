package io.github.jaymcole.housegraph.graph;

public class Edge {

    private BaseNode leftNode;
    private NodeVariable leftVariable;
    private BaseNode rightNode;
    private NodeVariable rightVariable;

    public Edge(BaseNode leftNode, NodeVariable leftVariable, BaseNode rightNode, NodeVariable rightVariable) {
        this.leftNode = leftNode;
        this.leftVariable = leftVariable;
        this.rightNode = rightNode;
        this.rightVariable = rightVariable;
    }

    public BaseNode getLeftNode() {
        return this.leftNode;
    }

    public NodeVariable getLeftVariable() {
        return leftVariable;
    }

    public BaseNode getRightNode() {
        return rightNode;
    }

    public NodeVariable getRightVariable() {
        return rightVariable;
    }

}
