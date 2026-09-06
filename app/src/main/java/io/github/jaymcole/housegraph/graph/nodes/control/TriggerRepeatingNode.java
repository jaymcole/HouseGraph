package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import io.github.jaymcole.housegraph.sdk.NodeTimer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * Entry-point node like {@link TriggerNode}, but fires repeatedly on a timer instead
 * of once per click: Start begins calling execute() every Interval seconds, Stop
 * cancels it. Purely a flow source - no data outputs of its own.
 * <p>
 * The Start/Stop buttons have flow-in counterparts of the same name, so another node's cascade
 * can arm or disarm the timer (see {@link ProcessContext#wasTriggeredVia}). Arriving through
 * either port never fires this node's own flow-out itself — only the periodic tick does — so
 * {@link #process(ProcessContext)} calls {@link #activateNone()} for those firings; the actual
 * button-equivalent work happens in {@link #onExecuted()}, which the engine dispatches through
 * its callback executor rather than on the execution thread {@code process()} runs on.
 * <p>
 * If the timer was running when the graph was saved, it resumes automatically on load: the
 * running flag rides along in {@link #saveState()} and {@link #autoStartIfWasRunning()} presses
 * Start for the user (see {@link AutoStartable}).
 *
 * <h2>It runs with or without a view</h2>
 * The clock is a {@link NodeTimer}, not a {@code javafx.animation.Timeline}, and {@link #running}
 * is a field of this node rather than "the timer object exists". So starting, stopping, saving
 * and resuming all work when nothing has drawn this node — a headless run, or a graph used from
 * inside another graph. The countdown label and the two buttons are presentation only, and every
 * write to them goes through {@link #present(Runnable)}, which discards the update when there is
 * no view and marshals it onto the FX thread when there is. That matters here because the tick
 * arrives on a timer thread, not the FX thread.
 */
@Display.Name("Repeating Trigger")
@Display.Description("Starts a run over and over on a timer.")
@Kind(NodeKind.CONTROL)
@Keywords({"timer", "interval", "schedule", "poll", "periodic", "cron", "repeat", "every"})
public class TriggerRepeatingNode extends BaseNode implements NodeContentProvider, AutoStartable {

    /**
     * The countdown ticks once a second rather than once per interval, so the label can show the
     * time remaining and so a long interval is still cancelled promptly. It ticks whether or not
     * anyone is watching: the tick is also what counts down to the firing.
     */
    private static final long TICK_MILLIS = 1_000;

    private final NodeVariable<Integer> intervalSeconds = new NodeVariable<>("Interval (s)", Integer.class, true).required();
    private final FlowPort startFlowInput = new FlowPort("Start", FlowPort.Direction.IN);
    private final FlowPort stopFlowInput = new FlowPort("Stop", FlowPort.Direction.IN);

    private final NodeTimer clock = new NodeTimer("RepeatingTrigger");

    /** This node's own running state — what {@link #saveState()} reports, and not a UI object. */
    private volatile boolean running;
    private volatile int intervalValue;
    private volatile int remainingSeconds;

    private Button startButton;
    private Button stopButton;
    private Label statusLabel;
    /** True when the timer was running at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasRunning;
    /** Set in {@link #process(ProcessContext)}, consumed in {@link #onExecuted()}. */
    private volatile FlowPort pendingFlowAction;

    @Override
    public void process(ProcessContext ctx) {
        if (ctx.wasTriggeredVia(startFlowInput)) {
            pendingFlowAction = startFlowInput;
            activateNone();
        } else if (ctx.wasTriggeredVia(stopFlowInput)) {
            pendingFlowAction = stopFlowInput;
            activateNone();
        } else {
            pendingFlowAction = null;
        }
    }

    @Override
    protected void onExecuted() {
        // The node's own incoming data edges (intervalSeconds's, if wired) were already pulled as
        // part of this same firing's resolution, and committed back onto the variable before this
        // callback ran - so, unlike the Start button (which fires outside any resolution), no
        // beginProcessing() is needed here before reading intervalSeconds.
        FlowPort action = pendingFlowAction;
        pendingFlowAction = null;
        if (action == startFlowInput) {
            armTimer();
        } else if (action == stopFlowInput) {
            stop();
        }
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        if (running) {
            state.put("running", "true");
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        wasRunning = Boolean.parseBoolean(state.get("running"));
    }

    @Override
    public void autoStartIfWasRunning() {
        if (wasRunning) {
            start();
        }
    }

    /** Test seam: whether the loaded graph had this timer running, i.e. auto-start is pending. */
    boolean wasRunning() {
        return wasRunning;
    }

    /** Test seam: whether the countdown is currently armed. */
    boolean isRunning() {
        return running;
    }

    @Override
    public void configureInputs() {
        addInput(intervalSeconds);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(startFlowInput);
        addFlowInput(stopFlowInput);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }

    /**
     * Structurally this now has a flow-in (Start/Stop), so the default would say it can only run
     * when reached along an edge. It is still self-triggering: the buttons call {@link #start()}/
     * {@link #stop()} directly, and the countdown calls {@link #execute()} on itself every interval.
     */
    @Override
    public boolean isExecutionEntryPoint() {
        return true;
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
        // A view built while the timer is already running (a rebuild after a settings change,
        // or a canvas opened on an already-live node) must not come up showing "Stopped".
        if (running) {
            showRunning();
        }
        return box;
    }

    private void start() {
        // Pull the interval through its data edge (if any) before reading it - a
        // connected value only gets copied into intervalSeconds when the graph
        // actually resolves this node, which nothing has done yet at this point.
        beginProcessing();
        armTimer();
    }

    /**
     * The actual timer setup, assuming {@link #intervalSeconds} already holds this run's resolved
     * value: {@link #start()} (the button) resolves it first via {@link #beginProcessing()}; the
     * flow-triggered path in {@link #onExecuted()} doesn't need to, since the engine already
     * resolved this node's own inputs before calling {@link #process}.
     */
    private void armTimer() {
        if (running) {
            return;
        }
        Integer seconds = intervalSeconds.getValue();
        if (seconds == null || seconds <= 0) {
            present(() -> statusLabel.setText("Enter a positive interval first"));
            return;
        }

        intervalValue = seconds;
        remainingSeconds = seconds;
        running = true;
        clock.start(TICK_MILLIS, this::countdownTick);
        showRunning();
    }

    private void stop() {
        running = false;
        clock.stop();
        present(() -> {
            startButton.setDisable(false);
            stopButton.setDisable(true);
            statusLabel.setText("Stopped");
        });
    }

    /**
     * One second of the countdown, on a {@link NodeTimer} thread. {@link #execute()} hands the run
     * to the engine's executor and returns, so the tick is never waiting on the graph it fires.
     */
    private void countdownTick() {
        remainingSeconds--;
        if (remainingSeconds <= 0) {
            remainingSeconds = intervalValue;
            execute();
        }
        updateCountdownLabel();
    }

    private void showRunning() {
        present(() -> {
            startButton.setDisable(true);
            stopButton.setDisable(false);
        });
        updateCountdownLabel();
    }

    private void updateCountdownLabel() {
        present(() -> statusLabel.setText("Next trigger in " + remainingSeconds + "s"));
    }

    /**
     * Stops the timer when the node is removed from the graph (deleted, replaced by a
     * load, or app shutdown) so it can't keep firing as a zombie. Cancelling the clock is
     * immediate and does not wait for a tick already in flight, so this stays inside the
     * fast, unbounded-but-quick half of teardown.
     */
    @Override
    protected void onRemoved() {
        running = false;
        clock.stop();
    }
}
