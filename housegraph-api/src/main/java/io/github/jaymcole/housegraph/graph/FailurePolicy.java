package io.github.jaymcole.housegraph.graph;

/**
 * What happens to a run's cascade when a node's {@code process()} fails — the counterpart to
 * {@link ExecutionPolicy}, which governs a node being re-entered rather than one going wrong.
 * <p>
 * A failure here means {@code process()} threw, or the node's timeout elapsed, or a required data
 * input's producer failed. It does <em>not</em> mean the node was cancelled: a run superseded by
 * {@link ExecutionPolicy#RESTART}, or stopped by {@link NodeGraph#dispose()}, marks its nodes
 * {@link NodeProcessingStatus#FAILED} but is an expected outcome rather than a fault, and never
 * routes to the error path. See {@link BaseNode#getErrorFlowPort()}.
 * <p>
 * Every node carries a policy (default {@link #HALT}); it is inert on a node no run ever flows
 * through, and on one that never fails.
 */
public enum FailurePolicy {

    /**
     * The failing node fires none of its ordinary flow-out ports: the branch stops there, and
     * {@link BaseNode#getErrorFlowPort() the node's Error port} fires instead. Wiring something
     * into that port is how a graph handles the failure; leaving it unwired means the branch
     * simply ends, which is what a graph with no error handling should do.
     * <p>
     * This is the default, and the reason is that the alternative is silently wrong: a camera
     * whose snapshot failed still has its previous image on its output, so a downstream send that
     * ran anyway would deliver stale data as though it were fresh.
     * <p>
     * Ports the node {@link BaseNode#activate activated} before it failed are discarded. A node
     * therefore does not need to order its {@code activate} calls defensively around the work that
     * might throw.
     */
    HALT,

    /**
     * The failing node cascades to its flow-out ports exactly as though it had succeeded, and its
     * Error port does not fire. Downstream nodes run against whatever values the node's outputs
     * happen to hold — the last successful run's, or the author's defaults.
     * <p>
     * This was the engine's only behaviour before failure policies existed. It is kept for the
     * node that genuinely means it: a best-effort step whose downstream does not depend on it
     * having worked, where a failure should not stop the branch. Prefer wiring the Error port
     * under {@link #HALT} to selecting this, since {@code CONTINUE} loses the distinction between
     * a run that worked and one that did not.
     */
    CONTINUE
}
