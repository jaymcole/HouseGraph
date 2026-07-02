package io.github.jaymcole.housegraph.ui;

/**
 * Reversible creation of a data edge by dragging between two ports. An input can only
 * ever be fed by one edge, so createEdge() silently replaces whatever was already
 * wired into the target; undo needs to restore that replaced edge too, not just
 * remove the new one, or it'd leave the target disconnected instead of back where it
 * started. What was replaced (if anything) is re-checked on every execute(), so this
 * stays correct across repeated undo/redo cycles.
 */
class CreateEdgeCommand implements Command {

    private final GraphCanvas canvas;
    private final PortView a;
    private final PortView b;
    private EdgeView createdView;
    private PortView replacedSource;
    private PortView replacedTarget;

    CreateEdgeCommand(GraphCanvas canvas, PortView a, PortView b) {
        this.canvas = canvas;
        this.a = a;
        this.b = b;
    }

    @Override
    public void execute() {
        PortView inputPort = a.getDirection() == PortView.Direction.OUTPUT ? b : a;
        EdgeView replaced = canvas.findEdgeViewTargeting(inputPort);
        replacedSource = replaced == null ? null : replaced.getSourcePort();
        replacedTarget = replaced == null ? null : replaced.getTargetPort();

        createdView = canvas.createEdge(a, b);
    }

    @Override
    public void undo() {
        createdView.delete();
        if (replacedSource != null) {
            canvas.createEdge(replacedSource, replacedTarget);
        }
    }
}
