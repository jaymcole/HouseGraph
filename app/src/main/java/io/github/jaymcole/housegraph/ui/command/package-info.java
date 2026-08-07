/**
 * Undo/redo: the {@link io.github.jaymcole.housegraph.ui.command.Command} pattern and the
 * {@link io.github.jaymcole.housegraph.ui.command.UndoManager} that keeps a linear history.
 * <p>
 * Every reversible canvas mutation (add/remove nodes, move, create data/flow edges, paste,
 * re-route waypoints) is a {@code Command} with {@code execute()}/{@code undo()}, so it
 * participates in undo rather than mutating the canvas ad hoc. Commands run on the JavaFX
 * Application Thread and call back into {@link io.github.jaymcole.housegraph.ui.GraphCanvas}.
 * See {@code docs/architecture/ui.md}.
 */
package io.github.jaymcole.housegraph.ui.command;
