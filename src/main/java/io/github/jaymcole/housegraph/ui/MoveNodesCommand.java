package io.github.jaymcole.housegraph.ui;

import java.util.List;

/**
 * Reversible move of one or more nodes from a captured "before" position to a
 * captured "after" position. The move itself already happened live during the drag
 * (for real-time visual feedback); this just gets {@link UndoManager#record}ed once
 * the gesture ends, rather than {@link UndoManager#execute}d.
 */
class MoveNodesCommand implements Command {

    private final List<NodeView> nodes;
    private final double[] fromX;
    private final double[] fromY;
    private final double[] toX;
    private final double[] toY;

    MoveNodesCommand(List<NodeView> nodes, double[] fromX, double[] fromY, double[] toX, double[] toY) {
        this.nodes = nodes;
        this.fromX = fromX;
        this.fromY = fromY;
        this.toX = toX;
        this.toY = toY;
    }

    @Override
    public void execute() {
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).setLayoutX(toX[i]);
            nodes.get(i).setLayoutY(toY[i]);
        }
    }

    @Override
    public void undo() {
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).setLayoutX(fromX[i]);
            nodes.get(i).setLayoutY(fromY[i]);
        }
    }
}
