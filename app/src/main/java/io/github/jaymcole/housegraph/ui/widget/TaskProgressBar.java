package io.github.jaymcole.housegraph.ui.widget;

import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * A progress bar plus percentage label that tracks whichever {@link Task} is currently running,
 * and hides itself when none is. Nothing here is specific to what the task does — it binds to
 * {@link Task#progressProperty()}, which is determinate when the task calls
 * {@code updateProgress(workDone, max)} and indeterminate otherwise — so one instance is meant to
 * be reused across every long-running operation a window kicks off, rather than each feature
 * building its own.
 */
public final class TaskProgressBar extends HBox {

    private final ProgressBar bar = new ProgressBar();
    private final Label percent = new Label();

    public TaskProgressBar() {
        super(8);
        setAlignment(Pos.CENTER_LEFT);
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        percent.setMinWidth(40);
        getChildren().setAll(bar, percent);
        setVisible(false);
        setManaged(false);
    }

    /**
     * Binds this bar to {@code task} for as long as it runs; unbinds and hides itself once it
     * finishes, succeeds, fails, or is cancelled, so the bar never shows a stale value from a
     * previous operation.
     */
    public void track(Task<?> task) {
        bar.progressProperty().bind(task.progressProperty());
        task.progressProperty().addListener((obs, was, now) -> updatePercentLabel(now.doubleValue()));
        updatePercentLabel(task.getProgress());
        setVisible(true);
        setManaged(true);
        task.runningProperty().addListener((obs, wasRunning, running) -> {
            if (!running) {
                bar.progressProperty().unbind();
                setVisible(false);
                setManaged(false);
            }
        });
    }

    private void updatePercentLabel(double progress) {
        percent.setText(progress < 0 ? "" : Math.round(progress * 100) + "%");
    }
}
