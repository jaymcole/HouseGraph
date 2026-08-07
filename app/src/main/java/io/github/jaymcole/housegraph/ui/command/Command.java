package io.github.jaymcole.housegraph.ui.command;

/** A reversible canvas mutation, tracked by {@link UndoManager}. */
public interface Command {

    void execute();

    void undo();
}
