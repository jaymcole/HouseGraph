package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.sdk.NodePresentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

public abstract class BaseNode {

    /** Name of the engine-owned error flow-out every node carries. See {@link #getErrorFlowPort()}. */
    public static final String ERROR_FLOW_PORT_NAME = "Error";

    /** Name of the engine-owned error-message output every node carries. See {@link #getErrorMessageOutput()}. */
    public static final String ERROR_MESSAGE_OUTPUT_NAME = "Error Message";

    private NodeGraph graph;
    private NodeProcessingStatus status = NodeProcessingStatus.NOT_STARTED;
    private Throwable lastError;
    private boolean configured = false;

    /**
     * What happens when this node is re-entered while work it started is still in flight. Applied at
     * two scopes (see {@link ExecutionPolicy} and {@link NodeGraph}): if this node is an
     * <em>execution entry point</em>, it gates a whole re-triggered run; if it's reached
     * <em>mid-cascade</em> along a flow edge, it gates re-entry of this node's own {@code process()}
     * across concurrent runs. Meaningful for any node a run flows through; inert for a pure data node
     * (one with no flow ports). Read on triggering/firing threads, so kept {@code volatile}. Defaults
     * to {@link ExecutionPolicy#QUEUE} — an entry re-trigger runs after the current one, and a
     * mid-cascade node processes one run at a time (opt into {@link ExecutionPolicy#PARALLEL} to let
     * concurrent runs overlap on it).
     */
    private volatile ExecutionPolicy executionPolicy = ExecutionPolicy.QUEUE;

    /**
     * What happens to the cascade when this node's {@code process()} fails — see
     * {@link FailurePolicy}. Read on firing threads, so kept {@code volatile}. Defaults to
     * {@link FailurePolicy#HALT}: a failed node fires {@link #getErrorFlowPort() its Error port}
     * rather than its ordinary flow-outs, so a branch does not continue against the values of a
     * step that did not work.
     */
    private volatile FailurePolicy failurePolicy = FailurePolicy.HALT;

    /**
     * The engine-owned flow-out fired instead of this node's ordinary flow-outs when it fails under
     * {@link FailurePolicy#HALT}. Every node has one; see {@link #getErrorFlowPort()} for why it is
     * the engine's rather than each node author's.
     */
    private final FlowPort errorFlowPort = new FlowPort(ERROR_FLOW_PORT_NAME, FlowPort.Direction.OUT);

    /**
     * The engine-owned data output carrying the message of the failure that fired
     * {@link #errorFlowPort}, so a handler can report what went wrong without the node author
     * having to plumb it. Transient and never persisted — it describes one run, not the node.
     */
    private final NodeVariable<String> errorMessage =
            new NodeVariable<String>(ERROR_MESSAGE_OUTPUT_NAME, String.class).transientValue();

    /**
     * Caps how many runs may execute this node's {@link #process(ProcessContext) process()} at once, across all concurrent
     * runs (0 = unlimited). For an expensive node — an LLM call, a rate-limited API, a flaky camera
     * — a limit of 1 serializes it so overlapping runs queue for it rather than hammering it at
     * once. Distinct from {@link ExecutionPolicy} (which is about re-triggering an entry node): this
     * governs a single node's own throughput. The {@link Semaphore} is rebuilt whenever the limit
     * changes; {@code null} means unlimited.
     */
    private volatile int maxConcurrency = 0;
    private volatile Semaphore concurrencyLimiter;

