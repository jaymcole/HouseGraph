package io.github.jaymcole.housegraph.sdk;

import io.github.jaymcole.housegraph.graph.BaseNode;

/**
 * Opt-in extension point for a node with a running/stopped lifecycle (a Start/Stop or
 * Connect/Disconnect resource — the repeating trigger, the echo resource, the web server, the
 * Discord bot) to <em>resume that running state automatically</em> when a saved graph is
 * reloaded, if the node was running when the graph was last saved.
 * <p>
 * The two halves of the contract:
 * <ul>
 *   <li><b>Persist the running flag.</b> A node persists whether it is currently live in its
 *       {@link BaseNode#saveState()} map (conventionally {@code "running" -> "true"}) and reads
 *       it back in {@link BaseNode#loadState(java.util.Map)}, exactly like any other node config.
 *       Because only save/load carries the state map (copy/paste duplication does not — see
 *       {@code NodeRegistry#duplicate}), a pasted copy of a running node never auto-starts.</li>
 *   <li><b>Resume on load.</b> {@link #autoStartIfWasRunning()} is called once, after the
 *       <em>whole</em> graph — every node and every edge — has been loaded. That ordering
 *       matters: the node's {@link BaseNode#onActivated()} has already run (so a resource is
 *       registered), and its incoming data edges are wired (so a node that pulls an input at
 *       Start, like the web server's {@code Store}, sees its wiring). The method is a no-op
 *       unless the node was running at save time.</li>
 * </ul>
 * Resuming reuses the node's normal user-driven start path (the same code its Start/Connect button
 * runs), so any work that must not block the caller (a gateway login, a socket bind) belongs on a
 * worker here exactly as it does on a button press.
 *
 * <h2>The thread is the loader's, and there may be no view</h2>
 * The desktop canvas calls this on the JavaFX Application Thread once it has drawn the graph, so
 * a node loaded there may touch its controls directly. <b>That is the canvas's guarantee, not
 * this interface's.</b> A loader with no canvas calls it on whatever thread opened the graph, and
 * the node it calls has never had {@link NodeContentProvider#createNodeContent()} run — every
 * control field is null. So a start path reached from here must keep its running state in the
 * node, drive any clock with something that is not a {@code javafx.animation.Timeline} (see
 * {@link NodeTimer}), and route every control update through {@link BaseNode#present(Runnable)},
 * which discards it when {@link BaseNode#hasView()} is false and marshals it to the right thread
 * when it is not.
 *
 * <h2>This is also the "on startup" hook</h2>
 * The timing above — once, after every node and edge is in place, with the state map already
 * loaded — is exactly what an <em>on-startup trigger</em> node needs, and using it that way is
 * intended rather than a workaround: such a node simply calls {@link BaseNode#execute()} from
 * {@link #autoStartIfWasRunning()}. That matters most for a graph running unattended, where nobody
 * is present to press Start (see {@code docs/engine/remote-runtime.md}).
 *
 * <p>Use this hook rather than adding a second startup interface: the timing is already what such a
 * node needs, and the copy/paste rule falls out for free — a duplicated node carries no state map,
 * so a pasted startup trigger never fires.
 *
 * @see NodeContentProvider
 */
public interface AutoStartable {

    /**
     * Resumes this node's running state if it was running when the graph was last saved; a no-op
     * otherwise. Called once after the full graph (nodes + edges) has loaded, on the loading
     * thread, and possibly on a node that has no view — see the class comment.
     */
    void autoStartIfWasRunning();
}
