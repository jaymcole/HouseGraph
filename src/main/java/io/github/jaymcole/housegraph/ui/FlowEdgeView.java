package io.github.jaymcole.housegraph.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.util.Duration;

/**
 * Visual curve connecting an OUT {@link FlowPortView} to an IN {@link FlowPortView},
 * with a small button at its midpoint to delete the connection. Styled distinctly
 * (green) from data {@link EdgeView}s to keep control flow visually separate.
 */
public class FlowEdgeView extends Group implements ConnectionView {

    private static final Color PULSE_STROKE = Color.web("#61dafb");
    private static final Duration PULSE_DURATION = Duration.millis(400);

    private final FlowPortView source;
    private final FlowPortView target;
    private final Group content;
    private final Runnable onDelete;
    private final CubicCurve curve = new CubicCurve();
    private final Button deleteButton = new Button("x");

    private boolean selected = false;
    private PauseTransition pulseRevert;

    public FlowEdgeView(FlowPortView source, FlowPortView target, Group content, Runnable onDelete) {
        this.source = source;
        this.target = target;
        this.content = content;
        this.onDelete = onDelete;

        curve.setFill(null);
        curve.setMouseTransparent(true);
        applyCurveStyle();

        deleteButton.setStyle(
                "-fx-background-color: #e06c75; -fx-text-fill: white; -fx-font-size: 9px;"
                        + " -fx-padding: 1 5 1 5; -fx-background-radius: 10;");
        deleteButton.setOnAction(event -> delete());

        getChildren().addAll(curve, deleteButton);

        source.getOwner().layoutXProperty().addListener((obs, oldV, newV) -> updatePath());
        source.getOwner().layoutYProperty().addListener((obs, oldV, newV) -> updatePath());
        target.getOwner().layoutXProperty().addListener((obs, oldV, newV) -> updatePath());
        target.getOwner().layoutYProperty().addListener((obs, oldV, newV) -> updatePath());

        updatePath();
    }

    public void updatePath() {
        Point2D start = source.getCenterInContent(content);
        Point2D end = target.getCenterInContent(content);

        curve.setStartX(start.getX());
        curve.setStartY(start.getY());
        curve.setEndX(end.getX());
        curve.setEndY(end.getY());

        double controlOffset = Math.max(50, Math.abs(end.getX() - start.getX()) / 2);
        curve.setControlX1(start.getX() + controlOffset);
        curve.setControlY1(start.getY());
        curve.setControlX2(end.getX() - controlOffset);
        curve.setControlY2(end.getY());

        double midX = (start.getX() + end.getX()) / 2;
        double midY = (start.getY() + end.getY()) / 2;
        deleteButton.relocate(midX - 8, midY - 10);
    }

    @Override
    public void delete() {
        content.getChildren().remove(this);
        onDelete.run();
    }

    @Override
    public boolean touchesNode(NodeView node) {
        return source.getOwner() == node || target.getOwner() == node;
    }

    public boolean hasTarget(FlowPortView port) {
        return target == port;
    }

    public FlowPortView getSourcePort() {
        return source;
    }

    public FlowPortView getTargetPort() {
        return target;
    }

    @Override
    public void setSelected(boolean selected) {
        this.selected = selected;
        applyCurveStyle();
    }

    @Override
    public boolean isSelected() {
        return selected;
    }

    private void applyCurveStyle() {
        curve.setStroke(selected ? Color.web("#e5c07b") : Color.web("#98c379"));
        curve.setStrokeWidth(selected ? 3 : 2);
    }

    /** Briefly flashes the curve to show execution just cascaded across it, then reverts to its normal style. */
    public void pulse() {
        curve.setStroke(PULSE_STROKE);
        curve.setStrokeWidth(4);
        if (pulseRevert != null) {
            pulseRevert.stop();
        }
        pulseRevert = new PauseTransition(PULSE_DURATION);
        pulseRevert.setOnFinished(event -> applyCurveStyle());
        pulseRevert.play();
    }
}
