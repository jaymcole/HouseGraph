package io.github.jaymcole.housegraph.graph;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseNode {

    private NodeProcessingStatus status;

    public BaseNode() {
        status = NodeProcessingStatus.NOT_STARTED;
        inputs = new ArrayList<>();
        outputs = new ArrayList<>();
    }

    /**
     * configureInputs()/configureOutputs() are deferred until first use rather than
     * called from the constructor, since a subclass's field initializers (e.g. the
     * NodeVariable fields they pass to addInput/addOutput) haven't run yet while the
     * BaseNode constructor is executing.
     */
    private void ensureConfigured() {
        if (!configured) {
            configured = true;
            configureInputs();
            configureOutputs();
        }
    }

    public void beginProcessing() {
        ensureConfigured();
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

    public List<NodeVariable> getInputs() {
        ensureConfigured();
        return inputs;
    }

    public List<NodeVariable> getOutputs() {
        ensureConfigured();
        return outputs;
    }

    public String getName() {
        return getClass().getSimpleName();
    }

    private boolean configured = false;
    private List<NodeVariable> inputs;
    private List<NodeVariable> outputs;

}
