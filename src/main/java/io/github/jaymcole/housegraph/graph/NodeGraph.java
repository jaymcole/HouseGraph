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

    /** How node/edge execution callbacks get from the background execution thread to wherever they need to run. */
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

    public synchronized void registerEdge(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        requireRegistered(edge.getSourceNode());
        requireRegistered(edge.getTargetNode());
        if (edge.getSourceVariable().type != edge.getTargetVariable().type) {
            throw new IllegalArgumentException(
                    "Cannot connect a " + edge.getSourceVariable().type.getSimpleName() + " output to a "
                            + edge.getTargetVariable().type.getSimpleName() + " input");
        }
        addToSet(outgoingDataEdges, edge.getSourceNode(), edge);
        addToSet(incomingDataEdges, edge.getTargetNode(), edge);
    }

    public synchronized void removeEdge(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        removeFromSet(outgoingDataEdges, edge.getSourceNode(), edge);
        removeFromSet(incomingDataEdges, edge.getTargetNode(), edge);
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
     */
    public void execute(BaseNode node, Runnable prepare) {
        executionExecutor.execute(() -> {
            try {
                prepare.run();
                executeEntry(node);
            } catch (RuntimeException e) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                System.err.println("Triggered execution of \"" + node.getName() + "\" failed: " + cause);
            }
        });
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

    private void executeEntry(BaseNode node) {
        requireRegistered(node);
        resetAllStatuses();
        flowVisited.clear();
        executeInternal(node);
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

    private void executeInternal(BaseNode node) {
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
            callbackExecutor.execute(() -> notifyFlowEdgeTraversed(flowEdge));
            branches.add(CompletableFuture.runAsync(() -> executeInternal(flowEdge.getTargetNode()), branchExecutor));
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
     * Blocks until every resolve()/execute() call submitted before this one has
     * finished running. Not needed by the app itself (nothing there waits on
     * completion — that's the whole point), but lets tests deterministically wait for
     * an execute() call's background work before asserting on its effects.
     */
    public void awaitIdle() {
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

    private static <T> void addToSet(Map<BaseNode, Set<T>> map, BaseNode node, T value) {
        map.computeIfAbsent(node, key -> new HashSet<>()).add(value);
    }

    private static <T> void removeFromSet(Map<BaseNode, Set<T>> map, BaseNode node, T value) {
        Set<T> set = map.get(node);
        if (set != null) {
            set.remove(value);
        }
    }
}