    /**
     * How long this node's {@link #process(ProcessContext) process()} may run before the engine interrupts it and marks the
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

    /**
     * Runs the flow branch hanging off one of this node's OUT {@link FlowPort}s once, to
     * completion, in a fresh isolated run, blocking until that sub-run quiesces. {@code seed}
     * runs first in the sub-run's context to set this node's per-iteration output values (via the
     * usual {@code output.setValue(...)}), and this node is pre-marked complete there so the body
     * pulls those seeded values without re-running this node's {@code process()}.
     * <p>
     * This is how a loop node fires a "body" flow output once per item: call it in a loop from
     * {@code process()}, each call an isolated run so the body executes afresh for every item
     * (the ordinary cascade fires each downstream node only once per run). Iterations run
     * sequentially — each call returns only after its body subtree has fully finished. See
     * {@link NodeGraph#runFlowBranchToCompletion} and {@code ForEachNode}.
     *
     * @param port the OUT flow port whose downstream branch to run; must belong to this node
     * @param seed work run in the sub-context to set this node's per-iteration outputs
     */
    protected void runFlowBranchToCompletion(FlowPort port, Runnable seed) {
        if (!getFlowOutputs().contains(port)) {
            throw new IllegalArgumentException(getName() + " tried to run a flow branch on a port it doesn't own");
        }
        requireGraph().runFlowBranchToCompletion(this, port, seed);
    }

    /**
     * The graph this node belongs to, or null while it belongs to none.
     *
     * <h4>What it is for</h4>
     * A node whose work is to stand up and drive a <em>second</em> {@link NodeGraph} — a module node
     * running the graph it references — has to give that graph the parts of its host's setup that do
     * not cross a graph boundary by themselves: the
     * {@linkplain NodeGraph#getCallbackExecutor() callback executor} (a fresh graph dispatches
     * inline, which in the app is not the FX thread), the
     * {@linkplain NodeGraph#getStepDelayMillis() step delay} (per-graph, so a watched run goes
     * opaque inside a graph that did not inherit it) and the
     * {@linkplain NodeGraph#getReleaseTimeout() release timeout}. There is no other way to reach
     * them, and every one of them is wrong by default rather than merely absent.
     * <p>
     * It is not a hook for reaching around the engine. Trigger through {@link #execute()}, pull
     * through {@link #beginProcessing()}, and loop through {@link #runFlowBranchToCompletion}; those
     * exist so a node does not need this.
     *
     * @return the owning graph, or null if this node has not been added to one
     */
    protected final NodeGraph getOwningGraph() {
        return graph;
    }

    private NodeGraph requireGraph() {
        if (graph == null) {
            throw new IllegalStateException(getName() + " has not been added to a NodeGraph yet");
        }
        return graph;
    }

    /**
     * The node's actual work, run once per pass after its inputs have been resolved. Read inputs and
     * write outputs through the node's {@link NodeVariable}s (directly, or via {@code ctx}); branch
     * with {@link #activate(FlowPort)} and loop with {@link #runFlowBranchToCompletion}.
     * <p>
     * The {@link ProcessContext} adds cooperative <b>cancellation</b> — a long-running or looping
     * {@code process()} should poll {@link ProcessContext#checkCancelled()} so a superseding
     * {@link ExecutionPolicy#RESTART} or an elapsed {@link #getTimeoutMillis() timeout} can stop it
     * (without it, cancellation only takes effect between nodes) — plus null-safe input reads
     * ({@link ProcessContext#get(NodeVariable, Object)}). A node that ignores {@code ctx} entirely
     * still runs correctly; a throwing {@code process()} is caught and marks the node FAILED.
     * <p>
     * A node with more than one flow-in port tells them apart through
     * {@link ProcessContext#wasTriggeredVia(FlowPort)} — the entry-side mirror of {@link #activate}'s
     * exit-side choice — which is what lets one node carry, say, a Start port and a Stop port. It
     * reads as empty when nothing arrived along a flow edge (a {@link #beginProcessing()} pull, a
     * data-dependency resolve, or the node a run was triggered on), so a single-flow-in node needs no
     * changes and can ignore it. If such a port must arm/disarm the node without also firing its own
     * flow-out, call {@link #activateNone()} for that firing rather than leaving {@code activate}
     * uncalled (which fires every out-port).
     *
     * @param ctx this invocation's context: cancellation checks, null-safe value accessors, and the
     *            flow-in ports control arrived through
     */
    public abstract void process(ProcessContext ctx);
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
     * node with a custom UI (see {@link io.github.jaymcole.housegraph.sdk.NodeContentProvider})
     * pushing a freshly-computed value into a Label it built.
     * <p>
     * Dispatched through {@code NodeGraph}'s callback executor, not called directly on the
     * execution thread — so in the app it arrives on the FX thread, and headless it runs on
     * the calling thread.
     */
    protected void onExecuted() {
    }

