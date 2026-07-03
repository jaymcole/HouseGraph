package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Entry-point node like {@link TriggerNode}, but fires repeatedly on a timer instead
 * of once per click: Start begins calling execute() every Interval seconds, Stop
 * cancels it. Purely a flow source - no data outputs of its own.
 */
@Display.Name("Repeating Trigger")
public class TriggerRepeatingNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<Integer> intervalSeconds = new NodeVariable<>("Interval (s)", Integer.class, true);

    private Timeline timeline;
    private Button startButton;
    private Button stopButton;
    private Label statusLabel;
    private int intervalValue;
    private int remainingSeconds;

    @Override
    public void process() {
    }

    @Override
    public void configureInputs() {
        addInput(intervalSeconds);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }

    @Override
    public Node createNodeContent() {
        startButton = new Button("Start");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(event -> start());

        stopButton = new Button("Stop");
        stopButton.setMaxWidth(Double.MAX_VALUE);
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> stop());

        statusLabel = new Label("Stopped");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        HBox buttons = new HBox(6, startButton, stopButton);
        VBox box = new VBox(4, buttons, statusLabel);
        return box;
    }

    private void start() {
        if (timeline != null) {
            return;
        }
        // Pull the interval through its data edge (if any) before reading it - a
        // connected value only gets copied into intervalSeconds when the graph
        // actually resolves this node, which nothing has done yet at this point.
        beginProcessing();
        Integer seconds = intervalSeconds.getValue();
        if (seconds == null || seconds <= 0) {
            statusLabel.setText("Enter a positive interval first");
            return;
        }

        intervalValue = seconds;
        remainingSeconds = seconds;
        updateCountdownLabel();

        // One-second ticks driving a countdown, rather than a single seconds-long
        // KeyFrame, so the remaining time can be shown and updated live.
        timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> countdownTick()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();

        startButton.setDisable(true);
        stopButton.setDisable(false);
    }

    private void stop() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        startButton.setDisable(false);
        stopButton.setDisable(true);
        statusLabel.setText("Stopped");
    }

    private void countdownTick() {
        remainingSeconds--;
        if (remainingSeconds <= 0) {
            remainingSeconds = intervalValue;
            tick();
        }
        if (timeline != null) {
            // tick() may have called stop() (e.g. the node was removed mid-run);
            // don't stomp its "Stopped" label back to a countdown in that case.
            updateCountdownLabel();
        }
    }

    private void updateCountdownLabel() {
        statusLabel.setText("Next trigger in " + remainingSeconds + "s");
    }

    private void tick() {
        try {
            execute();
        } catch (IllegalStateException e) {
            // The node was removed from the canvas (no longer attached to a
            // NodeGraph) while the timer was still running; stop firing instead of
            // leaving a zombie timer that errors every interval forever.
            stop();
        }
    }
}
