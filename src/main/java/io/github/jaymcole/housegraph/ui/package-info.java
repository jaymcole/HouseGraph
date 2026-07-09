/**
 * The JavaFX layer: the canvas and everything the user directly interacts with.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.GraphCanvas} is the hub — it hosts
 * {@link io.github.jaymcole.housegraph.ui.view.NodeView}s and the edge views between them,
 * driving user gestures into the graph engine. The rest of the layer is split by concern into
 * sub-packages:
 * <ul>
 *   <li>{@link io.github.jaymcole.housegraph.ui.view} — the node/edge/port views and their
 *       extension points ({@code NodeContentProvider}, {@code ValueEditors}' sibling glyphs).</li>
 *   <li>{@link io.github.jaymcole.housegraph.ui.editor} — inline value/secret editing
 *       ({@code ValueEditors}, {@code SecretsEditor}).</li>
 *   <li>{@link io.github.jaymcole.housegraph.ui.command} — reversible mutations
 *       ({@code Command}) tracked by {@code UndoManager}.</li>
 *   <li>{@link io.github.jaymcole.housegraph.ui.io} — save/load ({@code GraphFileIO}).</li>
 * </ul>
 * All code here runs on the JavaFX Application Thread; the engine marshals its callbacks
 * onto it. See {@code docs/architecture/ui.md}.
 */
package io.github.jaymcole.housegraph.ui;