    // --- Presentation seam --------------------------------------------------------

    /**
     * The sink this node's inline-UI updates go through, or null when nothing is drawing this
     * node. Written by the host when it builds or discards the node's content and read from
     * timer, engine and UI threads alike, hence {@code volatile}.
     */
    private volatile NodePresentation presentation;

    /**
     * Installs (or, with null, clears) the sink this node's {@link #present(Runnable)} updates
     * run through. <b>Called by the host that draws the node</b>, around
     * {@link io.github.jaymcole.housegraph.sdk.NodeContentProvider#createNodeContent()} — node
     * authors call {@link #present(Runnable)} instead and never touch this.
     *
     * @param presentation the sink to route UI updates through, or null when this node has no view
     */
    public final void setPresentation(NodePresentation presentation) {
        this.presentation = presentation;
    }

    /**
     * Whether anything is currently drawing this node — that is, whether
     * {@link io.github.jaymcole.housegraph.sdk.NodeContentProvider#createNodeContent()} has run
     * and its controls are still on screen.
     *
     * <h4>This is a question about the node, not about the process</h4>
     * A node with no view is the ordinary case in a headless run, but not only there: a graph
     * used from inside another graph has no view for any of its interior nodes while the app
     * around it is fully windowed. Ask this, never
     * {@link io.github.jaymcole.housegraph.sdk.RuntimeMode#isDaemon()}, which answers whether a
     * supervisor started the JVM and says nothing about any particular node.
     *
     * <p>Most node code does not need this: routing every control update through
     * {@link #present(Runnable)} already does the right thing either way. Use it to skip work
     * that only exists to feed a control — building a thumbnail, formatting a long report.
     *
     * @return true when this node has a live view
     */
    protected final boolean hasView() {
        return presentation != null;
    }

