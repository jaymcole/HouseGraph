package io.github.jaymcole.housegraph.graph;

/**
 * What happens when a node is re-entered while work it started earlier is still in flight —
 * the re-entrant-trigger problem (e.g. a camera fires another motion event while the previous
 * motion pass is still running a slow Discord send).
 * <p>
 * The policy is applied at <b>two scopes</b> (see {@link NodeGraph} for the mechanics):
 * <ul>
 *   <li><b>At an entry node</b> — the node {@code execute()} is called on (a Trigger, a Repeating
 *       Trigger, an event listener, a Discord command) — it gates the <em>whole run</em>: a
 *       re-trigger arriving while that node's run is still in flight is dropped / restarted /
 *       coalesced / run in parallel.</li>
 *   <li><b>At a mid-cascade node</b> — one reached along a flow edge during a run — the same policy
 *       is re-applied at a narrower, <em>process-scoped</em> grain: it gates only the window in
 *       which some run is inside that node's {@code process()}. This is what lets two branches
 *       fanning out from one trigger carry different re-entrancy behavior (a slow branch that
 *       {@code DROP}s overlaps, a fast branch that {@code QUEUE}s them). A mid-cascade node still
 *       fires at most once per run — this gate is about <em>different</em> runs overlapping on it,
 *       not the within-run {@code flowVisited} dedup.</li>
 * </ul>
 * Every node carries a policy (default {@link #QUEUE}); it's inert on a pure data node that no run
 * ever flows through. All four are implemented: the engine runs each trigger as an isolated
 * concurrent {@code Run}, so {@link #PARALLEL} never gates while {@link #DROP}/{@link #QUEUE}/
 * {@link #RESTART} keep a single in-flight run (per entry node) or a single in-flight
 * {@code process()} (per mid-cascade node).
 * <p>
 * The wording below is written for the entry-node scope; at a mid-cascade node read "run" as "this
 * node's {@code process()}" and "cascade" as "this node's own execution".
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
