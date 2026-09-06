package io.github.jaymcole.housegraph.sdk;

import io.github.jaymcole.housegraph.graph.BaseNode;

/**
 * The sink a node's inline UI updates travel through, installed by whatever is drawing that
 * node and absent when nothing is. It is what makes a node's presentation code safe to run
 * with no view: a node never touches a control directly, it hands the update to
 * {@link BaseNode#present(Runnable)}, and with no view installed the update is simply not run.
 *
 * <h2>Per node, not per process</h2>
 * "Has a view" is a property of <em>one node</em>. A headless daemon is the obvious case where
 * the answer is no for every node, but it is not the only one, and a process-wide flag such as
 * {@link RuntimeMode#isDaemon()} answers a different question: a graph referenced from inside
 * another graph has no view for any of its interior nodes while the app around it has a window,
 * a canvas, and views for everything else. So the seam is a per-node reference, and
 * {@link BaseNode#hasView()} reads it — nothing here consults the process.
 *
 * <h2>Threading</h2>
 * An implementation decides where the update runs. The desktop app's node view marshals it onto
 * the JavaFX Application Thread, running it inline when the caller is already there, so a button
 * handler still sees its own effect immediately while a background tick does not touch a control
 * from the wrong thread. That marshalling is deliberately the host's job: it is why this
 * interface takes a plain {@link Runnable} and mentions no toolkit, and why the engine can carry
 * it without importing JavaFX.
 *
 * <h2>Who calls what</h2>
 * Node authors call {@link BaseNode#present(Runnable)} and never implement this interface. Hosts
 * implement it and install it with {@link BaseNode#setPresentation(NodePresentation)} when they
 * build a node's content, clearing it when that content goes away.
 *
 * @see BaseNode#present(Runnable)
 * @see NodeContentProvider
 */
@FunctionalInterface
public interface NodePresentation {

    /**
     * Runs one update against the node's inline controls, on whatever thread this sink's owner
     * requires.
     *
     * @param uiUpdate the control update to apply
     */
    void update(Runnable uiUpdate);
}
