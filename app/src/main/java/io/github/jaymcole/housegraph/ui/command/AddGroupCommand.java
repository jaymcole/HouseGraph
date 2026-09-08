package io.github.jaymcole.housegraph.ui.command;

import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.view.GroupView;

/** Reversible add of a single group frame — from the canvas menu, with or without a selection to wrap. */
public class AddGroupCommand implements Command {

    private final GraphCanvas canvas;
    private final GroupView groupView;

    public AddGroupCommand(GraphCanvas canvas, GroupView groupView) {
        this.canvas = canvas;
        this.groupView = groupView;
    }

    @Override
    public void execute() {
        canvas.addGroup(groupView);
    }

    @Override
    public void undo() {
        // The frame may have been selected since it was added, so make sure it doesn't linger as a
        // stale reference in the selection once it is off the canvas.
        canvas.removeGroup(groupView);
    }
}
