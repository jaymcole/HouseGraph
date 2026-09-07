package io.github.jaymcole.housegraph.graph;

/**
 * A handle on one in-flight {@link NodeGraph} run, handed to the caller that drives it while that
 * run's {@link ExecutionContext} is still bound.
 *
 * <h2>Why a run has to be reached into from outside</h2>
 * A run's state is per-run by design: node statuses, the flow-visited set and the computed-value
 * overlay all live in a context that is gone the moment the run quiesces. Everything the engine
 * itself needs from a run happens inside it, so nothing needed a handle — until a node in one graph
 * began driving a run in <em>another</em> (a module node running the graph it references). Such a
 * caller has two questions the engine has no other answer for, and both must be asked before the
 * context is discarded:
 * <ul>
 *   <li><b>What did the run produce?</b> Reading a node's output after the run is over gives the
 *       {@linkplain ExecutionContext#commitValuesOf committed mirror}, which is last-run-wins across
 *       concurrent runs — the wrong value whenever two runs overlap. {@link #pull} reads the run's
 *       own overlay instead, resolving anything the run has not already computed.</li>
 *   <li><b>Where did control get to?</b> {@link #hasRun} answers per run, so two concurrent runs
 *       through one graph cannot see each other's answer. Recording the same fact on the shared
 *       node — a flag a sink node sets when it fires — could not.</li>
 * </ul>
 *
 * <h2>Valid only during the call</h2>
 * A scope is passed to the harvest callback of {@link NodeGraph#runToCompletion} and is meaningful
 * only for the duration of that call. Retaining one and using it later reaches into a context
 * nothing else can see any more; do not.
 */
public interface RunScope {

    /**
     * Resolves {@code node} within this run, pulling its data dependencies and running its
     * {@code process()} — exactly as {@link NodeGraph#resolve} does, except that it reuses this
     * run's context instead of starting a fresh one. A node the run already executed short-circuits
     * on its status and is <em>not</em> run again; its computed value is read back as it stands.
     *
     * @param node the node to resolve; must belong to the graph this run is executing
     * @throws IllegalStateException if the node is not in that graph, or its data edges form a cycle
     */
    void pull(BaseNode node);

    /**
     * Whether {@code node} ran in this run — reached and executed, successfully or not. False for a
     * node control never reached, and for one whose branch was pruned.
     *
     * @param node the node to ask about
     * @return true if this run executed it
     */
    boolean hasRun(BaseNode node);
}
