package io.github.jaymcole.housegraph.ui.command;

import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.view.GroupView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reversible removal of group frames.
 *
 * <p>Only the frames. A frame commands the nodes inside it for a move or a copy, but not for a
 * delete: the frame is a large click target laid over real work, and cascading a delete through it
 * would make one mis-aimed keystroke destroy an automation. Removing the label around a group of
 * nodes leaves the nodes exactly where they were.
 *
 * <p>The same {@link GroupView} instances go back on undo, so a redo removes the frames the user is
 * actually looking at rather than lookalikes — the discipline {@code RemoveNodesCommand} keeps for
 * nodes.
 */
public class RemoveGroupsCommand implements Command {

    private final GraphCanvas canvas;
    private final List<GroupView> groups;

    public RemoveGroupsCommand(GraphCanvas canvas, Collection<GroupView> groupsToRemove) {
        this.canvas = canvas;
        this.groups = new ArrayList<>(groupsToRemove);
    }

    @Override
    public void execute() {
        for (GroupView group : groups) {
            canvas.removeGroup(group);
        }
    }

    @Override
    public void undo() {
        for (GroupView group : groups) {
            canvas.addGroup(group);
        }
    }
}
