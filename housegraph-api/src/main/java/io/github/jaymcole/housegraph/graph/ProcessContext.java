package io.github.jaymcole.housegraph.graph;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * The per-invocation handle passed to {@link BaseNode#process(ProcessContext)}. It gives a node
 * things its bare {@code process()} predecessor could not offer:
 * <ul>
 *   <li><b>Cooperative cancellation.</b> {@link #isCancelled()} / {@link #checkCancelled()} let a
 *       long-running {@code process()} notice that its run was superseded (a re-triggering
 *       {@link ExecutionPolicy#RESTART}), that its {@link BaseNode#getTimeoutMillis() timeout}
 *       elapsed, or that its thread was interrupted, and stop promptly. This is the part with no
 *       other access path: before the context existed, cancellation was only checked by the engine
 *       <em>between</em> nodes, so a CPU-bound {@code process()} — an image analysis, a long loop —
 *       could not be stopped at all; only a blocking call that happened to honour thread interruption
 *       could. A node that loops or does long work should poll {@link #checkCancelled()} periodically.</li>
 *   <li><b>Null-safe typed input reads.</b> {@link #get(NodeVariable, Object)} returns an input's
 *       value or a fallback when it is null — replacing the {@code getSafeValue()} helper that nodes
 *       used to hand-roll.</li>
 *   <li><b>Symmetric reads/writes.</b> {@link #get(NodeVariable)} and {@link #set(NodeVariable, Object)}
 *       so a node can go entirely through the context; both route through the same run value overlay
 *       as {@link NodeVariable#getValue()}/{@link NodeVariable#setValue}.</li>
 *   <li><b>Which flow-in port fired.</b> {@link #triggeredVia()} / {@link #wasTriggeredVia(FlowPort)}
 *       name the IN {@link FlowPort}s whose edges brought control to this node, so a node with
 *       several named entry points can branch on the one that fired — the inbound mirror of
 *       {@link BaseNode#activate}, which picks the OUT ports control leaves by. Empty when there was
 *       no flow arrival at all: a {@link BaseNode#beginProcessing() pull}, a node reached as another
 *       node's data dependency, or a run's externally-triggered entry node.</li>
 * </ul>
 * A context instance is created by the engine for one {@code process()} call and must not be retained
 * past it. Values still flow through {@link NodeVariable}'s overlay-aware accessors, so a node may
 * equally read {@code input.getValue()} directly; the {@code get}/{@code set} methods are
 * conveniences, while the cancellation methods are the reason the context exists.
 */
public final class ProcessContext {

    private final BooleanSupplier cancelled;
    private final Set<FlowPort> triggeredVia;

    /** Package-private: only the engine (same package) constructs a context for a {@code process()} call. */
    ProcessContext(BooleanSupplier cancelled, Set<FlowPort> triggeredVia) {
        this.cancelled = cancelled;
        this.triggeredVia = triggeredVia;
    }

    /** A context for a firing with no flow arrival behind it (a pull, or a test). */
    ProcessContext(BooleanSupplier cancelled) {
        this(cancelled, Set.of());
    }

    /**
     * A context that is never cancelled, for invoking a node's {@link BaseNode#process(ProcessContext)}
     * directly — outside the engine, with no run behind it (e.g. a unit test resolving a single node,
     * or a caller that wants a node's transform without a flow cascade). The engine builds its own
     * cancellation-aware contexts; use this only when there is nothing to cancel.
     *
     * @return a context whose {@link #isCancelled()} is always false and whose
     *         {@link #triggeredVia()} is empty (no run, so no flow arrival)
     */
    public static ProcessContext uncancelled() {
        return new ProcessContext(() -> false);
    }

    /**
     * Whether this {@code process()} should stop early: its run was cancelled by a superseding
     * {@link ExecutionPolicy#RESTART}, its {@link BaseNode#getTimeoutMillis() timeout} fired, or its
     * thread was interrupted. Polling this (or {@link #checkCancelled()}) inside a loop or between
     * expensive steps is how a node cooperates with cancellation; a node that never checks simply
     * runs to completion as it always did.
     *
     * @return true if the node should abandon its work
     */
    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }

    /**
     * Throws {@link CancellationException} when {@link #isCancelled()} is true, otherwise returns
     * normally. A convenience for the common "stop now" path deep inside a loop: the engine catches
     * the exception, marks the node incomplete, and unwinds the run without logging it as a failure.
     *
     * @throws CancellationException if the run or node has been cancelled
     */
    public void checkCancelled() {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Node processing was cancelled");
        }
    }

    /**
     * The IN {@link FlowPort}s whose {@link FlowEdge}s brought control to this node for this firing —
     * how a node with more than one named entry point tells them apart. The inbound counterpart to
     * {@link BaseNode#activate(FlowPort)}: that picks which OUT ports control leaves by, this reports
     * which IN ports it came in through.
     * <p>
     * Empty when no flow edge was involved: a {@link BaseNode#beginProcessing()} pull, a node resolved
     * as another node's data dependency, or the node a run was triggered on directly (its trigger came
     * from outside the graph, not along an edge). A node with exactly one flow-in port that only ever
     * does one thing can ignore this entirely.
     * <p>
     * Ordinarily a single port, since a node fires once per run: the first arrival fires it and later
     * ones are deduped away. Several ports appear when they genuinely arrived together — a
     * {@link BaseNode#isFlowJoin() flow join}, which fires only once every incoming edge has arrived,
     * or concurrent sibling branches reaching two of the node's ports before it ran. A port fed by
     * several edges (fan-in) contributes itself once however many of those edges arrive.
     * <p>
     * <b>Do not use two ports on one node as two behaviours within a single run.</b> The node still
     * fires once, so only the arrivals recorded by the time that firing starts are visible and the
     * rest are dropped. Genuinely distinct behaviours — a Start port and a Stop port — belong to
     * distinct triggers, and so to distinct runs, where each sees exactly its own port.
     *
     * @return the IN ports control arrived through, immutable and possibly empty
     */
    public Set<FlowPort> triggeredVia() {
        return triggeredVia;
    }

    /**
     * Whether control reached this node through {@code port} for this firing — the readable form of
     * {@code triggeredVia().contains(port)} for the usual "which entry point fired?" branch.
     *
     * @param port one of this node's IN flow ports
     * @return true if an edge into {@code port} triggered this firing
     */
    public boolean wasTriggeredVia(FlowPort port) {
        return triggeredVia.contains(port);
    }

    /**
     * This input's current value, or {@code fallback} when it is null — the null-safe read that
     * replaces per-node {@code getSafeValue} helpers.
     *
     * @param variable the input to read
     * @param fallback the value to use when the input's value is null
     * @param <T>      the variable's value type
     * @return the input's value, or {@code fallback} if it is null
     */
    public <T> T get(NodeVariable<T> variable, T fallback) {
        T value = variable.getValue();
        return value != null ? value : fallback;
    }

    /**
     * This variable's current value — the run's computed value if it has one, else the authored
     * value. Identical to {@link NodeVariable#getValue()}; provided so a node can read entirely
     * through the context.
     *
     * @param variable the variable to read
     * @param <T>      the variable's value type
     * @return its current value, possibly null
     */
    public <T> T get(NodeVariable<T> variable) {
        return variable.getValue();
    }

    /**
     * Sets a variable's value for this run. Identical to {@link NodeVariable#setValue} (both write
     * into the run's value overlay); provided for symmetry with {@link #get}.
     *
     * @param variable the output to write
     * @param value    the value to store
     * @param <T>      the variable's value type
     */
    public <T> void set(NodeVariable<T> variable, T value) {
        variable.setValue(value);
    }
}
