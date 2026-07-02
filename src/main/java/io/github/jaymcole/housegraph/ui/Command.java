package io.github.jaymcole.housegraph.ui;

/** A reversible canvas mutation, tracked by {@link UndoManager}. */
interface Command {

    void execute();

    void undo();
}
