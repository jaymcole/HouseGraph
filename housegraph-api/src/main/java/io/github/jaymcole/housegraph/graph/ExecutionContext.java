package io.github.jaymcole.housegraph.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Per-run execution state, isolated from every other concurrent run.
 * <p>
 * <b>Status: Stage-A (in progress).</b> This is the foundation of the concurrent-runs design
 * in {@code docs/engine/execution-policy.md}. It now carries the run's
 * <em>node statuses</em> and <em>flow-visited set</em> (previously mutable fields on the shared
 * node/graph objects) as well as the <em>computed-value overlay</em> (the linchpin de-risked by
 * the Stage-A spike). Still to move here in a later increment: activated flow-ports, join
 * bookkeeping and cancellation.
 * <p>
 * The status/visited maps are consulted through a context passed by parameter; the value overlay
 * is consulted through the {@link #run(Runnable) thread-local binding}. Both are concurrent
 * because within one run several branch threads touch this context at once.
 * <p>
 * <b>The overlay.</b> A node's {@link NodeVariable} holds two kinds of value: a stable
 * <em>authored</em> value (a constant, a typed-in config field) that lives on the node, and a
 * <em>computed</em> value produced while a run executes. Concurrent runs must not clobber one
 * another's computed values, so those live here — one context per run — while authored values
 * stay on the node and are read-only during a run (safe to share). {@link NodeVariable#getValue()}
 * returns this run's computed value if it has one, else falls back to the authored value;
 * {@link NodeVariable#setValue} writes into this overlay. The indirection lives entirely in
 * {@link NodeVariable}, so node {@code process()} code is untouched.
 * <p>
 * A context is bound to the executing thread via {@link #run(Runnable)}. When no context is
 * bound (the current engine, or UI code editing authored values), {@link NodeVariable} reads and
 * writes the authored value directly — so this class is inert until the engine starts binding it.
 */
public final class ExecutionContext {

    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();

    /**
     * This run's computed values, keyed by variable identity. A synchronized {@code HashMap}
     * (rather than a {@code ConcurrentHashMap}) because a run's branch threads write it in
     * parallel <em>and</em> an unset output legitimately holds a {@code null} value, which
     * {@code ConcurrentHashMap} forbids. Entries are only ever added during a run, never removed,
     * so a {@code containsKey}-then-{@code get} pair needs no extra locking.
     */
    private final Map<NodeVariable<?>, Object> computedValues = Collections.synchronizedMap(new HashMap<>());

    /**
     * This run's per-node status. Absent means {@link NodeProcessingStatus#NOT_STARTED} — so a
     * fresh context starts every node un-run, replacing the old "reset all statuses at pass
     * start" step. Concurrent because branch threads within one run resolve nodes in parallel.
     */
    private final Map<BaseNode, NodeProcessingStatus> statuses = new ConcurrentHashMap<>();

    /** Nodes already cascaded through in this run's flow traversal (dedup); replaces the old graph field. */
    private final Set<BaseNode> flowVisited = ConcurrentHashMap.newKeySet();

    /** Which flow-out ports each node fired this run (empty means "fire all"); replaces the old {@link BaseNode} field. */
    private final Map<BaseNode, Set<FlowPort>> activatedOutputs = new ConcurrentHashMap<>();

    /**
     * Nodes that explicitly activated <em>no</em> flow-out port this run via
     * {@link BaseNode#activateNone()} — the opposite of the default (an absent
     * {@link #activatedOutputs} entry, which means "fire all"). Kept as its own set rather than an
     * empty {@link #activatedOutputs} entry so the two "nothing recorded" states stay
     * distinguishable: never calling {@code activate} at all still fires every out-port.
     */
    private final Set<BaseNode> noActivation = ConcurrentHashMap.newKeySet();

    /**
     * The IN flow ports each node has had an edge arrive at this run — the inbound mirror of
     * {@link #activatedOutputs}. Accumulated by {@code NodeGraph.Run.schedule} on every arrival,
     * including ones the node-level dedup then drops, and snapshotted into the node's
     * {@link ProcessContext} when its {@code process()} is about to run
     * ({@link ProcessContext#triggeredVia()}). A node that runs only as a pulled data dependency, or
     * as a run's externally-triggered entry node, has no entry here and so reads as empty.
     */
    private final Map<BaseNode, Set<FlowPort>> flowArrivals = new ConcurrentHashMap<>();

    /**
     * Per-node resolution monitors, scoped to this run. Two branch threads of the same run that
     * share a data dependency take the same monitor (so the node resolves once); a data cycle
     * re-enters it on one thread (reentrant) and hits the {@code IN_PROGRESS} check. Crucially the
     * monitor is <em>per-context</em>, not the node object itself, so two concurrent runs sharing a
     * node don't serialize on each other's {@code process()} — each has isolated state anyway.
     */
    private final Map<BaseNode, Object> resolutionLocks = new ConcurrentHashMap<>();

    /** Arrival counts at flow-join nodes this run, so a join fires only once all its branches have arrived. */
    private final Map<BaseNode, AtomicInteger> joinArrivals = new ConcurrentHashMap<>();

    /**
     * Whether this run has been cancelled — a superseding {@link ExecutionPolicy#RESTART} at its
     * entry node. Defaults to never-cancelled (a synchronous {@code resolve} pull has no run behind
     * it); a {@link NodeGraph.Run} points this at its own cancellation token. Read from firing
     * threads via {@link ProcessContext}, so kept {@code volatile}.
     */
    private volatile BooleanSupplier cancellationSignal = () -> false;

    /** The context bound to the calling thread, or null if none (then {@link NodeVariable} uses authored values). */
    static ExecutionContext current() {
        return CURRENT.get();
    }

    /** Points this run's cancellation at {@code signal} (a {@link NodeGraph.Run}'s token). */
    void setCancellationSignal(BooleanSupplier signal) {
        this.cancellationSignal = signal;
    }

    /** Whether this run has been cancelled; consulted by {@link ProcessContext#isCancelled()}. */
    boolean isCancelled() {
        return cancellationSignal.getAsBoolean();
    }

    NodeProcessingStatus statusOf(BaseNode node) {
        return statuses.getOrDefault(node, NodeProcessingStatus.NOT_STARTED);
    }

    void setStatus(BaseNode node, NodeProcessingStatus status) {
        statuses.put(node, status);
    }

    /** Marks {@code node} as cascaded-through in this run; returns false if it already was (dedup). */
    boolean markFlowVisited(BaseNode node) {
        return flowVisited.add(node);
    }

    /** Records that {@code node} fired {@code port} this run (from {@link BaseNode#activate}). */
    void activate(BaseNode node, FlowPort port) {
        activatedOutputs.computeIfAbsent(node, ignored -> ConcurrentHashMap.newKeySet()).add(port);
    }

    /** The flow-out ports {@code node} fired this run; empty means "fire all" (see {@link BaseNode#activate}). */
    Set<FlowPort> activatedOf(BaseNode node) {
        return activatedOutputs.getOrDefault(node, Set.of());
    }

    /** Records that {@code node} activates none of its flow-out ports this run (see {@link BaseNode#activateNone}). */
    void activateNone(BaseNode node) {
        noActivation.add(node);
    }

    /**
     * Whether {@code node} explicitly activated no flow-out port this run — distinct from a node
     * that never called {@code activate} at all, which fires every out-port (see {@link #activatedOf}).
     */
    boolean activatesNone(BaseNode node) {
        return noActivation.contains(node);
    }

    /** Records that a flow edge into {@code port} arrived at {@code node} this run (see the field). */
    void recordFlowArrival(BaseNode node, FlowPort port) {
        flowArrivals.computeIfAbsent(node, ignored -> ConcurrentHashMap.newKeySet()).add(port);
    }

    /**
     * An immutable snapshot of the IN flow ports that have had an edge arrive at {@code node} so
     * far this run; empty when none have. Copied rather than exposed live so the set a
     * {@code process()} sees can't grow under it mid-call.
     */
    Set<FlowPort> flowArrivalsOf(BaseNode node) {
        Set<FlowPort> arrived = flowArrivals.get(node);
        return arrived == null ? Set.of() : Set.copyOf(arrived);
    }

    /** This run's resolution monitor for {@code node} (see the field). */
    Object lockFor(BaseNode node) {
        return resolutionLocks.computeIfAbsent(node, ignored -> new Object());
    }

    /**
     * Records one arrival at a flow-join {@code node} this run and returns the running total. A
     * join fires once its arrivals reach its wired incoming-edge count (see {@link BaseNode#isFlowJoin}).
     */
    int recordJoinArrival(BaseNode node) {
        return joinArrivals.computeIfAbsent(node, ignored -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Copies this run's computed values for {@code node}'s variables back onto the variables
     * themselves, so post-run observers (the UI's {@code onExecuted()}, tests, the next run's
     * authored-value fallback) can read them once no context is bound. Called right after the
     * node's {@code process()}. Last-writer-wins across concurrent runs — it's a display mirror,
     * not the isolated in-run value (which stays in this overlay). See the class Javadoc.
     */
    void commitValuesOf(BaseNode node) {
        for (NodeVariable<?> variable : node.getInputs()) {
            commit(variable);
        }
        for (NodeVariable<?> variable : node.getOutputs()) {
            commit(variable);
        }
    }

    private void commit(NodeVariable<?> variable) {
        if (computedValues.containsKey(variable)) {
            variable.commitComputed(computedValues.get(variable));
        }
    }

    boolean hasValue(NodeVariable<?> variable) {
        return computedValues.containsKey(variable);
    }

    @SuppressWarnings("unchecked")
    <T> T getValue(NodeVariable<T> variable) {
        return (T) computedValues.get(variable);
    }

    <T> void setValue(NodeVariable<T> variable, T value) {
        computedValues.put(variable, value);
    }

    /**
     * Runs {@code body} with this context bound as the current run on the calling thread, restoring
     * whatever was bound before (nesting-safe).
     *
     * @param body the work to run with this context active
     */
    public void run(Runnable body) {
        ExecutionContext previous = CURRENT.get();
        CURRENT.set(this);
        try {
            body.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
