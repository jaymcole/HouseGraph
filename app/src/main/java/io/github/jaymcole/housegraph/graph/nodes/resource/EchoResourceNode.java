package io.github.jaymcole.housegraph.graph.nodes.resource;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import io.github.jaymcole.housegraph.sdk.NodeTimer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.HashMap;
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
 * <p>
 * If it was running when the graph was saved, it resumes automatically on load: the running
 * flag rides along in {@link #saveState()} and {@link #autoStartIfWasRunning()} presses Start
 * for the user (see {@link AutoStartable}).
 *
 * <h2>It runs with or without a view</h2>
 * The clock is a {@link NodeTimer} rather than a {@code javafx.animation.Timeline}, and the
 * registration, the counter and the running flag are all fields of this node. So starting,
 * publishing, stopping and resuming work when nothing has drawn it — a headless run, or a graph
 * used from inside another graph. The name field, the two buttons and the status label are
 * presentation only, and every write to them goes through {@link #present(Runnable)}: discarded
 * when there is no view, marshalled onto the FX thread when there is, which is what the
 * once-a-second publish needs since it happens on a timer thread.
 */
@Display.Name("Echo Resource")
@Display.Description("Hosts a named echo resource that other nodes publish to and listen on.")
@Kind(NodeKind.RESOURCE)
@Keywords({"echo", "resource", "connection", "server", "host", "publish", "broadcast", "named"})
public class EchoResourceNode extends BaseNode implements NodeContentProvider, AutoStartable {

    private static final long EMIT_MILLIS = 1_000;

    private final NodeTimer clock = new NodeTimer("EchoResource");

    private volatile String resourceName = "echo";
    private volatile int counter;
    private volatile boolean running;
    /** True when this node was running at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasRunning;

    private TextField nameField;
    private Button startButton;
    private Button stopButton;
    private Label statusLabel;

    @Override
    public void process(ProcessContext ctx) {
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("name", resourceName);
        if (running) {
            state.put("running", "true");
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        String name = state.get("name");
        if (name != null && !name.isBlank()) {
            resourceName = name;
        }
        wasRunning = Boolean.parseBoolean(state.get("running"));
    }

    @Override
    public void autoStartIfWasRunning() {
        if (wasRunning) {
            start();
        }
    }

    /** Test seam: whether the loaded graph had this resource running, i.e. auto-start is pending. */
    boolean wasRunning() {
        return wasRunning;
    }

    /** Test seam: whether this resource is currently registered and publishing. */
    boolean isRunning() {
        return running;
    }

    /** Test seam: the name this resource publishes under, which the UI's name field edits. */
    String resourceName() {
        return resourceName;
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
        VBox box = new VBox(4, nameField, buttons, statusLabel);
        // A view built while this resource is already live (a rebuild, or a canvas opened on an
        // already-running node) must come up locked and labelled, not offering Start again.
        if (running) {
            showRunning();
        }
        return box;
    }

    private void start() {
        if (running) {
            return;
        }
        running = true;
        counter = 0;
        ResourceRegistry.shared().register(resourceName, this);
        clock.start(EMIT_MILLIS, this::emit);
        showRunning();
    }

    /** One publish, on a {@link NodeTimer} thread — hence the status label going via {@link #present}. */
    private void emit() {
        counter++;
        ResourceRegistry.shared().publish(resourceName, "tick " + counter);
        present(() -> statusLabel.setText("Published tick " + counter));
    }

    /**
     * Idempotent teardown — used by the Stop button, by the flow-free {@link #onRemoved()}, and by
     * a headless caller that never had controls to reset.
     */
    private void stop() {
        running = false;
        clock.stop();
        ResourceRegistry.shared().unregister(resourceName);
        present(() -> {
            nameField.setDisable(false);
            startButton.setDisable(false);
            stopButton.setDisable(true);
            statusLabel.setText("Stopped");
        });
    }

    private void showRunning() {
        present(() -> {
            // Name is locked while running so the registered name can't drift from what's published.
            nameField.setDisable(true);
            startButton.setDisable(true);
            stopButton.setDisable(false);
            statusLabel.setText("Running as \"" + resourceName + "\"");
        });
    }
}
