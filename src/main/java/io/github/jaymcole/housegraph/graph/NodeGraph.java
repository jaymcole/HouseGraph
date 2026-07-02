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
 * a concurrently-running {@link #execute}. Every method that touches this graph's
 * shared state is {@code synchronized} on top of that, so a UI-thread edit (deleting a
 * node, wiring an edge) can't interleave with an in-flight background traversal and
 * corrupt it — worst case, such an edit briefly blocks until the current run finishes.
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

    /** Tracks which nodes have already been cascaded through during the current execute() pass. */
    private final Set<BaseNode> flowVisited = new HashSet<>();

    private final List<GraphExecutionListener> executionListeners = new ArrayList<>();

    /** All graph reads/writes and traversal happen on this single thread, so nothing ever races. */
    private final ExecutorService executionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "housegraph-execution");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Executor callbackExecutor = Runnable::run;

    public synchronized void addExecutionListener(GraphExecutionListener listener) {
        executionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** How node/edge execution callbacks get from the background execution thread to wherever they need to run. */
    public void setCallbackExecutor(Executor callbackExecutor) {
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    // --- Node lifecycle ---------------------------------------------------------

    public synchronized void addNode(BaseNode node) {
        Objects.requireNonNull(node, "node");
        if (node.getGraph() != null && node.getGraph() != this) {
            throw new IllegalStateException(node.getName() + " already belongs to another NodeGraph");
        }
        if (nodes.add(node)) {
            node.setGraph(this);
        }
    }

    public synchronized void removeNode(BaseNode node) {
        Objects.requireNonNull(node, "node");
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
        nodes.remove(node);
        node.setGraph(null);
    }

    public synchronized Set<BaseNode> getNodes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
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
        executionExecutor.execute(() -> {
            try {
                executeEntry(node);
            } catch (RuntimeException e) {
                System.err.println("Triggered execution of \"" + node.getName() + "\" failed: " + e);
            }
        });
    }

    private synchronized void resolveEntry(BaseNode node) {
        requireRegistered(node);
        resetAllStatuses();
        resolveInternal(node);
    }

    private synchronized void executeEntry(BaseNode node) {
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
        for (FlowEdge flowEdge : getOutgoingFlowEdges(node)) {
            callbackExecutor.execute(() -> notifyFlowEdgeTraversed(flowEdge));
            executeInternal(flowEdge.getTargetNode());
        }
    }

    private void resolveInternal(BaseNode node) {
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
        for (BaseNode node : nodes) {
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

    private void requireRegistered(BaseNode node) {
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
