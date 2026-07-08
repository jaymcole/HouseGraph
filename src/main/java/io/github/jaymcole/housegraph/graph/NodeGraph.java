package io.github.jaymcole.housegraph.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
 * reconvergence no longer waits for all incoming branches, joining is an explicit concern — see the
 * design doc {@code docs/design/per-node-execution-policy.md} (a join node is planned).
 * <p>
 * Structural methods (adding/removing nodes and edges, reading the topology) stay
 * {@code synchronized} on this instance for their own brief critical section, but that
 * lock is never held for a whole run — so a UI-thread edit isn't forced to wait out a slow
 * in-flight trigger.
 * <p>
 * <b>Re-entrant triggers.</b> When {@link #execute(BaseNode, Runnable)} is called on a node
 * whose earlier run is still in flight, the node's {@link ExecutionPolicy} decides the outcome:
 * {@code DROP} ignores it, {@code RESTART} cancels the in-flight run's remaining cascade and runs a
 * fresh one, {@code QUEUE} (the default) coalesces to a single pending follow-up run, and
 * {@code PARALLEL} starts an independent concurrent run every time. {@code DROP}/{@code QUEUE}/
 * {@code RESTART} keep a single in-flight run per entry node via {@link EntryExecution}; different
 * entry nodes (and {@code PARALLEL} re-fires) run concurrently.
 * <p>
 * This class never imports anything from JavaFX: node/edge execution callbacks
 * ({@link BaseNode#onExecuted()}, {@link GraphExecutionListener}) are dispatched
 * through an injectable {@link #setCallbackExecutor callback executor}, which defaults
 * to running them immediately on the calling (here, background) thread. The UI layer
 * supplies {@code Platform::runLater} so those callbacks — which touch JavaFX nodes —
 * actually run on the FX Application Thread.
 */
public class NodeGraph {

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
        if (!edge.getTargetVariable().type.isAssignableFrom(edge.getSourceVariable().type)) {
            throw new IllegalArgumentException(
                    "Cannot connect a " + edge.getSourceVariable().type.getSimpleName() + " output to a "
                            + edge.getTargetVariable().type.getSimpleName() + " input");
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
            try {
                node.process();
                setStatus(context, node, NodeProcessingStatus.SUCCESS);
                node.setLastError(null);
            } catch (Exception e) {
                setStatus(context, node, NodeProcessingStatus.FAILED);
                node.setLastError(e);
                System.err.println("Node \"" + node.getName() + "\" failed to process: " + e);
            }
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
     * Records {@code node}'s status for this run (authoritative, used for cycle detection and
     * dedup) and mirrors it onto the node itself, so post-run observers — the UI and tests
     * calling {@link BaseNode#getStatus()} — can still see how a node's last run ended. The
     * mirror is last-run-wins across concurrent runs; only the context copy drives execution.
     */
    private void setStatus(ExecutionContext context, BaseNode node, NodeProcessingStatus status) {
        context.setStatus(node, status);
        node.setStatus(status);
    }

    @SuppressWarnings("unchecked")
    private void propagateValue(Edge edge) {
        edge.getTargetVariable().setValue(edge.getSourceVariable().getValue());
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

        Run(Runnable onComplete) {
            this.onComplete = onComplete;
        }

        PassToken token() {
            return token;
        }

        /** Runs {@code prepare} in this run's context (so an event payload lands in its own value overlay), then fires {@code entry}. */
        void start(BaseNode entry, Runnable prepare) {
            try {
                context.run(prepare);
            } catch (RuntimeException e) {
                System.err.println("Trigger preparation for \"" + entry.getName() + "\" failed: " + rootCause(e));
            }
            // Always schedule, even if prepare threw, so the run reaches onComplete and balances its beginPass().
            schedule(entry);
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
         */
        private void fire(BaseNode node) {
            try {
                if (token.isCancelled()) {
                    return;
                }
                context.run(() -> resolveInternal(context, node));

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
                System.err.println("Triggered execution of \"" + node.getName() + "\" failed: " + rootCause(e));
            } finally {
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
