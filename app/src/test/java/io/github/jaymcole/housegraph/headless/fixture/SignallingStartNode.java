package io.github.jaymcole.housegraph.headless.fixture;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.sdk.AutoStartable;

import java.util.HashMap;
import java.util.Map;

/**
 * Resumes like a real long-lived node — persists a running flag, reads it back, and executes once
 * from {@code autoStartIfWasRunning()} — but fires immediately rather than on a clock.
 *
 * <p>Immediately is the point: the repeating trigger's shortest interval is one real second, and
 * the two tests built on it are already the slowest in the suite. A node that fires the moment it
 * resumes proves the same thing about the runner — the graph is loaded, wired and live — in the
 * time it takes to dispatch one run.
 */
@Display.Name("Signalling Start")
public class SignallingStartNode extends BaseNode implements AutoStartable {

    private boolean wasRunning;
    private volatile boolean running;

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }

    @Override
    public boolean isExecutionEntryPoint() {
        return true;
    }

    @Override
    public void process(ProcessContext ctx) {
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
        wasRunning = "true".equals(state.get("running"));
    }

    @Override
    public void autoStartIfWasRunning() {
        if (!wasRunning) {
            return;
        }
        running = true;
        execute();
    }

    /** What this node's Start button would do — enough that saving the graph records it as running. */
    public void start() {
        running = true;
    }

    /** Whether this node is live — held here, not in a control, exactly as the seam requires. */
    public boolean isRunning() {
        return running;
    }
}
