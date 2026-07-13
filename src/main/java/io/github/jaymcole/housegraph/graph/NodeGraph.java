package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Owns a set of {@link BaseNode}s and the {@link Edge}/{@link FlowEdge} connections
 * between them, and drives their execution.
 * <p>
 * This is an instance (not static) so that each canvas/document gets its own
 * isolated graph — multiple graphs can coexist, tests can create a throwaway graph
 * per case, and deleting a node actually releases it (no static map holding a
 * reference forever).
 * <p>
 * A node must be added via {@link #addNode} before it can take part in an edge, and
 * a {@link BaseNode#execute()}/{@link BaseNode#beginProcessing()} call only works
 * once its node has been added to a graph.
 * <p>
 * <b>Threading — concurrent runs.</b> A <em>run</em> is one trigger firing and everything that
 * cascades from it. Each {@link #execute} starts a run and returns immediately; runs execute
 * concurrently on a shared virtual-thread executor and never block one another, so a slow node
 * (a camera, an LLM node) only slows its own run — separate triggers keep firing. Every run
 * carries its own isolated {@link ExecutionContext} (node statuses, the visited set, activated
 * flow-ports, and computed data values), which is what makes overlapping runs safe: two runs
 * over the same node don't share its status or its computed values. {@link #resolve}/{@link
 * BaseNode#beginProcessing()} still behaves synchronously to its caller (some need the value the
 * instant it returns), running the pull in a fresh context on the executor and blocking until done.
 * <p>
 * <b>Flow is fire-and-forget.</b> Within a run, a node resolves its data synchronously (pulled),
 * runs its {@code process()}, then <em>schedules</em> its activated downstream nodes as independent
 * tasks and does not wait for them (see {@link Run}). Ordinary linear order is still preserved (a
 * node runs downstream only after its own {@code process()} finishes), but a fan-out no longer
 * joins — each branch progresses on its own. A node reached twice in one run is fired once
 * ({@code flowVisited} dedup); a node pulled as a shared data dependency runs once per run, guarded
 * by its own intrinsic lock in {@link #resolveInternal} (which, reentrant per-thread, also turns a
 * data cycle into a clean {@link IllegalStateException} rather than a stack overflow). Because
 * reconvergence no longer waits for all incoming branches, joining is an explicit concern handled by
 * a flow-join node (see {@link BaseNode#isFlowJoin()}); the design doc
 * {@code docs/design/per-node-execution-policy.md} covers the whole concurrent-runs model.
 * <p>
 * Structural methods (adding/removing nodes and edges, reading the topology) stay
 * {@code synchronized} on this instance for their own brief critical section, but that
 * lock is never held for a whole run — so a UI-thread edit isn't forced to wait out a slow
 * in-flight trigger.
 * <p>
 * <b>Re-entrant triggers, two scopes.</b> {@link ExecutionPolicy} is applied at two levels. At the
 * <em>entry node</em> — when {@link #execute(BaseNode, Runnable)} is called while an earlier run of
 * that node is still in flight — the policy gates the <em>whole run</em>: {@code DROP} ignores it,
 * {@code RESTART} cancels the in-flight run's remaining cascade and runs a fresh one, {@code QUEUE}
 * (the default) coalesces to a single pending follow-up run, and {@code PARALLEL} starts an
 * independent concurrent run every time. {@code DROP}/{@code QUEUE}/{@code RESTART} keep a single
 * in-flight run per entry node via {@link EntryExecution}; different entry nodes (and
 * {@code PARALLEL} re-fires) run concurrently.
 * <p>
 * At a <em>mid-cascade node</em> — one reached along a flow edge during a run — the same policy is
 * re-applied at a narrower, <em>process-scoped</em> grain via a {@link ReentryGate} (see
 * {@link #reentryGates}): if a sibling run is currently inside that node's {@code process()}, this
 * run's arrival is dropped / coalesced-and-queued / restarts it, exactly as above but scoped to the
 * node's own {@code process()} rather than a whole run. This is what lets one trigger fan out into
 * branches with <em>different</em> re-entrancy behavior — a slow branch that {@code DROP}s overlaps
 * while a fast sibling branch keeps firing. Note the composition: the entry policy governs whether
 * concurrent runs start at all, so a mid-cascade gate only ever sees overlap when the entry is
 * {@code PARALLEL} (or when distinct entry nodes feed a shared node).
 * <p>
 * <b>Loop bodies run as seeded sub-runs.</b> A node that must fire one of its flow outputs
 * <em>more than once</em> — a for-each loop firing its body once per list item — can't express
 * that through the ordinary cascade, because a downstream node is fired at most once per run
 * ({@code flowVisited} dedup). Instead it calls {@link #runFlowBranchToCompletion}, which runs that
 * branch in a <em>fresh, isolated</em> run (so the dedup resets and the body executes afresh) after
 * seeding the driving node's per-iteration output values into the sub-run's context and pre-marking
 * it complete there — so body nodes pull the seeded values without re-running the driver's
 * {@code process()}. The driver's {@code process()} blocks on each such call, running iterations
 * sequentially; its own outer run stays non-idle throughout. See {@code ForEachNode}.
 * <p>
 * This class never imports anything from JavaFX: node/edge execution callbacks
 * ({@link BaseNode#onExecuted()}, {@link GraphExecutionListener}) are dispatched
 * through an injectable {@link #setCallbackExecutor callback executor}, which defaults
 * to running them immediately on the calling (here, background) thread. The UI layer
 * supplies {@code Platform::runLater} so those callbacks — which touch JavaFX nodes —
 * actually run on the FX Application Thread.
 */
public class NodeGraph {

    private static final Logger log = Log.get(NodeGraph.class);

    private final Set<BaseNode> nodes = new LinkedHashSet<>();
    private final Map<BaseNode, Set<Edge>> outgoingDataEdges = new HashMap<>();
    private final Map<BaseNode, Set<Edge>> incomingDataEdges = new HashMap<>();
    private final Map<BaseNode, Set<FlowEdge>> outgoingFlowEdges = new HashMap<>();
    private final Map<BaseNode, Set<FlowEdge>> incomingFlowEdges = new HashMap<>();

    /**
     * Per-entry-node execution state backing the {@link ExecutionPolicy} decision made in
     * {@link #execute(BaseNode, Runnable)}. Keyed by the node {@code execute()} is called on;
     * each value tracks whether a pass from that node is in flight and holds at most one
     * coalesced pending pass. Concurrent because triggers arrive on many threads (FX, JDA,
     * camera, resource publishers). Entries are dropped when a node leaves the graph.
     */
    private final Map<BaseNode, EntryExecution> entryExecutions = new ConcurrentHashMap<>();

    /**
     * Per-node <em>process-scoped</em> re-entry gates backing an {@link ExecutionPolicy} carried by a
     * <em>mid-cascade</em> node (one reached along a flow edge, not the run's entry). Distinct from
     * {@link #entryExecutions}: that gates a whole run at its entry node, whereas a gate here spans
     * only the window in which some run is executing this node's {@code process()}. When a second
     * run's flow reaches the node while that window is open, the node's policy decides the outcome —
     * so two branches fanning out from one trigger can carry different re-entrancy behavior (a slow
     * branch that {@code DROP}s overlaps, a fast branch that {@code QUEUE}s them). Consulted from
     * {@link Run#fire}; keyed by node; concurrent because sibling runs fire nodes on many threads;
     * entries dropped when a node leaves the graph.
     */
    private final Map<BaseNode, ReentryGate> reentryGates = new ConcurrentHashMap<>();

    /**
     * Number of triggered passes that have been accepted but not yet finished, including a
     * coalesced pass still waiting to run. Lets {@link #awaitIdle()} wait for the whole
     * chain a burst of triggers produces — a coalesced follow-up is submitted lazily from
     * inside the pass ahead of it, so it isn't yet on the executor's queue when awaitIdle
     * is called. Guarded by {@link #idleLock}.
     */
    private int outstandingPasses = 0;
    private final Object idleLock = new Object();

    private final List<GraphExecutionListener> executionListeners = new ArrayList<>();

    /**
     * Every node firing — a trigger's entry node and each downstream node reached by a
     * fire-and-forget flow cascade — runs as a task here, as does each synchronous
     * {@link #resolve} pull. Virtual threads are the natural fit: runs and their branches are
     * independent and numerous, and a firing that blocks (a slow camera, a minutes-long LLM
     * node, a debug delay) parks its cheap virtual thread without tying up a scarce platform
     * one or holding up any other run. Concurrency between runs is safe because each run owns
     * an isolated {@link ExecutionContext}; see the class Javadoc.
     */
    private final ExecutorService runExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Fires the per-node process timeouts (see {@link BaseNode#getTimeoutMillis()}): a one-shot task
     * scheduled here interrupts the firing thread if a node's {@code process()} overruns. A single
     * daemon thread suffices — the tasks only set a flag and interrupt, never do real work.
     */
    private final ScheduledExecutorService watchdogScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "housegraph-timeout-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Executor callbackExecutor = Runnable::run;

    private static final Runnable NO_PREPARATION = () -> {
    };

    public synchronized void addExecutionListener(GraphExecutionListener listener) {
        executionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * How node/edge execution callbacks get from the background execution thread to wherever they need to run.
     *
     * @param callbackExecutor the executor used to dispatch execution callbacks (e.g. {@code Platform::runLater})
     */
    public void setCallbackExecutor(Executor callbackExecutor) {
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    // --- Node lifecycle ---------------------------------------------------------

    public void addNode(BaseNode node) {
        Objects.requireNonNull(node, "node");
        // The lifecycle hook runs outside the lock so a node's onActivated() can do work
        // (subscribe, look something up) without blocking the whole graph.
        if (attach(node)) {
            node.onActivated();
        }
    }

    private synchronized boolean attach(BaseNode node) {
        if (node.getGraph() != null && node.getGraph() != this) {
            throw new IllegalStateException(node.getName() + " already belongs to another NodeGraph");
        }
        if (nodes.add(node)) {
            node.setGraph(this);
            return true;
        }
        return false;
    }

    public void removeNode(BaseNode node) {
        Objects.requireNonNull(node, "node");
        // onRemoved() runs outside the lock too - resource teardown (closing a socket,
        // stopping a timer) mustn't be held up by, or hold up, the graph lock.
        if (detach(node)) {
            node.onRemoved();
        }
    }

    private synchronized boolean detach(BaseNode node) {
        for (Edge edge : new ArrayList<>(getOutgoingDataEdges(node))) {
            removeEdge(edge);
        }
        for (Edge edge : new ArrayList<>(getIncomingDataEdges(node))) {
            removeEdge(edge);
        }
        for (FlowEdge edge : new ArrayList<>(getOutgoingFlowEdges(node))) {
            removeFlowEdge(edge);
        }
        for (FlowEdge edge : new ArrayList<>(getIncomingFlowEdges(node))) {
            removeFlowEdge(edge);
        }
        outgoingDataEdges.remove(node);
        incomingDataEdges.remove(node);
        outgoingFlowEdges.remove(node);
        incomingFlowEdges.remove(node);
        boolean present = nodes.remove(node);
        // Drop any policy bookkeeping for this node; an in-flight pass keeps its own
        // captured reference and finishes unaffected.
        entryExecutions.remove(node);
        // Release the node's re-entry gate too, and free any run parked on it (QUEUE/RESTART) so
        // its firing task returns and its run can quiesce rather than waiting on a gate no one
        // will ever signal now the node is gone.
        ReentryGate gate = reentryGates.remove(node);
        if (gate != null) {
            gate.cancelWaiter();
        }
        node.setGraph(null);
        return present;
    }

    public synchronized Set<BaseNode> getNodes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
    }

    /**
     * Removes and disposes every node (so long-lived resources get cleaned up via
     * {@link BaseNode#onRemoved()}) and stops the execution threads. Intended for app
     * shutdown; the graph shouldn't be used afterward.
     */
    public void dispose() {
        List<BaseNode> current;
        synchronized (this) {
            current = new ArrayList<>(nodes);
        }
        for (BaseNode node : current) {
            removeNode(node);
        }
        runExecutor.shutdownNow();
        watchdogScheduler.shutdownNow();
    }

    // --- Data edges ---------------------------------------------------------------

    public void registerEdge(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        // The onInputEdgeAdded hook is dispatched through the callback executor rather than
        // called inline. A node reacting to the wiring may rebuild its ports (and thus its
        // on-canvas view); the UI's executor (Platform.runLater) defers that to the next FX
        // pulse, so it can't tear down and rebuild the view while the caller (e.g.
        // GraphCanvas.createEdge) is still mid-wiring and holding now-stale PortView
        // references. Headless (Runnable::run) still fires it synchronously.
        if (attachEdge(edge)) {
            callbackExecutor.execute(() -> edge.getTargetNode().onInputEdgeAdded(edge));
        }
    }

    private synchronized boolean attachEdge(Edge edge) {
        requireRegistered(edge.getSourceNode());
        requireRegistered(edge.getTargetNode());
        // The authoritative type gate. A connection is allowed when the target is assignable from
        // the source or a hidden converter bridges the pair (see TypeConverters); propagateValue
        // then applies that converter at handoff. GraphCanvas.isValidConnection mirrors this for the
        // drag-time UX check.
        if (!TypeConverters.isCompatible(edge.getSourceVariable().type, edge.getTargetVariable().type)) {
            throw new IllegalArgumentException(
                    "Cannot connect a " + edge.getSourceVariable().type.getSimpleName() + " output to a "
                            + edge.getTargetVariable().type.getSimpleName() + " input");
        }
        // Cardinality gate: an input is fed by at most one data edge, so a value is pulled through a
        // single, unambiguous source (multiple edges into one input would make propagateValue's
        // last-writer-wins order nondeterministic). Callers that mean to rewire replace, not stack:
        // remove the existing edge first (the UI's GraphCanvas.createEdge does exactly this). A
        // re-register of the very same edge is idempotent and allowed.
        for (Edge existing : incomingDataEdges.getOrDefault(edge.getTargetNode(), Collections.emptySet())) {
            if (existing != edge && existing.getTargetVariable() == edge.getTargetVariable()) {
                throw new IllegalStateException(
                        "Input \"" + edge.getTargetVariable().name + "\" on " + edge.getTargetNode().getName()
                                + " is already fed by a data edge; remove it before wiring another");
            }
        }
        boolean added = addToSet(outgoingDataEdges, edge.getSourceNode(), edge);
        addToSet(incomingDataEdges, edge.getTargetNode(), edge);
        return added;
    }

    public void removeEdge(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        // Deferred through the callback executor for the same reason as registerEdge.
        if (detachEdge(edge)) {
            callbackExecutor.execute(() -> edge.getTargetNode().onInputEdgeRemoved(edge));
        }
    }

    private synchronized boolean detachEdge(Edge edge) {
        boolean removed = removeFromSet(outgoingDataEdges, edge.getSourceNode(), edge);
        removeFromSet(incomingDataEdges, edge.getTargetNode(), edge);
        return removed;
    }

    public synchronized Set<Edge> getOutgoingDataEdges(BaseNode node) {
        return Collections.unmodifiableSet(new HashSet<>(outgoingDataEdges.getOrDefault(node, Collections.emptySet())));
    }

    public synchronized Set<Edge> getIncomingDataEdges(BaseNode node) {
        return Collections.unmodifiableSet(new HashSet<>(incomingDataEdges.getOrDefault(node, Collections.emptySet())));
    }

    // --- Flow edges -----------------------------------------------------------

    public synchronized void registerFlowEdge(FlowEdge edge) {
        Objects.requireNonNull(edge, "edge");
        requireRegistered(edge.getSourceNode());
        requireRegistered(edge.getTargetNode());
        addToSet(outgoingFlowEdges, edge.getSourceNode(), edge);
        addToSet(incomingFlowEdges, edge.getTargetNode(), edge);
    }

    public synchronized void removeFlowEdge(FlowEdge edge) {
        Objects.requireNonNull(edge, "edge");
        removeFromSet(outgoingFlowEdges, edge.getSourceNode(), edge);
        removeFromSet(incomingFlowEdges, edge.getTargetNode(), edge);
    }

    public synchronized Set<FlowEdge> getOutgoingFlowEdges(BaseNode node) {
        return Collections.unmodifiableSet(new HashSet<>(outgoingFlowEdges.getOrDefault(node, Collections.emptySet())));
    }

    public synchronized Set<FlowEdge> getIncomingFlowEdges(BaseNode node) {
        return Collections.unmodifiableSet(new HashSet<>(incomingFlowEdges.getOrDefault(node, Collections.emptySet())));
    }

    // --- Execution ------------------------------------------------------------

    /**
     * Pulls a fresh value through {@code node}'s incoming data edges (resolving
     * upstream nodes first) and runs its process(). Every call runs in a fresh
     * {@link ExecutionContext}, so every node starts un-run and this never serves a stale
     * cached value. Blocks the calling thread until the pull completes (see the class Javadoc).
     *
     * @param node the node to resolve
     * @throws IllegalStateException if the data edges form a cycle
     */
    public void resolve(BaseNode node) {
        runOnExecutorAndWait(() -> resolveEntry(node));
    }

    /**
     * Resolves {@code node} (see {@link #resolve}) and then cascades along its
     * outgoing flow edges, resolving each downstream node in turn. A node reached
     * via two different flow paths in the same pass only runs once. Runs on a
     * background thread and returns immediately - the point of this being the trigger
     * path is that a slow node in the graph doesn't block whoever called this.
     *
     * @param node the node to trigger
     */
    public void execute(BaseNode node) {
        execute(node, NO_PREPARATION);
    }

    /**
     * Triggers {@code node} (see the single-arg overload) after running {@code prepare}
     * on the execution thread, at the very start of the pass. This is how an event source
     * hands its per-event data to exactly one pass: the payload is captured in
     * {@code prepare} and applied to the node's outputs <em>inside</em> the serialized
     * pass, so a burst of events can't clobber one another's values through a shared
     * field (which they could if the value were written from the event thread before the
     * pass ran).
     * <p>
     * When a pass this node already started is still in flight, the node's
     * {@link ExecutionPolicy} decides what happens to this new trigger — dropped, restarted,
     * or queued (coalesced). See {@link ExecutionPolicy}.
     *
     * @param node    the node to trigger
     * @param prepare work run on the execution thread at the very start of the pass
     */
    public void execute(BaseNode node, Runnable prepare) {
        Objects.requireNonNull(prepare, "prepare");
        ExecutionPolicy policy = node.getExecutionPolicy();

        if (policy == ExecutionPolicy.PARALLEL) {
            // Every trigger starts its own concurrent run - no single-flight gate, no
            // coalescing. Isolated contexts make overlapping runs of the same node safe.
            beginPass();
            startRun(node, prepare, this::endPass);
            return;
        }

        EntryExecution state = entryExecutions.computeIfAbsent(node, ignored -> new EntryExecution());
        synchronized (state) {
            if (!state.running) {
                // Nothing in flight for this node: start a run immediately, regardless of policy.
                state.running = true;
                beginPass();
                state.runningToken = startRun(node, prepare, () -> onRunComplete(node, state));
                return;
            }
            if (policy == ExecutionPolicy.DROP) {
                // A run from this node is already in flight or queued; ignore this trigger.
                return;
            }
            // RESTART and QUEUE both coalesce to a single pending run carrying the latest
            // inputs; RESTART additionally cancels the in-flight run's remaining cascade so
            // the pending one effectively replaces it rather than following it.
            if (policy == ExecutionPolicy.RESTART && state.runningToken != null) {
                state.runningToken.cancel();
            }
            if (state.pendingPrepare == null) {
                beginPass(); // first run to queue behind the running one
            }
            state.pendingPrepare = prepare; // replaces any earlier pending (coalesce to latest)
        }
    }

    /**
     * Runs the flow branch leaving {@code sourcePort} once, to completion, in a fresh isolated
     * {@link ExecutionContext}, blocking the caller until that sub-run quiesces. Before the branch
     * fires, {@code seed} runs in the sub-context to populate {@code source}'s per-iteration output
     * values, and {@code source} is pre-marked complete there so downstream nodes pull the seeded
     * values (short-circuiting on its complete status in {@link #resolveInternal}) instead of
     * re-running its {@code process()}.
     * <p>
     * This is the primitive behind loop nodes (see {@code ForEachNode}): a node drives its "body"
     * flow output once per item by calling this in a loop, each call an isolated run so the body's
     * per-run flow dedup ({@code flowVisited}) resets and the body executes afresh for every item.
     * The caller is a run-executor virtual thread inside its own {@code process()}, so blocking here
     * is cheap and keeps that outer run non-idle until the whole loop finishes — no separate
     * {@link #beginPass()} is needed for the sub-run.
     *
     * @param source     the node whose flow output drives the branch (typically the loop node)
     * @param sourcePort the OUT flow port whose downstream branch to run
     * @param seed       work run in the sub-context to set {@code source}'s per-iteration outputs
     */
    public void runFlowBranchToCompletion(BaseNode source, FlowPort sourcePort, Runnable seed) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourcePort, "sourcePort");
        Objects.requireNonNull(seed, "seed");
        requireRegistered(source);
        CountDownLatch done = new CountDownLatch(1);
        Run run = new Run(done::countDown);
        run.startBranch(source, sourcePort, seed);
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running loop body branch for " + source.getName(), e);
        }
    }

    /**
     * Creates a run for {@code node} and submits its start to the run executor, returning its
     * cancellation token (so a RESTART trigger holding {@code state}'s monitor can stop it). The
     * run fires {@code node} and cascades fire-and-forget; when it fully quiesces {@code onComplete}
     * runs (on a run-executor thread).
     */
    private PassToken startRun(BaseNode node, Runnable prepare, Runnable onComplete) {
        Run run = new Run(onComplete);
        runExecutor.execute(() -> run.start(node, prepare));
        return run.token();
    }

    /**
     * Called when a run started for {@code node} fully quiesces: chains into a coalesced pending
     * run if one accumulated while it ran (QUEUE / RESTART), otherwise marks the node idle so the
     * next trigger starts fresh. Either way {@link #endPass()} balances this run's {@link #beginPass()};
     * a chained run was counted separately when queued, so it keeps the graph non-idle until it too
     * finishes.
     */
    private void onRunComplete(BaseNode node, EntryExecution state) {
        synchronized (state) {
            if (state.pendingPrepare != null) {
                Runnable next = state.pendingPrepare;
                state.pendingPrepare = null;
                state.runningToken = startRun(node, next, () -> onRunComplete(node, state));
            } else {
                state.running = false;
                state.runningToken = null;
            }
        }
        endPass();
    }

    /**
     * Passes a mid-cascade {@code node}'s firing through its {@link ReentryGate} under {@code policy}
     * (never {@code PARALLEL} — the caller skips gating for that). Returns the held gate if this run
     * may run the node's {@code process()} (the caller must later {@link ReentryGate#release()} it),
     * or {@code null} if this branch should be abandoned. May block the calling firing thread for a
     * {@code QUEUE}/{@code RESTART} arrival while an earlier run holds the gate.
     */
    private ReentryGate acquireReentryGate(BaseNode node, ExecutionPolicy policy) {
        return reentryGates.computeIfAbsent(node, ignored -> new ReentryGate()).acquire(policy);
    }

    private void beginPass() {
        synchronized (idleLock) {
            outstandingPasses++;
        }
    }

    private void endPass() {
        synchronized (idleLock) {
            if (--outstandingPasses == 0) {
                idleLock.notifyAll();
            }
        }
    }

    private void resolveEntry(BaseNode node) {
        requireRegistered(node);
        ExecutionContext context = new ExecutionContext();
        context.run(() -> resolveInternal(context, node));
    }

    private void runOnExecutorAndWait(Runnable task) {
        try {
            runExecutor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for graph resolution", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * Resolving a node is scoped to this run's own per-node monitor ({@link ExecutionContext#lockFor}),
     * reentrant per thread. That gives two things at once: two concurrent flow branches of the same
     * run that share a data dependency can't both run that shared node's process() - the second
     * blocks here until the first finishes, then sees its now-complete status and returns without
     * re-running it - and a single thread revisiting a node it's already mid-resolving (a data cycle)
     * hits the IN_PROGRESS check below rather than deadlocking on itself. The monitor is per-run, so
     * two <em>different</em> concurrent runs sharing this node don't serialize on it.
     */
    private void resolveInternal(ExecutionContext context, BaseNode node) {
        synchronized (context.lockFor(node)) {
            NodeProcessingStatus status = context.statusOf(node);
            if (status == NodeProcessingStatus.IN_PROGRESS) {
                throw new IllegalStateException("Cycle detected in data graph at node: " + node.getName());
            }
            if (status.isComplete()) {
                return;
            }

            setStatus(context, node, NodeProcessingStatus.IN_PROGRESS);
            for (Edge edge : getIncomingDataEdges(node)) {
                resolveInternal(context, edge.getSourceNode());
                propagateValue(edge);
                callbackExecutor.execute(() -> notifyDataEdgeTraversed(edge));
            }

            callbackExecutor.execute(() -> notifyNodeStarted(node));
            runProcess(context, node);
            // Mirror this run's computed values onto the node before the (possibly async,
            // off-context) onExecuted callback runs, so it and later observers see them.
            context.commitValuesOf(node);
            callbackExecutor.execute(() -> {
                node.onExecuted();
                notifyNodeExecuted(node);
            });
        }
    }

    /**
     * Runs {@code node}'s {@code process()} and records its status, honoring the node's concurrency
     * limit and timeout. The concurrency permit (if any) caps how many runs execute this node at
     * once — a run blocks here until one is free, so its firing stays pending and the graph isn't
     * considered idle meanwhile. The timeout (if any) schedules a watchdog that interrupts this
     * thread if {@code process()} overruns, marking the node {@code FAILED} with a
     * {@link TimeoutException}. Cancellation (this timeout, or a superseding {@link
     * ExecutionPolicy#RESTART}) is cooperative: the engine interrupts the thread and exposes the
     * cancelled state through the {@link ProcessContext} handed to {@code process()}, so a node that
     * polls {@link ProcessContext#checkCancelled()} — or a blocking call that honours interruption —
     * stops promptly, while a {@code process()} that does neither runs to completion regardless.
     */
    private void runProcess(ExecutionContext context, BaseNode node) {
        Semaphore limiter = node.concurrencyLimiter();
        if (limiter != null) {
            limiter.acquireUninterruptibly();
        }

        long timeoutMillis = node.getTimeoutMillis();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = null;
        if (timeoutMillis > 0) {
            Thread firingThread = Thread.currentThread();
            watchdog = watchdogScheduler.schedule(() -> {
                timedOut.set(true);
                firingThread.interrupt();
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        }

        // The node's cooperative-cancellation view: its run being superseded (RESTART), its timeout
        // firing, or its thread being interrupted. Polling this is the only way a CPU-bound process()
        // can be stopped — the engine's own cancellation checks only fire between nodes.
        BooleanSupplier cancelled = () ->
                timedOut.get() || context.isCancelled() || Thread.currentThread().isInterrupted();
        ProcessContext processContext = new ProcessContext(cancelled);

        try {
            node.process(processContext);
            if (timedOut.get()) {
                // process() returned but the watchdog had already fired (it swallowed the interrupt).
                throw new TimeoutException(node.getName() + " exceeded its " + timeoutMillis + " ms timeout");
            }
            setStatus(context, node, NodeProcessingStatus.SUCCESS);
            node.setLastError(null);
        } catch (Exception e) {
            // A plain cancellation (a superseding RESTART or node removal asked this run to stop, and
            // the node cooperated by bailing out of process()) is expected, not a failure: record the
            // incomplete status but log it quietly rather than as an error.
            boolean cancelledNotTimedOut = e instanceof CancellationException && !timedOut.get();
            Throwable error = timedOut.get() && !(e instanceof TimeoutException)
                    ? new TimeoutException(node.getName() + " exceeded its " + timeoutMillis + " ms timeout")
                    : e;
            setStatus(context, node, NodeProcessingStatus.FAILED);
            node.setLastError(error);
            if (cancelledNotTimedOut) {
                log.debug("Node \"{}\" processing was cancelled", node.getName());
            } else {
                log.error("Node \"{}\" failed to process: {}", node.getName(), error);
            }
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            // Clear any interrupt the watchdog raised so the firing thread continues (scheduling
            // downstream) clean, and release the concurrency permit for the next waiting run.
            Thread.interrupted();
            if (limiter != null) {
                limiter.release();
            }
        }
    }

    /**
     * Records {@code node}'s status for this run (authoritative, used for cycle detection and
     * dedup) and mirrors it onto the node itself, so post-run observers — the UI and tests
     * calling {@link BaseNode#getStatus()} — can still see how a node's last run ended. The
     * mirror is last-run-wins across concurrent runs; only the context copy drives execution.
     */
    private void setStatus(ExecutionContext context, BaseNode node, NodeProcessingStatus status) {
        context.setStatus(node, status);
        node.setStatus(status);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void propagateValue(Edge edge) {
        NodeVariable<?> source = edge.getSourceVariable();
        // Raw target: setValue erases to setValue(Object), so the converted value (already coerced
        // to the target type by TypeConverters, or passed through when no converter applies) can be
        // handed off without a compile-time cast.
        NodeVariable target = edge.getTargetVariable();
        target.setValue(TypeConverters.convert(source.getValue(), source.type, target.type));
    }

    private void notifyNodeStarted(BaseNode node) {
        for (GraphExecutionListener listener : executionListeners) {
            listener.onNodeStarted(node);
        }
    }

    private void notifyNodeExecuted(BaseNode node) {
        for (GraphExecutionListener listener : executionListeners) {
            listener.onNodeExecuted(node);
        }
    }

    private void notifyDataEdgeTraversed(Edge edge) {
        for (GraphExecutionListener listener : executionListeners) {
            listener.onDataEdgeTraversed(edge);
        }
    }

    private void notifyFlowEdgeTraversed(FlowEdge edge) {
        for (GraphExecutionListener listener : executionListeners) {
            listener.onFlowEdgeTraversed(edge);
        }
    }

    /**
     * Blocks until the graph is idle: every triggered run accepted so far has fully quiesced,
     * <em>including</em> any coalesced follow-up run a burst of triggers queued. Runs now execute
     * concurrently and fire-and-forget, so there is no single thread to drain; instead this waits
     * on the {@link #outstandingPasses} count, which a run decrements only once its last node
     * firing completes. The happens-before via {@link #idleLock} means a caller that returns from
     * here sees all of every finished run's effects. Not needed by the app itself, but lets tests
     * deterministically wait for background work before asserting on its effects.
     */
    public void awaitIdle() {
        synchronized (idleLock) {
            while (outstandingPasses > 0) {
                try {
                    idleLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for the graph to become idle", e);
                }
            }
        }
    }

    // --- Helpers ----------------------------------------------------------------

    /**
     * {@code synchronized} even though most callers (registerEdge/registerFlowEdge)
     * already hold this instance's monitor themselves (reentrant, so no extra cost
     * there) - resolveEntry/executeEntry do NOT, since they're no longer synchronized
     * for their whole duration (see class Javadoc), so this method has to protect its
     * own read of the shared nodes set.
     */
    private synchronized void requireRegistered(BaseNode node) {
        if (!nodes.contains(node)) {
            throw new IllegalStateException(node.getName() + " must be added to the graph before it can be wired or run");
        }
    }

    private static <T> boolean addToSet(Map<BaseNode, Set<T>> map, BaseNode node, T value) {
        return map.computeIfAbsent(node, key -> new HashSet<>()).add(value);
    }

    private static <T> boolean removeFromSet(Map<BaseNode, Set<T>> map, BaseNode node, T value) {
        Set<T> set = map.get(node);
        return set != null && set.remove(value);
    }

    private static Throwable rootCause(RuntimeException e) {
        return e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    /**
     * One execution run: a trigger firing and everything that cascades from it, carried in its
     * own isolated {@link ExecutionContext}. Flow is <b>fire-and-forget</b> — a node schedules its
     * downstream nodes and does not wait for them — so the run stays alive (its {@link #pending}
     * counter above zero) until every scheduled node firing has completed, at which point
     * {@link #onComplete} runs. Data is still pulled synchronously within each firing. Non-static:
     * a run uses the enclosing graph's executor, topology and callbacks.
     */
    private final class Run {
        private final ExecutionContext context = new ExecutionContext();
        private final PassToken token = new PassToken();
        private final AtomicInteger pending = new AtomicInteger();
        private final Runnable onComplete;

        /**
         * The node this run was triggered on. Its re-entrancy was already decided by the whole-run
         * gate in {@link #execute(BaseNode, Runnable)}, so {@link #fire} skips the per-node
         * process-scoped gate for it (only nodes reached along a flow edge are gated there).
         */
        private BaseNode entryNode;

        Run(Runnable onComplete) {
            this.onComplete = onComplete;
            // A node's process() sees this run's cancellation (a superseding RESTART) through its
            // ProcessContext, which reads it off the context; point the context at this run's token.
            context.setCancellationSignal(token::isCancelled);
        }

        PassToken token() {
            return token;
        }

        /** Runs {@code prepare} in this run's context (so an event payload lands in its own value overlay), then fires {@code entry}. */
        void start(BaseNode entry, Runnable prepare) {
            this.entryNode = entry;
            try {
                context.run(prepare);
            } catch (RuntimeException e) {
                log.error("Trigger preparation for \"{}\" failed: {}", entry.getName(), rootCause(e));
            }
            // Always schedule, even if prepare threw, so the run reaches onComplete and balances its beginPass().
            schedule(entry);
        }

        /**
         * Runs the flow branch leaving {@code sourcePort} of {@code source} as this run's work, after
         * seeding {@code source}'s per-iteration state into this run's context: it is marked
         * {@link NodeProcessingStatus#SUCCESS} (so body nodes that pull it short-circuit on its
         * complete status rather than re-running its {@code process()}) and {@code seed} sets its
         * output values in this context's overlay. Only the edges leaving {@code sourcePort} are
         * scheduled, so the source's other out-ports stay dormant. Backs
         * {@link #runFlowBranchToCompletion}.
         * <p>
         * The run is held open across scheduling (a guarding {@code pending} increment) so a
         * body task that finishes fast can't drive {@code pending} to zero — firing
         * {@code onComplete} and releasing the waiting caller — before every branch edge is
         * scheduled. If the port is unwired, nothing is scheduled and the run completes here so the
         * caller is still released.
         */
        void startBranch(BaseNode source, FlowPort sourcePort, Runnable seed) {
            this.entryNode = source;
            pending.incrementAndGet();
            try {
                context.run(() -> {
                    setStatus(context, source, NodeProcessingStatus.SUCCESS);
                    // A body edge looping back to source in this sub-run won't re-fire it.
                    context.markFlowVisited(source);
                    try {
                        seed.run();
                    } catch (RuntimeException e) {
                        log.error("Loop-body seeding for \"{}\" failed: {}", source.getName(), rootCause(e));
                    }
                });
                for (FlowEdge flowEdge : getOutgoingFlowEdges(source)) {
                    if (flowEdge.getSourcePort() != sourcePort) {
                        continue;
                    }
                    callbackExecutor.execute(() -> notifyFlowEdgeTraversed(flowEdge));
                    schedule(flowEdge.getTargetNode());
                }
            } finally {
                if (pending.decrementAndGet() == 0) {
                    onComplete.run();
                }
            }
        }

        /**
         * Handles one flow arrival at {@code node}. An ordinary node fires on its first arrival and
         * is deduped thereafter; a {@link BaseNode#isFlowJoin() flow join} instead records the arrival
         * and fires only once all its wired incoming edges have arrived (an AND-barrier). A join whose
         * branches don't all arrive this run simply never fires — no firing task is left pending, so
         * the run still quiesces.
         */
        private void schedule(BaseNode node) {
            if (node.isFlowJoin()) {
                int arrived = context.recordJoinArrival(node);
                if (arrived >= getIncomingFlowEdges(node).size() && context.markFlowVisited(node)) {
                    fireTask(node);
                }
                return;
            }
            if (context.markFlowVisited(node)) {
                fireTask(node);
            }
        }

        private void fireTask(BaseNode node) {
            pending.incrementAndGet();
            runExecutor.execute(() -> fire(node));
        }

        /**
         * Resolves {@code node} (pulling its data) then schedules its activated downstream nodes,
         * without waiting for them. The last firing to complete (pending count reaching zero) ends
         * the run. Cooperative RESTART cancellation is checked here and before each downstream
         * schedule; a node already mid-{@code process()} isn't interrupted (see {@link ExecutionPolicy#RESTART}).
         * <p>
         * A mid-cascade node (anything but this run's {@link #entryNode}) with a non-{@code PARALLEL}
         * policy is first passed through its {@link ReentryGate}: if another run is currently inside
         * this node's {@code process()}, the node's policy decides whether this firing is dropped,
         * coalesced-and-queued behind it, or restarts it. The gate is released the instant this run's
         * {@code process()} returns — before any downstream is scheduled — so the gated window is
         * exactly the node's own {@code process()} (see {@link #reentryGates}).
         */
        private void fire(BaseNode node) {
            ReentryGate held = null;
            try {
                if (token.isCancelled()) {
                    return;
                }
                ExecutionPolicy policy = node.getExecutionPolicy();
                if (node != entryNode && policy != ExecutionPolicy.PARALLEL) {
                    held = acquireReentryGate(node, policy);
                    if (held == null) {
                        // DROP hit a busy node, or a QUEUE/RESTART wait was coalesced away by a newer
                        // arrival: abandon this branch. Downstream past this node is skipped for this run.
                        return;
                    }
                }
                context.run(() -> resolveInternal(context, node));

                // Gated window is this node's process(): release before cascading downstream so a
                // sibling run waiting on this node can proceed as soon as our process() is done.
                if (held != null) {
                    held.release();
                    held = null;
                }

                // Which out-ports fired: whatever process() activated, or - if it activated nothing
                // - all of them (see BaseNode.activate). A branch node narrows the cascade this way.
                Set<FlowPort> activated = context.activatedOf(node);
                for (FlowEdge flowEdge : getOutgoingFlowEdges(node)) {
                    if (!activated.isEmpty() && !activated.contains(flowEdge.getSourcePort())) {
                        continue;
                    }
                    if (token.isCancelled()) {
                        break;
                    }
                    callbackExecutor.execute(() -> notifyFlowEdgeTraversed(flowEdge));
                    schedule(flowEdge.getTargetNode());
                }
            } catch (RuntimeException e) {
                log.error("Triggered execution of \"{}\" failed: {}", node.getName(), rootCause(e));
            } finally {
                if (held != null) {
                    // process() threw before we released: don't leak the gate or a sibling run parks forever.
                    held.release();
                }
                if (pending.decrementAndGet() == 0) {
                    onComplete.run();
                }
            }
        }
    }

    /**
     * Per-entry-node execution bookkeeping for {@link ExecutionPolicy}. All fields are
     * guarded by this object's own monitor (the {@code synchronized (state)} blocks in
     * {@link #execute(BaseNode, Runnable)} and {@link #onRunComplete}).
     */
    private static final class EntryExecution {
        /** A run from this node is in flight (started and not yet quiesced). */
        boolean running;
        /** The coalesced next run's preparation, or null if none is queued. At most one. */
        Runnable pendingPrepare;
        /** Cancellation handle for the in-flight run, used by a RESTART trigger. */
        PassToken runningToken;
    }

    /**
     * A single mid-cascade node's process-scoped re-entry gate (see {@link #reentryGates}). At most
     * one run <em>holds</em> the gate at a time — the one currently inside the node's
     * {@code process()} — and its {@link ExecutionPolicy} decides what a second run's arrival does:
     * {@code DROP} abandons the arrival, {@code QUEUE} coalesces it behind the holder (at most one
     * waiter; a newer arrival evicts an older one), {@code RESTART} interrupts the holder and then
     * queues like {@code QUEUE}. {@code PARALLEL} never reaches here — those firings skip the gate.
     * All state is guarded by this object's own monitor. Non-blocking to release; a waiting arrival
     * parks a cheap virtual thread until it's handed the gate or coalesced away.
     */
    private static final class ReentryGate {
        /** A run currently holds the gate (is inside, or about to enter, the node's {@code process()}). */
        private boolean busy;
        /** The holder's firing thread, so a {@code RESTART} arrival can interrupt its {@code process()}. */
        private Thread holderThread;
        /** The one coalesced arrival waiting for the gate, or null when none waits. */
        private Waiter waiter;

        /**
         * Acquires the gate for the calling firing thread under {@code policy}. Returns {@code this}
         * if the caller may run the node's {@code process()} (and must later {@link #release()} it),
         * or {@code null} if this branch should be abandoned — a {@code DROP} that found the gate
         * busy, or a {@code QUEUE}/{@code RESTART} wait that a newer arrival (or node removal)
         * coalesced away. For a busy {@code RESTART} the holder's thread is interrupted (cooperative)
         * before this arrival waits.
         */
        synchronized ReentryGate acquire(ExecutionPolicy policy) {
            if (!busy) {
                busy = true;
                holderThread = Thread.currentThread();
                return this;
            }
            if (policy == ExecutionPolicy.DROP) {
                return null;
            }
            if (policy == ExecutionPolicy.RESTART && holderThread != null) {
                holderThread.interrupt();
            }
            if (waiter != null) {
                waiter.cancelled = true; // evict the older waiter: coalesce to the latest arrival
            }
            Waiter self = new Waiter();
            waiter = self;
            notifyAll(); // wake a freshly-evicted waiter so it returns and abandons its branch
            while (!self.signaled && !self.cancelled) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (waiter == self) {
                        waiter = null;
                    }
                    return null;
                }
            }
            if (self.cancelled) {
                return null;
            }
            // Signaled: the previous holder handed us the still-held gate.
            holderThread = Thread.currentThread();
            return this;
        }

        /**
         * Releases the gate after the holder's {@code process()} returns. Hands it straight to the
         * coalesced waiter if one is queued (which then runs the node), otherwise marks the node free.
         */
        synchronized void release() {
            if (waiter != null) {
                waiter.signaled = true; // hand off: busy stays true, the waiter becomes the new holder
                waiter = null;
                holderThread = null;    // the woken waiter records its own thread
                notifyAll();
            } else {
                busy = false;
                holderThread = null;
            }
        }

        /** Frees a run parked here (used when the node is removed); the waiter abandons its branch. */
        synchronized void cancelWaiter() {
            if (waiter != null) {
                waiter.cancelled = true;
                waiter = null;
                notifyAll();
            }
        }

        /** One coalesced pending arrival: exactly one of the flags is eventually set. */
        private static final class Waiter {
            boolean signaled;  // gate handed to this waiter → run the node
            boolean cancelled; // evicted by a newer arrival (or node removal) → abandon
        }
    }

    /**
     * One run's cancellation flag. A RESTART trigger flips it; {@link Run#fire} checks it at each
     * node boundary and stops advancing the cascade. Volatile because it's set from the triggering
     * thread and read from the run's firing threads.
     */
    private static final class PassToken {
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
        }

        boolean isCancelled() {
            return cancelled;
        }
    }
}
