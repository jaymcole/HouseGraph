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
 *   <li>{@link io.github.jaymcole.housegraph.sdk.ValueEditors} — make a custom value type
 *       manually editable in a node's inline field.</li>
 *   <li>{@link io.github.jaymcole.housegraph.sdk.Secrets} — read a credential by reference.</li>
 * </ul>
 *
 * <p>The first three lived in {@code ui/} until node implementations moved out of this
 * repository, at which point "a node depends on the UI package" stopped being merely untidy and
 * became impossible: an out-of-tree node cannot see {@code app}. They are dispatched by the host
 * with {@code instanceof}, so implementing one is the entire opt-in — there is nothing to
 * register.
 *
 * <p><b>This package is published API</b>, and the API is not stable yet. A breaking change here
 * means rebuilding every library compiled against it — today the first-party libraries in
 * {@code housegraph-nodes} and anything built from the plugin template — so make that change in
 * the same pass. See {@code docs/engine/plugin-runtime.md}.
 */
package io.github.jaymcole.housegraph.sdk;
