package io.github.jaymcole.housegraph.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class BaseNode {

    private NodeGraph graph;
    private NodeProcessingStatus status = NodeProcessingStatus.NOT_STARTED;
    private Throwable lastError;
    private boolean configured = false;
    private final List<NodeVariable> inputs = new ArrayList<>();
    private final List<NodeVariable> outputs = new ArrayList<>();

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

    /**
     * Pulls a fresh value through this node's incoming data edges (recursively
     * resolving upstream nodes first) and runs process(). Safe to call directly on
     * any node, independent of flow wiring. Requires the node to have been added to
     * a {@link NodeGraph} first.
     */
    public void beginProcessing() {
        requireGraph().resolve(this);
    }

    /**
     * Triggers this node the same way {@link #beginProcessing()} does, then cascades
     * along any outgoing {@link FlowEdge}s to trigger downstream flow-connected
     * nodes. This is the entry point for flow-driven execution (e.g. a TriggerNode
     * button), as opposed to beginProcessing()'s pull-only model.
     */
    public void execute() {
        requireGraph().execute(this);
    }

    private NodeGraph requireGraph() {
        if (graph == null) {
            throw new IllegalStateException(getName() + " has not been added to a NodeGraph yet");
        }
        return graph;
    }

    public abstract void process();
    public abstract void configureInputs();
    public abstract void configureOutputs();

    protected void addInput(NodeVariable variable) {
        inputs.add(variable);
    }

    protected void addOutput(NodeVariable variable) {
        outputs.add(variable);
    }

    public List<NodeVariable> getInputs() {
        ensureConfigured();
        return Collections.unmodifiableList(inputs);
    }

    public List<NodeVariable> getOutputs() {
        ensureConfigured();
        return Collections.unmodifiableList(outputs);
    }

    public String getName() {
        return getClass().getSimpleName();
    }

    /** The exception from the most recent failed process() call, or null if it last succeeded (or hasn't run). */
    public Throwable getLastError() {
        return lastError;
    }

    // Package-private: only NodeGraph (same package) drives node execution state.

    NodeGraph getGraph() {
        return graph;
    }

    void setGraph(NodeGraph graph) {
        this.graph = graph;
    }

    NodeProcessingStatus getStatus() {
        return status;
    }

    void setStatus(NodeProcessingStatus status) {
        this.status = status;
    }

    void setLastError(Throwable lastError) {
        this.lastError = lastError;
    }

}
