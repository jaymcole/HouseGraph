package io.github.jaymcole.housegraph.ui.command;

import io.github.jaymcole.housegraph.ui.view.NodeView;

/**
 * Reversible change to a node's manual size floor, from a captured "before" to a captured "after"
 * (see {@link NodeView#setManualSize}). Both the grip drag and the context menu's <b>Reset size</b>
 * apply the change themselves and then {@link UndoManager#record} it, rather than
 * {@link UndoManager#execute}ing it — the drag has to be live to be worth doing at all, and the
 * reset arrives through the same path so the two are one kind of undo step.
 */
public class ResizeNodeCommand implements Command {

    private final NodeView node;
    private final double fromWidth;
    private final double fromHeight;
    private final double toWidth;
    private final double toHeight;

    public ResizeNodeCommand(NodeView node, double fromWidth, double fromHeight, double toWidth, double toHeight) {
        this.node = node;
        this.fromWidth = fromWidth;
        this.fromHeight = fromHeight;
        this.toWidth = toWidth;
        this.toHeight = toHeight;
    }

    @Override
    public void execute() {
        node.setManualSize(toWidth, toHeight);
    }

    @Override
    public void undo() {
        node.setManualSize(fromWidth, fromHeight);
    }
}
