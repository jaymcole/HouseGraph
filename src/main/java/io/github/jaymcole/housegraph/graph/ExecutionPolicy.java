package io.github.jaymcole.housegraph.graph;

/**
 * What happens when a node is {@linkplain BaseNode#execute() triggered} while a pass it
 * started earlier is still in flight — the re-entrant-trigger problem (e.g. a camera
 * fires another motion event while the previous motion pass is still running a slow
 * Discord send).
 * <p>
 * The policy is scoped to the <em>entry node</em> of a pass — the node {@code execute()}
 * is actually called on (a Trigger, a Repeating Trigger, an event listener, a Discord
 * command). Nodes reached downstream during a cascade aren't re-entered by this
 * mechanism; the pass already dedupes them ({@link NodeGraph}'s {@code flowVisited}).
 * <p>
 * All four are implemented: the engine runs each trigger as an isolated concurrent
 * {@code Run} (see {@link NodeGraph}), so {@link #PARALLEL} simply starts a new run every
 * time while {@link #DROP}/{@link #QUEUE}/{@link #RESTART} keep a single in-flight run per
 * entry node.
 */
public enum ExecutionPolicy {

    /**
     * Ignore the new trigger while a pass from this node is already in flight (running or
     * queued). The event is dropped outright — nothing is remembered or replayed.
     */
    DROP,

    /**
     * Cancel the in-flight pass's remaining cascade and run a fresh pass with the newest
     * inputs. Cancellation is cooperative: it's checked at each node boundary, so a node
     * <em>already executing</em> its {@code process()} still runs to completion — only the
     * not-yet-reached downstream nodes are skipped — and the replacement pass starts once
     * the cancelled one unwinds (passes serialize on one execution thread).
     */
    RESTART,

    /**
     * Run the new trigger after the in-flight pass finishes. Coalesces to the latest: at
     * most one pass is kept pending, so a burst of triggers on a slow graph collapses to
     * a single follow-up pass carrying the newest inputs rather than an unbounded backlog.
     * This is the default.
     */
    QUEUE,

    /**
     * Start an independent run for the new trigger, concurrent with the in-flight one(s) — no
     * single-flight gate and no coalescing. Safe because each run carries an isolated
     * {@link ExecutionContext}. Use it when overlapping invocations should all proceed (e.g. two
     * motion events each analysed on their own).
     */
    PARALLEL
}
