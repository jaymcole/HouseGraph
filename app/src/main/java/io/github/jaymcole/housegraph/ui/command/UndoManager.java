package io.github.jaymcole.housegraph.ui.command;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Linear undo/redo history of {@link Command}s. Executing a new command clears the
 * redo stack, matching standard editor behavior (you can't redo past a fresh edit).
 */
public class UndoManager {

    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    /** Runs a command for the first time and records it for undo. */
    public void execute(Command command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear();
    }

    /**
     * Records a command as having already happened (its {@link Command#execute()} is
     * NOT called), for actions applied live outside the undo system - e.g. a node drag
     * updates position on every mouse-moved event for real-time feedback, and only
     * gets wrapped into a single undo step once the gesture ends.
     */
    public void record(Command command) {
        undoStack.push(command);
        redoStack.clear();
    }

    public void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        Command command = undoStack.pop();
        command.undo();
        redoStack.push(command);
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        Command command = redoStack.pop();
        command.execute();
        undoStack.push(command);
    }

    /** Wipes all history - e.g. loading a different graph makes the previous one's history meaningless. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
