package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.annotations.Display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseNode {

    private NodeGraph graph;
    private NodeProcessingStatus status = NodeProcessingStatus.NOT_STARTED;
    private Throwable lastError;
    private boolean configured = false;
    private final List<NodeVariable> inputs = new ArrayList<>();
    private final List<NodeVariable> outputs = new ArrayList<>();
    private final List<FlowPort> flowInputs = new ArrayList<>();
    private final List<FlowPort> flowOutputs = new ArrayList<>();

    /**
     * Which flow-out ports {@link #process()} chose to fire this pass. Written by the
     * thread running this node's process() and read afterward by the engine when it
     * decides which branches to cascade into - possibly a different thread (a node can
     * be resolved as another node's data dependency before it's reached via flow), so
     * this is a concurrent set for its cross-thread happens-before guarantees.
     */
    private final Set<FlowPort> activatedOutputs = ConcurrentHashMap.newKeySet();

    /**
     * configureInputs()/configureOutputs()/configureFlow*() are deferred until first
     * use rather than called from the constructor, since a subclass's field
     * initializers (e.g. the NodeVariable/FlowPort fields they pass to addInput/
     * addFlowOutput) haven't run yet while the BaseNode constructor is executing.
     */
    private void ensureConfigured() {
        if (!configured) {
            configured = true;
            configureInputs();
            configureOutputs();
            configureFlowInputs();
            configureFlowOutputs();
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

    /**
     * Override to declare this node's control-flow entry point(s) via {@link #addFlowInput}.
     * Default: none - the node can't be triggered along a {@link FlowEdge} (it can still
     * be pulled as a data dependency). Most executable nodes add a single unnamed port.
     */
    public void configureFlowInputs() {
    }

    /**
     * Override to declare this node's control-flow exit point(s) via {@link #addFlowOutput}.
     * Default: none. A plain node adds one unnamed port; a branch/decider node adds
     * several named ports and picks between them at runtime with {@link #activate}.
     */
    public void configureFlowOutputs() {
    }

    /**
     * Called by {@link NodeGraph} right after this node finishes a process() attempt
     * (success or failure — check {@link #getLastError()} if it matters). No-op by
     * default; a node can override it to react to its own values changing, e.g. a
     * node with a custom UI (see {@code io.github.jaymcole.housegraph.ui.NodeContentProvider})
     * pushing a freshly-computed value into a Label it built.
     */
    protected void onExecuted() {
    }

    /**
     * Node-specific configuration to persist in a save file, beyond input/output
     * values — e.g. a dropdown selection or a chosen key. Empty by default. The values
     * are stored verbatim, so this must never contain a secret (persist the reference,
     * not the secret; see {@link NodeVariable#markSecret()}).
     */
    public Map<String, String> saveState() {
        return Map.of();
    }

    /** Restores what {@link #saveState()} produced when a graph is loaded. No-op by default. */
    public void loadState(Map<String, String> state) {
    }

    protected void addInput(NodeVariable variable) {
        inputs.add(variable);
    }

    protected void addOutput(NodeVariable variable) {
        outputs.add(variable);
    }

    protected void addFlowInput(FlowPort port) {
        flowInputs.add(port);
    }

    protected void addFlowOutput(FlowPort port) {
        flowOutputs.add(port);
    }

    /**
     * From within {@link #process()}, marks one of this node's flow-out ports to fire
     * when control cascades out of the node. A node that activates <em>no</em> port
     * fires <em>all</em> its flow-out ports (so ordinary nodes need no activation call
     * and keep triggering everything downstream, exactly as before flow ports could
     * branch); a node that activates one or more ports fires only those. See
     * {@link NodeGraph}'s cascade logic.
     *
     * @throws IllegalArgumentException if {@code port} isn't one of this node's own flow-out ports
     */
    protected void activate(FlowPort port) {
        if (!getFlowOutputs().contains(port)) {
            throw new IllegalArgumentException(getName() + " tried to activate a flow port it doesn't own");
        }
        activatedOutputs.add(port);
    }

    public List<NodeVariable> getInputs() {
        ensureConfigured();
        return Collections.unmodifiableList(inputs);
    }

    public List<NodeVariable> getOutputs() {
        ensureConfigured();
        return Collections.unmodifiableList(outputs);
    }

    public List<FlowPort> getFlowInputs() {
        ensureConfigured();
        return Collections.unmodifiableList(flowInputs);
    }

    public List<FlowPort> getFlowOutputs() {
        ensureConfigured();
        return Collections.unmodifiableList(flowOutputs);
    }

    /** The node's display name: {@link Display.Name#value()} if the class is annotated with it, else the simple class name. */
    public String getName() {
        Display.Name displayName = getClass().getAnnotation(Display.Name.class);
        if (displayName != null && !displayName.value().isBlank()) {
            return displayName.value();
        }
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

    /** The flow-out ports process() chose to fire this pass; empty means "fire all" - see {@link #activate}. */
    Set<FlowPort> getActivatedOutputs() {
        return activatedOutputs;
    }

    /** Cleared by the engine before each process() so activation never leaks across passes. */
    void clearActivatedOutputs() {
        activatedOutputs.clear();
    }

    void setLastError(Throwable lastError) {
        this.lastError = lastError;
    }

}
