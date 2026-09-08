package io.github.jaymcole.housegraph.ui.command;

import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.PlacedGraph;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import io.github.jaymcole.housegraph.ui.view.GroupView;

import io.github.jaymcole.housegraph.graph.NodeRegistry;

/**
 * Reversible paste of a clipboard snapshot. Each execute() (including a redo, i.e. a
 * second call after an undo) duplicates fresh {@link io.github.jaymcole.housegraph.graph.BaseNode}
 * instances from the clipboard's originals - simpler than trying to preserve identity
 * of the specific pasted nodes across an undo/redo cycle, and nothing else depends on
 * that identity surviving. The same goes for the group frames the snapshot carries.
 */
public class PasteCommand implements Command {

    private final GraphCanvas canvas;
    private final GraphSnapshot snapshot;
    private final double offsetX;
    private final double offsetY;
    private PlacedGraph pasted;

    public PasteCommand(GraphCanvas canvas, GraphSnapshot snapshot, double offsetX, double offsetY) {
        this.canvas = canvas;
        this.snapshot = snapshot;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    @Override
    public void execute() {
        pasted = canvas.place(snapshot, entry -> NodeRegistry.duplicate(entry.node()), offsetX, offsetY);
        canvas.selectOnly(pasted);
    }

    @Override
    public void undo() {
        canvas.deleteNodes(pasted.nodes());
        // The frames go too. Undoing a paste has to leave nothing behind, and a pasted frame is not
        // a frame the user drew - unlike a delete, where a frame deliberately survives its contents.
        for (GroupView group : pasted.groups()) {
            canvas.removeGroup(group);
        }
    }
}
