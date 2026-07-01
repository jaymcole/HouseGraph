package io.github.jaymcole.housegraph.ui;

import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;

/**
 * Visual curve connecting an OUT {@link FlowPortView} to an IN {@link FlowPortView},
 * with a small button at its midpoint to delete the connection. Styled distinctly
 * (green) from data {@link EdgeView}s to keep control flow visually separate.
 */
public class FlowEdgeView extends Group implements ConnectionView {

    private final FlowPortView source;
    private final FlowPortView target;
    private final Group content;
    private final Runnable onDelete;
    private final CubicCurve curve = new CubicCurve();
    private final Button deleteButton = new Button("x");

    private boolean selected = false;

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
}
