package io.github.jaymcole.housegraph.graph;

/**
 * Notified by {@link NodeGraph} as a resolve/execute pass runs, so a UI layer can
 * react (e.g. flash the node/edge that just fired) without {@link NodeGraph} itself
 * needing to know anything about how it's displayed. Register via
 * {@link NodeGraph#addExecutionListener}.
 */
public interface GraphExecutionListener {

    /** A node's process() is about to be called - it may take a while (see NodeGraph's threading notes). */
    default void onNodeStarted(BaseNode node) {
    }

    /** A node just finished a process() attempt, successful or not. */
    default void onNodeExecuted(BaseNode node) {
    }

    /** A value was just copied across this data edge, from source to target. */
    default void onDataEdgeTraversed(Edge edge) {
    }

    /** Execution just cascaded across this flow edge, from source to target. */
    default void onFlowEdgeTraversed(FlowEdge edge) {
    }
}
