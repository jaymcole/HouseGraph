/**
 * The node-authoring SDK: the parts of the published API that exist for node authors rather
 * than for the engine.
 *
 * <p>{@code graph/} is the model a node is built from — {@code BaseNode}, {@code NodeVariable},
 * {@code FlowPort}, {@code ProcessContext}. This package is everything else an author reaches
 * for, and each member is here because it must be usable from <em>outside</em> this repository:
 *
 * <ul>
 *   <li>{@link io.github.jaymcole.housegraph.sdk.NodeContentProvider} — give a node its own
 *       inline JavaFX UI. This is why the api module depends on JavaFX at all.</li>
 *   <li>{@link io.github.jaymcole.housegraph.sdk.AutoStartable} — resume a node's running state
 *       when a saved graph is reopened.</li>
 *   <li>{@link io.github.jaymcole.housegraph.sdk.NodePresentation} — the sink behind
 *       {@code BaseNode.present(Runnable)}, so a node's UI updates are a no-op when nothing is
 *       drawing that node.</li>
 *   <li>{@link io.github.jaymcole.housegraph.sdk.NodeTimer} — a node's repeating clock, with no
 *       toolkit behind it.</li>
 *   <li>{@link io.github.jaymcole.housegraph.sdk.RuntimeMode} — tell a supervised daemon graph
 *       apart from one a person opened by hand.</li>
 *   <li>{@link io.github.jaymcole.housegraph.sdk.ValueEditors} — make a custom value type
 *       manually editable in a node's inline field.</li>
 *   <li>{@link io.github.jaymcole.housegraph.sdk.Secrets} — read a credential by reference.</li>
 * </ul>
 *
 * <p>{@code NodeContentProvider}, {@code AutoStartable} and {@code RuntimeMode} lived in
 * {@code ui/} until node implementations moved out of this repository, at which point "a node
 * depends on the UI package" stopped being merely untidy and became impossible: an out-of-tree
 * node cannot see {@code app}. They are dispatched by the host with {@code instanceof}, so
 * implementing one is the entire opt-in — there is nothing to register.
 *
 * <p>{@code NodePresentation} and {@code NodeTimer} are the pair that lets a node with a
 * running/stopped lifecycle keep that state in itself rather than in its controls, so it works
 * whether or not anything is drawing it. Whether a node has a view is asked <em>of that node</em>
 * ({@code BaseNode.hasView()}) and never of the process: {@code RuntimeMode.isDaemon()} answers a
 * different question and is wrong for a graph nested inside another graph, whose interior nodes
 * have no view in an app that is fully windowed.
 *
 * <p><b>This package is published API</b>, and the API is not stable yet. A breaking change here
 * means rebuilding every library compiled against it — today the first-party libraries in
 * {@code housegraph-nodes} and anything built from the plugin template — so make that change in
 * the same pass. See {@code docs/engine/plugin-runtime.md}.
 */
package io.github.jaymcole.housegraph.sdk;
