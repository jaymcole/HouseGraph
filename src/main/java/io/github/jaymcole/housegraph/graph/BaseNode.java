package io.github.jaymcole.housegraph.graph;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseNode {

    private NodeProcessingStatus status;

    public BaseNode() {
        status = NodeProcessingStatus.NOT_STARTED;
        inputs = new ArrayList<>();
        outputs = new ArrayList<>();
        configureInputs();
        configureOutputs();
    }

    public void beginProcessing() {
        if(status.isComplete()) {
            return;
        }

        for(Edge edge : Graph.getLeftEdges(this)) {
            if (!edge.getLeftNode().status.isComplete()) {
                edge.getLeftNode().beginProcessing();
            }
        }

        try {
            process();
            status = NodeProcessingStatus.SUCCESS;
        } catch (Exception e) {
            status = NodeProcessingStatus.FAILED;
        }

        for(Edge edge : Graph.getRightEdges(this)) {
            if (edge.getRightNode().status != NodeProcessingStatus.WAITING_FOR_UPSTREAM) {
                edge.getLeftNode().beginProcessing();
            }
        }
    }

    public abstract void process();
    public abstract void configureInputs();
    public abstract void configureOutputs();

    protected void addInput(NodeVariable variable) {
        if (inputs == null) {
            inputs = new ArrayList<>();
        }
        inputs.add(variable);
    }

    protected void addOutput(NodeVariable variable) {
        if (inputs == null) {
            outputs = new ArrayList<>();
        }
        outputs.add(variable);
    }

    private List<NodeVariable> inputs;
    private List<NodeVariable> outputs;

}
