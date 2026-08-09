package io.github.jaymcole.housegraph.sdk;

import io.github.jaymcole.housegraph.graph.BaseNode;

/**
 * Opt-in extension point for a {@link NodeContentProvider} node with a running/stopped
 * lifecycle (a Start/Stop or Connect/Disconnect resource — the repeating trigger, the echo
 * resource, the web server, the Discord bot) to <em>resume that running state automatically</em>
 * when a saved graph is reloaded, if the node was running when the graph was last saved.
 * <p>
 * The two halves of the contract:
 * <ul>
 *   <li><b>Persist the running flag.</b> A node persists whether it is currently live in its
 *       {@link BaseNode#saveState()} map (conventionally {@code "running" -> "true"}) and reads
 *       it back in {@link BaseNode#loadState(java.util.Map)}, exactly like any other node config.
 *       Because only save/load carries the state map (copy/paste duplication does not — see
 *       {@code NodeRegistry#duplicate}), a pasted copy of a running node never auto-starts.</li>
 *   <li><b>Resume on load.</b> {@link #autoStartIfWasRunning()} is called once, on the FX thread,
 *       after the <em>whole</em> graph — every node and every edge — has been loaded onto the
 *       canvas. That ordering matters: the node's {@link BaseNode#onActivated()} has already run
 *       (so a resource is registered), and its incoming data edges are wired (so a node that pulls
 *       an input at Start, like the web server's {@code Store}, sees its wiring). The method is a
 *       no-op unless the node was running at save time.</li>
 * </ul>
 * Resuming reuses the node's normal user-driven start path (the same code its Start/Connect button
 * runs), so any off-UI-thread work (a gateway login, a socket bind) stays off the FX thread just as
 * it does on a button press.
 *
 * <h2>This is also the "on startup" hook</h2>
 * The timing above — once, after every node and edge is in place, with the state map already
 * loaded — is exactly what an <em>on-startup trigger</em> node needs, and using it that way is
 * intended rather than a workaround: such a node simply calls {@link BaseNode#execute()} from
 * {@link #autoStartIfWasRunning()}. That matters most for a graph running unattended, where nobody
 * is present to press Start (see {@code docs/architecture/deployment.md}).
 *
 * <p><b>Do not add a second interface for it.</b> {@code BaseNode}'s abstract method set is frozen
 * and this module is published API, so a near-duplicate of this contract would be permanent surface
 * bought for nothing. The copy/paste rule even falls out for free: a duplicated node carries no
 * state map, so a pasted startup trigger never fires.
 *
 * @see NodeContentProvider
 */
public interface AutoStartable {

    /**
     * Resumes this node's running state if it was running when the graph was last saved; a no-op
     * otherwise. Called once on the FX thread after the full graph (nodes + edges) has loaded.
     */
    void autoStartIfWasRunning();
}
