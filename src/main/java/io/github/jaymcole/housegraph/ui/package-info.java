/**
 * The JavaFX layer: the canvas and everything the user directly interacts with.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.GraphCanvas} hosts
 * {@link io.github.jaymcole.housegraph.ui.NodeView}s and the edge views between them,
 * driving user gestures into the graph engine; inline editing goes through
 * {@link io.github.jaymcole.housegraph.ui.ValueEditors}; reversible mutations are
 * {@link io.github.jaymcole.housegraph.ui.Command}s tracked by
 * {@link io.github.jaymcole.housegraph.ui.UndoManager}; and
 * {@link io.github.jaymcole.housegraph.ui.GraphFileIO} handles save/load.
 * <p>
 * All code here runs on the JavaFX Application Thread; the engine marshals its callbacks
 * onto it. See {@code docs/architecture/ui.md}.
 */
package io.github.jaymcole.housegraph.ui;
