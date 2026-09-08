package io.github.jaymcole.housegraph.ui.command;

import io.github.jaymcole.housegraph.saveformat.NodeGroup;
import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.view.GroupView;

/**
 * Reversible change to one group frame's state: its position, its size, its title, its colour, or
 * any combination.
 *
 * <p>One command covers all four because {@link NodeGroup} is the frame's <em>whole</em> state as a
 * single immutable value, so "what it was" and "what it is now" are two of them and nothing else
 * needs capturing. A move and a resize record identically; only which fields differ changes.
 *
 * <p>Like a node drag, the change is normally already applied live by the time this is recorded —
 * {@link UndoManager#record} rather than {@code execute} — but {@link #execute()} re-applies it so a
 * redo works.
 */
public class SetGroupCommand implements Command {

    private final GraphCanvas canvas;
    private final GroupView groupView;
    private final NodeGroup before;
    private final NodeGroup after;

    public SetGroupCommand(GraphCanvas canvas, GroupView groupView, NodeGroup before, NodeGroup after) {
        this.canvas = canvas;
        this.groupView = groupView;
        this.before = before;
        this.after = after;
    }

    @Override
    public void execute() {
        apply(after);
    }

    @Override
    public void undo() {
        apply(before);
    }

    /** Restacks as well as applying, because a size change moves the frame in the paint order. */
    private void apply(NodeGroup state) {
        groupView.setGroup(state);
        canvas.restackGroups();
    }
}
