package io.github.jaymcole.housegraph.ui.command;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Linear undo/redo history of {@link Command}s. Executing a new command clears the
 * redo stack, matching standard editor behavior (you can't redo past a fresh edit).
 *
 * <p>Also tracks whether the history has moved since the document was last saved
 * ({@link #isDirty()}), by comparing the undo stack's depth against the depth it was
 * at when {@link #markSaved()} was last called. This works because the stack depth is
 * a faithful position in the linear history: {@link #execute} and {@link #record} each
 * grow it by one, {@link #undo()} and {@link #redo()} move it by exactly one the other
 * way, and a fresh edit after undoing clears the redo stack rather than branching. The
 * one case this cannot see is undoing back past a save point and then making a
 * different edit that happens to leave the stack the same depth — the same limitation
 * most editors accept for this technique.
 */
public class UndoManager {

    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    /** The undo stack's depth as of the last {@link #markSaved()} (or {@link #clear()}). */
    private int savedDepth;

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

    /** Whether there is anything to {@link #undo()} — for greying out a menu item. */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** Whether there is anything to {@link #redo()}. */
    public boolean canRedo() {
        return !redoStack.isEmpty();
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

    /**
     * Wipes all history - e.g. loading a different graph makes the previous one's history
     * meaningless. Also marks the document clean: what was just loaded matches what's on disk.
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        savedDepth = 0;
    }

    /** Marks the document clean at the current point in its history - call after a successful save. */
    public void markSaved() {
        savedDepth = undoStack.size();
    }

    /** Whether the history has moved since {@link #markSaved()} (or {@link #clear()}) was last called. */
    public boolean isDirty() {
        return undoStack.size() != savedDepth;
    }
}
