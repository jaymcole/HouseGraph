package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.annotations.Display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

public abstract class BaseNode {

    private NodeGraph graph;
    private NodeProcessingStatus status = NodeProcessingStatus.NOT_STARTED;
    private Throwable lastError;
    private boolean configured = false;

    /**
     * What happens when this node is {@link #execute() triggered} again while a pass it
     * started is still in flight. Only meaningful for entry-point nodes (the ones that
     * actually get {@code execute()}d); inert for pure transform nodes. Read on the
     * triggering thread, so kept {@code volatile}. Defaults to {@link ExecutionPolicy#QUEUE}
     * to preserve the engine's historical "run the next trigger after this one" behavior.
     */
    private volatile ExecutionPolicy executionPolicy = ExecutionPolicy.QUEUE;

    /**
     * Caps how many runs may execute this node's {@link #process()} at once, across all concurrent
     * runs (0 = unlimited). For an expensive node — an LLM call, a rate-limited API, a flaky camera
     * — a limit of 1 serializes it so overlapping runs queue for it rather than hammering it at
     * once. Distinct from {@link ExecutionPolicy} (which is about re-triggering an entry node): this
     * governs a single node's own throughput. The {@link Semaphore} is rebuilt whenever the limit
     * changes; {@code null} means unlimited.
     */
    private volatile int maxConcurrency = 0;
    private volatile Semaphore concurrencyLimiter;

    /**
     * How long this node's {@link #process()} may run before the engine interrupts it and marks the
     * node {@code FAILED} with a {@link java.util.concurrent.TimeoutException} (0 = no timeout, in
     * milliseconds). Meant for nodes that call out to something that can hang — a camera, an LLM,
     * an HTTP API. Cooperative: it interrupts the thread, so it only aborts a {@code process()} that
     * honors interruption (a blocking call that ignores it won't stop, same limit as RESTART).
     */
    private volatile long timeoutMillis = 0;

    private final List<NodeVariable> inputs = new ArrayList<>();
    private final List<NodeVariable> outputs = new ArrayList<>();
    private final List<FlowPort> flowInputs = new ArrayList<>();
    private final List<FlowPort> flowOutputs = new ArrayList<>();

    private Runnable portsChangedListener = () -> {
    };

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
     * Rebuilds the input/output/flow-port lists from the node's current configuration —
     * for nodes whose ports depend on editable settings (e.g. a command node whose
     * outputs mirror its declared options). Discards the existing ports and re-runs the
     * configure hooks, so those hooks must read the node's current settings.
     */
    public void reconfigure() {
        inputs.clear();
        outputs.clear();
        flowInputs.clear();
        flowOutputs.clear();
        configured = false;
        ensureConfigured();
    }

    /**
     * Set by the UI so a node can ask its on-canvas view to rebuild after its ports change.
     *
     * @param listener the callback to run when this node's ports change, or null to clear it
     */
    public void setPortsChangedListener(Runnable listener) {
        this.portsChangedListener = listener == null ? () -> {
        } : listener;
    }

