package io.github.jaymcole.housegraph.ui;

import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;

/**
 * Visual curve connecting an output {@link PortView} to an input {@link PortView},
 * with a small button at its midpoint to delete the connection.
 */
public class EdgeView extends Group {

    private final PortView source;
    private final PortView target;
    private final Group content;
    private final CubicCurve curve = new CubicCurve();
    private final Button deleteButton = new Button("x");

    public EdgeView(PortView source, PortView target, Group content, Runnable onDelete) {
        this.source = source;
        this.target = target;
        this.content = content;

        curve.setFill(null);
        curve.setStroke(Color.web("#61afef"));
        curve.setStrokeWidth(2);
        curve.setMouseTransparent(true);

        deleteButton.setStyle(
                "-fx-background-color: #e06c75; -fx-text-fill: white; -fx-font-size: 9px;"
                        + " -fx-padding: 1 5 1 5; -fx-background-radius: 10;");
        deleteButton.setOnAction(event -> {
            content.getChildren().remove(EdgeView.this);
            onDelete.run();
        });

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
}
