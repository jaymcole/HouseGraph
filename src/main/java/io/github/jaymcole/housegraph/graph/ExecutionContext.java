package io.github.jaymcole.housegraph.graph;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-run execution state, isolated from every other concurrent run.
 * <p>
 * <b>Status: Stage-A spike.</b> This is the first slice of the concurrent-runs design in
 * {@code docs/design/per-node-execution-policy.md} — currently only the <em>computed-value
 * overlay</em>, the linchpin we wanted to de-risk before committing to the full
 * re-architecture. Node status, the visited set, activated flow-ports, join bookkeeping and
 * cancellation are <em>not</em> here yet; they join this class in later stages.
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
     * This run's computed values, keyed by variable identity. A {@code HashMap} suffices for the
     * spike because a context is only ever touched by one thread at a time; the real per-run
     * context, once a run fans out across branch threads, needs a null-tolerant concurrent map
     * (a plain {@code ConcurrentHashMap} rejects the null values an unset output legitimately has).
     */
    private final Map<NodeVariable<?>, Object> computedValues = new HashMap<>();

    /** The context bound to the calling thread, or null if none (then {@link NodeVariable} uses authored values). */
    static ExecutionContext current() {
        return CURRENT.get();
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
