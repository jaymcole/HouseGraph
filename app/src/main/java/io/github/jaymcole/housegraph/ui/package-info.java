/**
 * The JavaFX layer: the canvas and everything the user directly interacts with.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.GraphCanvas} is the hub — it hosts
 * {@link io.github.jaymcole.housegraph.ui.view.NodeView}s and the edge views between them,
 * driving user gestures into the graph engine. The rest of the layer is split by concern into
 * sub-packages:
 * <ul>
 *   <li>{@link io.github.jaymcole.housegraph.ui.view} — the node/edge/port views and the
 *       {@code ExecutionPolicyIcons} glyphs.</li>
 *   <li>{@link io.github.jaymcole.housegraph.ui.editor} — the secrets dialog
 *       ({@code SecretsEditor}).</li>
 *   <li>{@link io.github.jaymcole.housegraph.ui.command} — reversible mutations
 *       ({@code Command}) tracked by {@code UndoManager}.</li>
 *   <li>{@link io.github.jaymcole.housegraph.ui.snapshot} — the snapshot data model
 *       ({@code GraphSnapshot} and its {@code Clipboard*} records) shared by copy/paste and
 *       save/load.</li>
 *   <li>{@link io.github.jaymcole.housegraph.ui.io} — save/load ({@code GraphFileIO}).</li>
 * </ul>
 * All code here runs on the JavaFX Application Thread; the engine marshals its callbacks
 * onto it. See {@code docs/engine/ui-layer.md}.
 * <p>
 * The node-facing extension points this layer <em>dispatches</em> —
 * {@link io.github.jaymcole.housegraph.sdk.NodeContentProvider},
 * {@link io.github.jaymcole.housegraph.sdk.AutoStartable} and
 * {@link io.github.jaymcole.housegraph.sdk.ValueEditors} — are declared in the published
 * {@code housegraph-api} module, because the nodes that implement them live outside this
 * repository and cannot see {@code app}.
 */
package io.github.jaymcole.housegraph.ui;