    /**
     * Runs one update against this node's inline controls, or discards it when the node has no
     * view. This is the only safe way for node code to touch what
     * {@link io.github.jaymcole.housegraph.sdk.NodeContentProvider#createNodeContent()} built:
     * those fields are null until that method runs, and it runs only when something draws the
     * node.
     *
     * <h4>Threading</h4>
     * The host decides where {@code uiUpdate} runs. The desktop app runs it inline when the
     * caller is already on the JavaFX Application Thread and marshals it there otherwise, so a
     * button handler still sees its own effect immediately while a background thread — a
     * {@link io.github.jaymcole.housegraph.sdk.NodeTimer} tick, a worker reporting a result —
     * cannot touch a control from the wrong thread. Because the update may run later, read the
     * node's own state inside the block rather than capturing a snapshot of it outside.
     *
     * @param uiUpdate the control update to apply; ignored when this node has no view
     */
    protected final void present(Runnable uiUpdate) {
        NodePresentation sink = presentation;
        if (sink != null) {
            sink.update(uiUpdate);
        }
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
     * <p>
     * Runs on the thread that removed the node — the FX thread in the app — and is
     * therefore the right place for teardown that is <em>thread-affine</em>: stopping a
     * {@code Timeline}, resetting a control. It is <b>not</b> time-bounded, so it must be
     * quick. Anything that blocks on the outside world belongs in
     * {@link #releaseResources()}.
     */
    protected void onRemoved() {
    }

    /**
     * Releases resources whose teardown takes real time — a child process to signal and
     * wait for, an mDNS registration to withdraw, a client to log out. Called once per
     * node, immediately after {@link #onRemoved()}, on a worker thread and under a time
     * limit. Must be idempotent, and safe even if the node's UI was never built.
     *
     * <h4>Why this is separate from {@code onRemoved()}</h4>
     * The two halves of teardown want opposite things. Stopping a {@code Timeline} or
     * touching a control <em>must</em> happen on the FX thread; killing a process tree
     * <em>must not</em>, because the app cannot wait on the FX thread for something that
     * might take ten seconds — and cannot bound it either, since you cannot time-limit
     * code running on the thread you are standing on. Splitting them lets each half run
     * where it belongs.
     * <p>
     * On {@link NodeGraph#dispose()} every node's {@code releaseResources()} runs
     * <em>concurrently</em>, so a machine running five servers shuts down in the time of
     * the slowest, not the sum of all five; a node that overruns
     * {@link NodeGraph#getReleaseTimeout()} is interrupted and abandoned so it cannot hold
     * up the rest. On an ordinary single-node removal it is handed to a background thread
     * and not waited for, so deleting a node never freezes the canvas.
     * <p>
     * Because it may be interrupted, treat a long wait here as cancellable: honour
     * {@link Thread#interrupted()} where you can. A no-op by default — only nodes owning
     * something slow need it.
     */
    protected void releaseResources() {
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
     * Called by {@link NodeGraph} right after a data edge whose <em>source</em> is this node is
     * registered — the output-side mirror of {@link #onInputEdgeAdded}. No-op by default; a node
     * whose behaviour depends on whether an output is actually consumed overrides this. Runs on
     * whatever thread performed the wiring (the UI thread for user edits), outside the graph's
     * structural lock.
     *
     * @param edge the data edge that was just wired out of this node
     */
    protected void onOutputEdgeAdded(Edge edge) {
    }

    /**
     * Called by {@link NodeGraph} right after a data edge whose <em>source</em> is this node is
     * removed (an explicit disconnect, the target's input being rewired, the target node being
     * deleted, or a view rebuild). No-op by default; the counterpart to
     * {@link #onOutputEdgeAdded}.
     *
     * @param edge the data edge that was just removed from this node's outputs
     */
    protected void onOutputEdgeRemoved(Edge edge) {
    }

    /**
     * The data edges currently leaving this node, or empty if it isn't in a graph — the mirror of
     * {@link #getIncomingDataEdges()}. Lets a node ask whether one of its own outputs is wired to
     * anything downstream (match {@link Edge#getSourceVariable()} against the output in question),
     * which is the reliable way to read <em>current</em> wiring rather than trust a single hook's
     * edge argument when those hooks are dispatched asynchronously.
     *
     * @return this node's current outgoing data edges, or empty if it isn't in a graph
     */
    protected Set<Edge> getOutgoingDataEdges() {
        return graph == null ? Set.of() : graph.getOutgoingDataEdges(this);
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
     * From within {@link #process(ProcessContext) process()}, marks one of this node's flow-out ports to fire
     * when control cascades out of the node. A node that activates <em>no</em> port
     * fires <em>all</em> its flow-out ports (so ordinary nodes need no activation call
     * and keep triggering everything downstream, exactly as before flow ports could
     * branch); a node that activates one or more ports fires only those. See
     * {@link NodeGraph}'s cascade logic. The entry-side mirror is
     * {@link ProcessContext#triggeredVia()}, which names the IN ports control arrived through.
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

    /**
     * From within {@link #process(ProcessContext) process()}, marks this firing as activating
     * <em>none</em> of this node's flow-out ports — the run's cascade stops here, rather than
     * {@link #activate}'s default of firing every out-port when it is never called. For a node
     * whose IN ports don't all mean "now also fire out" (see {@link ProcessContext#wasTriggeredVia}):
     * e.g. an arm/disarm pair of entry points where only the node's own periodic firing should
     * reach its flow-out, not the Start/Stop signal that armed or disarmed it.
     */
    protected void activateNone() {
        ExecutionContext context = ExecutionContext.current();
        if (context != null) {
            context.activateNone(this);
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
     * This node's flow-out ports <em>plus</em> its engine-owned {@link #getErrorFlowPort() Error}
     * port — everything a {@link FlowEdge} may legitimately leave this node by.
     *
     * <h4>Why this is separate from {@link #getFlowOutputs()}</h4>
     * {@code getFlowOutputs()} answers "what flow-outs did this node's author declare", and a great
     * deal depends on that answer being unpolluted: a node with no flow ports at all is a pure data
     * node, one with flow-outs and no flow-ins is an execution entry point
     * ({@link #isExecutionEntryPoint()}), and a module's boundary markers derive a module's whole
     * interface from theirs. Folding a port every node has into that list would make every constant
     * a trigger and give every module a phantom port.
     *
     * <p>So the error port is <em>connectable</em> without being <em>declared</em>. Only the code
     * that genuinely wires or draws ports reads this list: edge persistence, edge resolution on
     * load, and the canvas. Everything asking about a node's shape keeps reading the other one and
     * keeps getting the author's answer.
     *
     * <p>The error port sorts last, so every declared port keeps the index it had and an older save
     * file's positional flow-edge references stay valid.
     *
     * @return this node's declared flow-outs followed by its error port, unmodifiable
     */
    public List<FlowPort> getConnectableFlowOutputs() {
        ensureConfigured();
        List<FlowPort> connectable = new ArrayList<>(flowOutputs);
        connectable.add(errorFlowPort);
        return Collections.unmodifiableList(connectable);
    }

    /**
     * This node's data outputs <em>plus</em> its engine-owned
     * {@link #getErrorMessageOutput() Error Message} output — everything an {@link Edge} may
     * legitimately leave this node by. The data-side counterpart of
     * {@link #getConnectableFlowOutputs()}, separate from {@link #getOutputs()} for the same
     * reasons and sorted the same way.
     *
     * @return this node's declared outputs followed by its error-message output, unmodifiable
     */
    @SuppressWarnings("rawtypes")
    public List<NodeVariable> getConnectableOutputs() {
        ensureConfigured();
        List<NodeVariable> connectable = new ArrayList<>(outputs);
        connectable.add(errorMessage);
        return Collections.unmodifiableList(connectable);
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
     * How a failure of this node's {@code process()} affects the cascade — see
     * {@link FailurePolicy}. Never null.
     *
     * @return this node's failure policy
     */
    public FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    /**
     * Sets how a failure of this node's {@code process()} affects the cascade.
     *
     * @param failurePolicy the policy to apply; null resets to {@link FailurePolicy#HALT}
     */
    public void setFailurePolicy(FailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy == null ? FailurePolicy.HALT : failurePolicy;
    }

    /**
     * This node's error flow-out: the port the engine fires, instead of the node's ordinary
     * flow-outs, when {@code process()} fails under {@link FailurePolicy#HALT}.
     *
     * <h4>Why the engine owns it rather than each node declaring one</h4>
     * A failure path only works if it is <em>universal</em>. An author who has to remember to add
     * an {@code Error} port will add one to the node whose failure they anticipated and leave it
     * off the rest, which is the state the engine was already in: every node could fail, and none
     * could say so. Giving every node the port means a graph can handle a failure anywhere,
     * including in a third-party library whose author never thought about it.
     *
     * <h4>It is fired by the engine, not by the node</h4>
     * A node never {@link #activate}s this port — it has already thrown by the time it matters, and
     * a node that could reach an {@code activate} call did not fail. {@link NodeGraph} fires it
     * from the failure it recorded, and {@link #getErrorMessageOutput()} carries the message.
     *
     * @return the engine-owned error flow-out port, never null
     */
    public final FlowPort getErrorFlowPort() {
        ensureConfigured();
        return errorFlowPort;
    }

    /**
     * This node's error-message output: the message of the failure that fired
     * {@link #getErrorFlowPort()}, set by the engine immediately before it does.
     * <p>
     * Transient, so it is never written to a save file — it describes one run rather than the
     * node's configuration. Null whenever the node's last run did not fail.
     *
     * @return the engine-owned error-message output, never null
     */
    public final NodeVariable<String> getErrorMessageOutput() {
        ensureConfigured();
        return errorMessage;
    }

    /**
     * Whether {@code port} is the engine-owned {@link #getErrorFlowPort() error flow-out} of the
     * node that owns it, rather than one its author declared.
     * <p>
     * The distinction matters wherever a node's <em>authored</em> shape is what is being described:
     * a type fingerprint, or a view that should not draw an error anchor on every node on the
     * canvas. Compared by identity, so a node that happens to declare a port called "Error" of its
     * own is not mistaken for this one.
     *
     * @param node the node the port belongs to
     * @param port the port to test
     * @return true when {@code port} is {@code node}'s engine-owned error flow-out
     */
    public static boolean isErrorFlowPort(BaseNode node, FlowPort port) {
        return node != null && port != null && port == node.errorFlowPort;
    }

    /**
     * Whether {@code variable} is the engine-owned {@link #getErrorMessageOutput() error-message
     * output} of the node that owns it. The counterpart of {@link #isErrorFlowPort}, for the same
     * reasons.
     *
     * @param node the node the variable belongs to
     * @param variable the variable to test
     * @return true when {@code variable} is {@code node}'s engine-owned error-message output
     */
    public static boolean isErrorMessageOutput(BaseNode node, NodeVariable<?> variable) {
        return node != null && variable != null && variable == node.errorMessage;
    }

    /**
     * Whether anything is wired to either of this node's error ports — a flow edge out of
     * {@link #getErrorFlowPort()}, or a data edge out of {@link #getErrorMessageOutput()}.
     * <p>
     * What a canvas draws error anchors for. Every node has them, and drawing two extra anchors on
     * every node of a forty-node graph would cost far more legibility than the feature is worth, so
     * they appear on the nodes whose failures a graph actually handles — and on any node the user
     * asks to see them on.
     *
     * @return true when this node's error path is wired; false when it is not, or the node is not in a graph
     */
    public final boolean hasErrorPathWired() {
        NodeGraph owner = getOwningGraph();
        if (owner == null) {
            return false;
        }
        for (FlowEdge edge : owner.getOutgoingFlowEdges(this)) {
            if (edge.getSourcePort() == errorFlowPort) {
                return true;
            }
        }
        for (Edge edge : owner.getOutgoingDataEdges(this)) {
            if (edge.getSourceVariable() == errorMessage) {
                return true;
            }
        }
        return false;
    }

    /**
     * The most runs allowed to execute this node's {@link #process(ProcessContext) process()} at once, across all runs;
     * 0 means unlimited. See the field.
     *
     * @return this node's concurrency limit, or 0 for unlimited
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /**
     * Sets the most runs allowed to execute this node's {@link #process(ProcessContext) process()} at once (clamped to
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
     * How long this node's {@link #process(ProcessContext) process()} may run before the engine aborts it (0 = no timeout),
     * in milliseconds. See the field.
     *
     * @return this node's process timeout in milliseconds, or 0 for none
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Sets how long this node's {@link #process(ProcessContext) process()} may run before the engine interrupts it and marks
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
     * rather than it only running when reached along an incoming flow edge. At an entry point the
     * {@link ExecutionPolicy} gates a whole re-triggered run; a non-entry flow node still has a
     * meaningful policy (it gates re-entry of its own {@code process()} across concurrent runs), so
     * this predicate no longer decides <em>whether</em> a node has a policy — the UI surfaces the
     * selector for every node that participates in flow. It still governs the whole-run gate in
     * {@link NodeGraph#execute(BaseNode, Runnable)}.
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
