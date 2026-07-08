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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * <b>Threading.</b> {@link #execute} (the trigger path) runs the whole traversal on a
 * dedicated background thread and returns immediately, so a slow node (e.g. one that
 * deliberately blocks, for testing) doesn't freeze the UI thread that triggered it.
 * {@link #resolve}/{@link BaseNode#beginProcessing()} still behaves synchronously to
 * its caller — some callers need the result the instant it returns — but now runs
 * through that same background thread rather than in-place, so it can never race with
 * a concurrently-running {@link #execute}.
 * <p>
 * Within one {@link #execute} pass, sibling branches of a control-flow fan-out (a node
 * with more than one outgoing {@link FlowEdge}) run concurrently on independent virtual
 * threads, so a slow branch (e.g. a deliberately-delayed node) can never hold up an
 * unrelated sibling branch — {@link #execute} itself only returns once every branch of
 * the cascade has finished. A node reached from two different branches (its data pulled
 * through a shared upstream dependency) is only ever processed once: {@link #resolveInternal}
 * takes that node's own intrinsic lock for the duration of its resolution, so a second
 * branch arriving concurrently simply waits for the first to finish and then observes
 * its already-{@code SUCCESS}/{@code FAILED} status instead of re-running it. That same
 * lock, reentrant per-thread, is also what still turns a data cycle into a clean
 * {@link IllegalStateException} rather than a stack overflow or a stall.
 * <p>
 * Structural methods (adding/removing nodes and edges, reading the topology) stay
 * {@code synchronized} on this instance for their own brief critical section, but that
 * lock is never held for an entire pass — so a UI-thread edit is no longer forced to
 * wait out a slow in-flight trigger the way it would if the whole traversal shared one
 * lock.
 * <p>
 * <b>Re-entrant triggers.</b> When {@link #execute(BaseNode, Runnable)} is called on a node
 * whose earlier pass is still in flight, the node's {@link ExecutionPolicy} decides the
 * outcome: {@code DROP} ignores it, {@code RESTART} cancels the in-flight cascade and runs a
 * fresh pass, and {@code QUEUE} (the default) coalesces to a single pending follow-up pass.
 * All three build on the single serialized execution thread above — a pass never runs
 * concurrently with another. {@code PARALLEL} would break that and is not yet implemented
 * (it needs per-pass execution state instead of the statuses/visited-set the engine mutates
 * on the shared node/graph objects today); it currently falls back to {@code QUEUE}.
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
     * Tracks which nodes have already been cascaded through during the current
     * execute() pass. Concurrent sibling branches (see class Javadoc) can race to
     * claim the same node, so this needs to be a thread-safe set, not a plain HashSet.
     */
    private final Set<BaseNode> flowVisited = ConcurrentHashMap.newKeySet();

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
     * Every top-level resolve()/execute() call is submitted here, so two separate
     * calls (e.g. two triggers firing close together) never run at the same time —
     * the second simply waits in queue behind the first.
     */
    private final ExecutorService executionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "housegraph-execution");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Sibling flow-edge branches within a single execute() pass are each dispatched
     * here so they can make progress independently. Virtual threads are a natural fit:
     * cheap enough to spin up one per branch, and a branch that blocks (e.g. a debug
     * delay node sleeping) doesn't tie up a scarce platform thread.
     */
    private final ExecutorService branchExecutor = Executors.newVirtualThreadPerTaskExecutor();

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
        executionExecutor.shutdownNow();
        branchExecutor.shutdownNow();
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
     * upstream nodes first) and runs its process(). Every call is a fresh pass: all
     * nodes' statuses are reset first, so this never serves a stale cached value.
     * Blocks the calling thread until the pull completes (see the class Javadoc).
     *
     * @param node the node to resolve
     * @throws IllegalStateException if the data edges form a cycle
     */
    public void resolve(BaseNode node) {
        runOnExecutionThreadAndWait(() -> resolveEntry(node));
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
            // Phase 2: true concurrent passes need per-pass state the engine doesn't carry
            // yet (see class Javadoc). Fall back to QUEUE so saves/UI stay forward-compatible.
            policy = ExecutionPolicy.QUEUE;
        }

        EntryExecution state = entryExecutions.computeIfAbsent(node, ignored -> new EntryExecution());
        synchronized (state) {
            if (!state.running) {
                // Nothing in flight for this node: start a pass immediately, regardless of policy.
                state.running = true;
                beginPass();
                submitPass(node, prepare, state);
                return;
            }
            if (policy == ExecutionPolicy.DROP) {
                // A pass is already in flight or queued for this node; ignore this trigger.
                return;
            }
            // RESTART and QUEUE both coalesce to a single pending pass carrying the latest
            // inputs; RESTART additionally cancels the in-flight pass's remaining cascade so
            // the pending one effectively replaces it rather than following it.
            if (policy == ExecutionPolicy.RESTART && state.runningToken != null) {
                state.runningToken.cancel();
            }
            if (state.pendingPrepare == null) {
                beginPass(); // first pass to queue behind the running one
            }
            state.pendingPrepare = prepare; // replaces any earlier pending (coalesce to latest)
        }
    }

    /**
     * Submits one pass for {@code node} to the single execution thread. The caller must hold
     * {@code state}'s monitor; this records the pass's cancellation token so a later RESTART
     * can stop it.
     */
    private void submitPass(BaseNode node, Runnable prepare, EntryExecution state) {
        PassToken token = new PassToken();
        state.runningToken = token;
        executionExecutor.execute(() -> runPass(node, prepare, state, token));
    }

    private void runPass(BaseNode node, Runnable prepare, EntryExecution state, PassToken token) {
        try {
            prepare.run();
            executeEntry(node, token);
        } catch (RuntimeException e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            System.err.println("Triggered execution of \"" + node.getName() + "\" failed: " + cause);
        } finally {
            // Chain into a coalesced pending pass if one accumulated while this ran (QUEUE /
            // RESTART), otherwise mark the node idle so the next trigger starts fresh. Either
            // way this pass is done: endPass() balances the beginPass() from execute(). The
            // coalesced follow-up was counted separately when it was queued, so it keeps the
            // graph non-idle until it too finishes.
            synchronized (state) {
                if (state.pendingPrepare != null) {
                    Runnable next = state.pendingPrepare;
                    state.pendingPrepare = null;
                    submitPass(node, next, state); // stays "running"; runs the coalesced pass next
                } else {
                    state.running = false;
                    state.runningToken = null;
                }
            }
            endPass();
        }
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

    /**
     * Not {@code synchronized} — see class Javadoc. Exclusivity between separate
     * top-level resolve()/execute() calls instead comes from both sharing the single
     * {@link #executionExecutor} thread, and per-node exclusivity within a pass comes
     * from the intrinsic lock {@link #resolveInternal} takes on each node.
     */
    private void resolveEntry(BaseNode node) {
        requireRegistered(node);
        resetAllStatuses();
        resolveInternal(node);
    }

    private void executeEntry(BaseNode node, PassToken token) {
        requireRegistered(node);
        resetAllStatuses();
        flowVisited.clear();
        executeInternal(node, token);
    }

    private void runOnExecutionThreadAndWait(Runnable task) {
        try {
            executionExecutor.submit(task).get();
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

    private void executeInternal(BaseNode node, PassToken token) {
        // Cooperative RESTART cancellation: a cancelled pass stops advancing the cascade at
        // the next node boundary. The node currently mid-process() isn't interrupted (see
        // ExecutionPolicy.RESTART) - only not-yet-reached downstream work is skipped.
        if (token.isCancelled()) {
            return;
        }
        if (!flowVisited.add(node)) {
            return;
        }
        resolveInternal(node);

        // Which out-ports fired: whatever process() activated, or - if it activated
        // nothing - all of them (see BaseNode.activate). A branch/decider node narrows
        // the cascade to just its chosen port(s) simply by activating them.
        Set<FlowPort> activated = node.getActivatedOutputs();

        // Fan out to the chosen outgoing flow edges concurrently, each on its own
        // virtual thread, rather than recursing straight through them one at a time - a
        // slow sibling branch (e.g. behind a debug delay node) must not hold up the
        // others. This call still doesn't return - i.e. the cascade isn't considered
        // done - until every branch has finished, same as the old sequential version.
        List<CompletableFuture<Void>> branches = new ArrayList<>();
        for (FlowEdge flowEdge : getOutgoingFlowEdges(node)) {
            if (!activated.isEmpty() && !activated.contains(flowEdge.getSourcePort())) {
                continue;
            }
            if (token.isCancelled()) {
                break;
            }
            callbackExecutor.execute(() -> notifyFlowEdgeTraversed(flowEdge));
            branches.add(CompletableFuture.runAsync(() -> executeInternal(flowEdge.getTargetNode(), token), branchExecutor));
        }
        for (CompletableFuture<Void> branch : branches) {
            branch.join();
        }
    }

    /**
     * Resolving a node is scoped to that node's own intrinsic lock, reentrant per
     * thread. That gives two things at once: two concurrent flow branches that happen
     * to share a data dependency can't both run that shared node's process() - the
     * second simply blocks here until the first finishes, then sees its now-complete
     * status and returns without re-running it - and a single thread revisiting a node
     * it's already in the middle of resolving (a data cycle) still hits the
     * IN_PROGRESS check below rather than deadlocking on itself, exactly as it did
     * back when this whole traversal only ever ran on one thread.
     */
    private void resolveInternal(BaseNode node) {
        synchronized (node) {
            NodeProcessingStatus status = node.getStatus();
            if (status == NodeProcessingStatus.IN_PROGRESS) {
                throw new IllegalStateException("Cycle detected in data graph at node: " + node.getName());
            }
            if (status.isComplete()) {
                return;
            }

            node.setStatus(NodeProcessingStatus.IN_PROGRESS);
            for (Edge edge : getIncomingDataEdges(node)) {
                resolveInternal(edge.getSourceNode());
                propagateValue(edge);
                callbackExecutor.execute(() -> notifyDataEdgeTraversed(edge));
            }

            callbackExecutor.execute(() -> notifyNodeStarted(node));
            try {
                // Reset here (not at pass start) so it's cleared no matter how this
                // node is reached, right before the process() that may re-populate it.
                node.clearActivatedOutputs();
                node.process();
                node.setStatus(NodeProcessingStatus.SUCCESS);
                node.setLastError(null);
            } catch (Exception e) {
                node.setStatus(NodeProcessingStatus.FAILED);
                node.setLastError(e);
                System.err.println("Node \"" + node.getName() + "\" failed to process: " + e);
            }
            callbackExecutor.execute(() -> {
                node.onExecuted();
                notifyNodeExecuted(node);
            });
        }
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

    private void resetAllStatuses() {
        // getNodes() (not the raw field) since a structural edit from another thread
        // can now interleave with this - see class Javadoc - and iterating the live
        // mutable set directly would risk a ConcurrentModificationException.
        for (BaseNode node : getNodes()) {
            node.setStatus(NodeProcessingStatus.NOT_STARTED);
        }
    }

    /**
     * Blocks until the graph is idle: every triggered pass accepted so far has finished,
     * <em>including</em> any coalesced follow-up pass a burst of triggers queued (which is
     * submitted lazily from inside the pass ahead of it, so waiting on the executor queue
     * alone would miss it). Not needed by the app itself (nothing there waits on completion
     * — that's the whole point), but lets tests deterministically wait for an execute()
     * call's background work before asserting on its effects.
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
        // Flush any non-triggered work (e.g. an in-flight resolve()) still on the execution
        // thread, and let the final pass's runPass() fully unwind before we return.
        runOnExecutionThreadAndWait(() -> {
        });
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

    /**
     * Per-entry-node execution bookkeeping for {@link ExecutionPolicy}. All fields are
     * guarded by this object's own monitor (the {@code synchronized (state)} blocks in
     * {@link #execute(BaseNode, Runnable)} and {@link #runPass}).
     */
    private static final class EntryExecution {
        /** A pass from this node is in flight (submitted and not yet finished). */
        boolean running;
        /** The coalesced next pass's preparation, or null if none is queued. At most one. */
        Runnable pendingPrepare;
        /** Cancellation handle for the in-flight pass, used by a RESTART trigger. */
        PassToken runningToken;
    }

    /**
     * One pass's cancellation flag. A RESTART trigger flips it; {@link #executeInternal}
     * checks it at each node boundary and stops advancing the cascade. Volatile because it's
     * set from the triggering thread and read from the execution/branch threads.
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
