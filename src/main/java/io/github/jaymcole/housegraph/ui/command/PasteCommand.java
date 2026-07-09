package io.github.jaymcole.housegraph.ui.command;

import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.view.NodeView;

import io.github.jaymcole.housegraph.graph.NodeRegistry;

import java.util.List;

/**
 * Reversible paste of a clipboard snapshot. Each execute() (including a redo, i.e. a
 * second call after an undo) duplicates fresh {@link io.github.jaymcole.housegraph.graph.BaseNode}
 * instances from the clipboard's originals - simpler than trying to preserve identity
 * of the specific pasted nodes across an undo/redo cycle, and nothing else depends on
 * that identity surviving.
 */
public class PasteCommand implements Command {

    private final GraphCanvas canvas;
    private final GraphCanvas.GraphSnapshot snapshot;
    private final double offsetX;
    private final double offsetY;
    private List<NodeView> pastedNodes;

    public PasteCommand(GraphCanvas canvas, GraphCanvas.GraphSnapshot snapshot, double offsetX, double offsetY) {
        this.canvas = canvas;
        this.snapshot = snapshot;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    @Override
    public void execute() {
        pastedNodes = canvas.place(snapshot, entry -> NodeRegistry.duplicate(entry.node()), offsetX, offsetY);
        canvas.selectOnly(pastedNodes);
    }

    @Override
    public void undo() {
        canvas.deleteNodes(pastedNodes);
    }
}
