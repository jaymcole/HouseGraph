package io.github.jaymcole.housegraph.ui.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UndoManager#isDirty()} is what {@code GraphWindow} asks before discarding a graph — see
 * {@code docs/engine/windows.md#unsaved-changes}. These tests cover it directly, since it's plain
 * history bookkeeping with nothing to do with the canvas.
 */
class UndoManagerTest {

    /** Does nothing beyond existing — {@code isDirty()} only cares that a slot was pushed. */
    private static final class NoOpCommand implements Command {
        @Override
        public void execute() {
        }

        @Override
        public void undo() {
        }
    }

    @Test
    void freshManagerIsClean() {
        assertFalse(new UndoManager().isDirty());
    }

    @Test
    void executingACommandMakesItDirty() {
        UndoManager undoManager = new UndoManager();
        undoManager.execute(new NoOpCommand());
        assertTrue(undoManager.isDirty());
    }

    @Test
    void recordingACommandMakesItDirty() {
        UndoManager undoManager = new UndoManager();
        undoManager.record(new NoOpCommand());
        assertTrue(undoManager.isDirty());
    }

    @Test
    void markSavedCleansTheCurrentPosition() {
        UndoManager undoManager = new UndoManager();
        undoManager.execute(new NoOpCommand());
        undoManager.markSaved();
        assertFalse(undoManager.isDirty());
    }

    @Test
    void undoingPastTheSavedPositionIsDirtyAgain() {
        UndoManager undoManager = new UndoManager();
        undoManager.execute(new NoOpCommand());
        undoManager.markSaved();
        undoManager.undo();
        assertTrue(undoManager.isDirty());
    }

    @Test
    void redoingBackToTheSavedPositionIsCleanAgain() {
        UndoManager undoManager = new UndoManager();
        undoManager.execute(new NoOpCommand());
        undoManager.markSaved();
        undoManager.undo();
        undoManager.redo();
        assertFalse(undoManager.isDirty());
    }

    @Test
    void furtherEditsAfterSavingAreDirty() {
        UndoManager undoManager = new UndoManager();
        undoManager.execute(new NoOpCommand());
        undoManager.markSaved();
        undoManager.execute(new NoOpCommand());
        assertTrue(undoManager.isDirty());
    }

    @Test
    void clearResetsBothHistoryAndTheSavedPosition() {
        UndoManager undoManager = new UndoManager();
        undoManager.execute(new NoOpCommand());
        undoManager.clear();
        assertFalse(undoManager.isDirty());
    }
}
