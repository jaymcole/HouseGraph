package io.github.jaymcole.housegraph.graph.nodes.resource;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Map;

/**
 * A stand-in long-lived resource, for proving the resource pattern before real
 * integrations (Discord, MQTT, …). While running it publishes an incrementing
 * "tick N" event once a second under its chosen name; anything listening on that name
 * (see {@link EchoListenerNode}) is driven by it.
 * <p>
 * It demonstrates the three things a real resource needs: a lifecycle independent of
 * graph flow (Start/Stop, not a flow trigger), a name others reference, and events that
 * drive execution. Its liveness is user-driven, so it has no ports and does nothing on
 * {@code process()} — it just sits on the canvas being a resource.
 */
@Display.Name("Echo Resource")
public class EchoResourceNode extends BaseNode implements NodeContentProvider {

    private String resourceName = "echo";
    private Timeline timeline;
    private int counter;
    private boolean running;

    private TextField nameField;
    private Button startButton;
    private Button stopButton;
    private Label statusLabel;

    @Override
    public void process() {
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public Map<String, String> saveState() {
        return Map.of("name", resourceName);
    }

    @Override
    public void loadState(Map<String, String> state) {
        String name = state.get("name");
        if (name != null && !name.isBlank()) {
            resourceName = name;
        }
    }

    @Override
    protected void onRemoved() {
        stop();
    }

    @Override
    public Node createNodeContent() {
        nameField = new TextField(resourceName);
        nameField.setPromptText("Resource name");
        nameField.textProperty().addListener((obs, old, value) -> resourceName = value);

        startButton = new Button("Start");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> start());

        stopButton = new Button("Stop");
        stopButton.setMaxWidth(Double.MAX_VALUE);
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stop());

        statusLabel = new Label("Stopped");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        HBox buttons = new HBox(6, startButton, stopButton);
        return new VBox(4, nameField, buttons, statusLabel);
    }

    private void start() {
        if (running) {
            return;
        }
        running = true;
        counter = 0;
        // Name is locked while running so the registered name can't drift from what's published.
        nameField.setDisable(true);
        startButton.setDisable(true);
        stopButton.setDisable(false);
        ResourceRegistry.shared().register(resourceName, this);

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> emit()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
        statusLabel.setText("Running as \"" + resourceName + "\"");
    }

    private void emit() {
        counter++;
        ResourceRegistry.shared().publish(resourceName, "tick " + counter);
        statusLabel.setText("Published tick " + counter);
    }

    /** Idempotent, UI-optional teardown — used by the Stop button and by {@link #onRemoved()}. */
    private void stop() {
        running = false;
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        ResourceRegistry.shared().unregister(resourceName);
        if (nameField != null) {
            nameField.setDisable(false);
            startButton.setDisable(false);
            stopButton.setDisable(true);
            statusLabel.setText("Stopped");
        }
    }
}