    /**
     * {@link #reconfigure() Reconfigures} this node's ports and asks its view to rebuild
     * (edges to surviving ports are reconnected by name/position). A node calls this
     * after a settings change that alters its ports.
     */
    protected void rebuildPorts() {
        reconfigure();
        portsChangedListener.run();
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

    /**
     * Like {@link #execute()}, but {@code prepare} runs on the execution thread at the
     * start of the pass — for an event-source node to set its outputs from the triggering
     * event's data, captured per-trigger so a burst of events can't overwrite each
     * other's values. See {@link NodeGraph#execute(BaseNode, Runnable)}.
     *
     * @param prepare work run on the execution thread at the start of the pass, before this node fires
     */
    protected void execute(Runnable prepare) {
        requireGraph().execute(this, prepare);
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
     * Called once when this node becomes part of a live graph (added to a
     * {@link NodeGraph}). A no-op for ordinary transform nodes; a node that owns a
     * long-lived resource can use it to hook up (e.g. subscribe to something). Note
     * this fires on load too, as each saved node is re-added. It is <em>not</em> where a
     * connection should be opened — a resource's liveness is user-driven (a Connect
     * button), not tied to being on the canvas.
     */
    protected void onActivated() {
    }

    /**
     * Called once when this node leaves a live graph — deleted, replaced by a load, or
     * on app shutdown ({@link NodeGraph#dispose()}). This is the place to release
     * anything long-lived (timers, sockets, threads) so it can't leak or keep running
     * as a zombie. Must be idempotent and safe even if the node's UI was never built.
     */
    protected void onRemoved() {
    }

    /**
     * Called by {@link NodeGraph} right after a data edge whose target is this node is
     * registered. No-op by default; a node whose ports depend on what's wired into it
     * (e.g. the object decomposer) overrides this to grow its outputs from the newly
     * connected source's type. Runs on whatever thread performed the wiring (the UI
     * thread for user edits), outside the graph's structural lock.
     *
     * @param edge the data edge that was just wired into this node
     */
    protected void onInputEdgeAdded(Edge edge) {
    }

    /**
     * Called by {@link NodeGraph} right after a data edge whose target is this node is
     * removed (an explicit disconnect, a replaced input, the source node being deleted,
     * or a view rebuild). No-op by default; the counterpart to {@link #onInputEdgeAdded}.
     *
     * @param edge the data edge that was just removed from this node
     */
    protected void onInputEdgeRemoved(Edge edge) {
    }

    /**
     * The data edges currently feeding this node, or empty if it isn't in a graph. Lets a
     * node that reacts to its wiring (see {@link #onInputEdgeAdded}) read its <em>current</em>
     * inputs rather than trust a single hook's edge argument — the reliable choice when the
     * hooks are dispatched asynchronously and a rebuild may briefly churn edges.
     *
     * @return this node's current incoming data edges, or empty if it isn't in a graph
     */
    protected Set<Edge> getIncomingDataEdges() {
        return graph == null ? Set.of() : graph.getIncomingDataEdges(this);
    }

    /**
     * The node's {@link NodeVariable#required() required} inputs that currently have no value
     * source — no incoming data edge and no non-null manually-authored value. An empty list means
     * the node is configured; a non-empty one means it's <em>misconfigured</em> and the UI flags it
     * (see the node view). Pure, JavaFX-free logic so it stays headless-testable; evaluated against
     * the node's current wiring ({@link #getIncomingDataEdges()}) and authored values. Meant to be
     * called outside a run (on the UI thread), where {@link NodeVariable#getValue()} returns the
     * authored value rather than a run's computed overlay.
     *
     * @return the required inputs lacking a value source, empty if the node is configured
     */
    public List<NodeVariable> getUnsatisfiedRequiredInputs() {
        Set<NodeVariable> fedByEdge = new java.util.HashSet<>();
        for (Edge edge : getIncomingDataEdges()) {
            fedByEdge.add(edge.getTargetVariable());
        }
        List<NodeVariable> unsatisfied = new ArrayList<>();
        for (NodeVariable input : getInputs()) {
            if (!input.isRequired()) {
                continue;
            }
            boolean satisfied = fedByEdge.contains(input) || input.getValue() != null;
            if (!satisfied) {
                unsatisfied.add(input);
            }
        }
        return unsatisfied;
    }

    /**
     * Whether this node has any {@link NodeVariable#required() required} input without a value
     * source — i.e. it can't run as configured. Shorthand for
     * {@code !getUnsatisfiedRequiredInputs().isEmpty()}.
     *
     * @return true if a required input is unsatisfied
     */
    public boolean isMisconfigured() {
        return !getUnsatisfiedRequiredInputs().isEmpty();
    }

    /**
     * Node-specific configuration to persist in a save file, beyond input/output
     * values — e.g. a dropdown selection or a chosen key. Empty by default. The values
     * are stored verbatim, so this must never contain a secret (persist the reference,
     * not the secret; see {@link NodeVariable#markSecret()}).
     *
     * @return this node's persistable configuration, empty by default
     */
    public Map<String, String> saveState() {
        return Map.of();
    }

    /**
     * Restores what {@link #saveState()} produced when a graph is loaded. No-op by default.
     *
     * @param state the previously saved configuration to restore
     */
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
     * @param port one of this node's own flow-out ports to fire when control cascades out
     * @throws IllegalArgumentException if {@code port} isn't one of this node's own flow-out ports
     */
    protected void activate(FlowPort port) {
        if (!getFlowOutputs().contains(port)) {
            throw new IllegalArgumentException(getName() + " tried to activate a flow port it doesn't own");
        }
        // Recorded on the current run's context (bound by the engine while process() runs), so
        // concurrent runs of the same node don't share activation. activate() is only ever
        // called from within process(), where a context is always bound.
        ExecutionContext context = ExecutionContext.current();
        if (context != null) {
            context.activate(this, port);
        }
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

    /**
     * The node's display name: {@link Display.Name#value()} if the class is annotated with it, else the simple class name.
     *
     * @return this node's display name
     */
    public String getName() {
        Display.Name displayName = getClass().getAnnotation(Display.Name.class);
        if (displayName != null && !displayName.value().isBlank()) {
            return displayName.value();
        }
        return getClass().getSimpleName();
    }

    /**
     * The exception from the most recent failed process() call, or null if it last succeeded (or hasn't run).
     *
     * @return the last process() failure, or null
     */
    public Throwable getLastError() {
        return lastError;
    }

    /**
     * How re-triggering this node while a pass is in flight is handled — see
     * {@link ExecutionPolicy}. Never null.
     *
     * @return this node's execution policy
     */
    public ExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    /**
     * Sets how re-triggering this node while a pass is in flight is handled.
     *
     * @param executionPolicy the policy to apply; null resets to {@link ExecutionPolicy#QUEUE}
     */
    public void setExecutionPolicy(ExecutionPolicy executionPolicy) {
        this.executionPolicy = executionPolicy == null ? ExecutionPolicy.QUEUE : executionPolicy;
    }

    /**
     * The most runs allowed to execute this node's {@link #process()} at once, across all runs;
     * 0 means unlimited. See the field.
     *
     * @return this node's concurrency limit, or 0 for unlimited
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /**
     * Sets the most runs allowed to execute this node's {@link #process()} at once (clamped to
     * &ge; 0; 0 = unlimited). Rebuilds the underlying permit semaphore.
     *
     * @param max the concurrency cap, or 0 for unlimited
     */
    public void setMaxConcurrency(int max) {
        int limit = Math.max(0, max);
        this.maxConcurrency = limit;
        this.concurrencyLimiter = limit == 0 ? null : new Semaphore(limit, true);
    }

    /** The permit semaphore enforcing {@link #getMaxConcurrency()}, or null when unlimited. Package-private: the engine acquires around {@code process()}. */
    Semaphore concurrencyLimiter() {
        return concurrencyLimiter;
    }

    /**
     * How long this node's {@link #process()} may run before the engine aborts it (0 = no timeout),
     * in milliseconds. See the field.
     *
     * @return this node's process timeout in milliseconds, or 0 for none
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Sets how long this node's {@link #process()} may run before the engine interrupts it and marks
     * it FAILED (clamped to &ge; 0; 0 = no timeout).
     *
     * @param millis the timeout in milliseconds, or 0 for none
     */
    public void setTimeoutMillis(long millis) {
        this.timeoutMillis = Math.max(0, millis);
    }

    /**
     * Whether this node can be an <em>execution entry point</em> — something calls
     * {@link #execute()} on it directly (a trigger button, a timer, an inbound event),
     * rather than it only running when reached along an incoming flow edge. Only entry
     * points can be re-triggered while a pass is in flight, so only they have a meaningful
     * {@link ExecutionPolicy}; the UI surfaces the policy selector for these nodes alone.
     * <p>
     * The default covers the common case structurally: a node with a flow output but no
     * flow input can <em>only</em> ever run via a direct {@code execute()} (nothing can
     * cascade into it). A node that is both flow-triggerable and self-triggering — one
     * with a flow input that also kicks itself off (e.g. a "Discover" button) — overrides
     * this to return {@code true}.
     *
     * @return true if this node initiates its own execution
     */
    public boolean isExecutionEntryPoint() {
        return !getFlowOutputs().isEmpty() && getFlowInputs().isEmpty();
    }

    /**
     * Whether this node is a <em>flow join</em> (an AND-barrier): it fires only once <em>all</em>
     * its wired incoming flow edges have arrived in a run, rather than on the first arrival like an
     * ordinary node. Default {@code false}. Because a run is fire-and-forget, reconverging parallel
     * branches needs this to wait for every branch (see {@link NodeGraph}). A join whose incoming
     * branches don't all fire in a run — e.g. one was pruned by an {@code If} — simply doesn't fire
     * that run (it's an AND); the run still quiesces.
     *
     * @return true if this node waits for all its incoming flow edges before firing
     */
    public boolean isFlowJoin() {
        return false;
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
