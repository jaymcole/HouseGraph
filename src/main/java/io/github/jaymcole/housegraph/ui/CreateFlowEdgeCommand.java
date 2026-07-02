package io.github.jaymcole.housegraph.ui;

/** Reversible creation of a flow edge by dragging between two flow ports - see {@link CreateEdgeCommand}, same idea for flow ports. */
class CreateFlowEdgeCommand implements Command {

    private final GraphCanvas canvas;
    private final FlowPortView a;
    private final FlowPortView b;
    private FlowEdgeView createdView;
    private FlowPortView replacedSource;
    private FlowPortView replacedTarget;

    CreateFlowEdgeCommand(GraphCanvas canvas, FlowPortView a, FlowPortView b) {
        this.canvas = canvas;
        this.a = a;
        this.b = b;
    }

    @Override
    public void execute() {
        FlowPortView inPort = a.getDirection() == FlowPortView.Direction.OUT ? b : a;
        FlowEdgeView replaced = canvas.findFlowEdgeViewTargeting(inPort);
        replacedSource = replaced == null ? null : replaced.getSourcePort();
        replacedTarget = replaced == null ? null : replaced.getTargetPort();

        createdView = canvas.createFlowEdge(a, b);
    }

    @Override
    public void undo() {
        createdView.delete();
        if (replacedSource != null) {
            canvas.createFlowEdge(replacedSource, replacedTarget);
        }
    }
}
