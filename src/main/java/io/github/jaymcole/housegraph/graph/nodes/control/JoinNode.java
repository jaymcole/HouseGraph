package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.ui.view.NodeContentProvider;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.Map;

/**
 * A flow <b>AND-barrier</b>: it fires its single flow-out only once <em>all</em> of its wired
 * incoming flow edges have arrived in a run. Because execution is fire-and-forget (a node
 * schedules its downstream and doesn't wait — see {@code NodeGraph}), a plain fan-in node fires on
 * the first branch to reach it; a Join instead waits for every branch, which is how you reconverge
 * parallel work (e.g. two slow analyses) before continuing.
 * <p>
 * It exposes several numbered flow-in ports; the number is adjustable with the −/+ buttons. Only
 * the ports you actually wire count toward the barrier — an unconnected port doesn't hold it up.
 * Conversely, if a wired branch never fires in a run (say an {@code If} upstream pruned it), the
 * Join simply doesn't fire that run: an AND isn't satisfied. The run still finishes.
 */
@Display.Name("Join")
public class JoinNode extends BaseNode implements NodeContentProvider {

    private static final int MIN_INPUTS = 2;
    private static final int MAX_INPUTS = 8;

    private int inputCount = MIN_INPUTS;
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);
    private Label countLabel;

    @Override
    public void process(ProcessContext ctx) {
        // Pure control-flow barrier: nothing to compute. Reaching here means every branch arrived.
    }

    @Override
    public boolean isFlowJoin() {
        return true;
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        // One numbered IN port per input. Flow edges reconnect by position on rebuild, so growing
        // the count keeps existing wires and shrinking only drops edges to the removed ports.
        for (int i = 1; i <= inputCount; i++) {
            addFlowInput(new FlowPort(String.valueOf(i), FlowPort.Direction.IN));
        }
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public Map<String, String> saveState() {
        return Map.of("inputs", String.valueOf(inputCount));
    }

    @Override
    public void loadState(Map<String, String> state) {
        inputCount = clamp(parseOr(state.get("inputs"), MIN_INPUTS));
    }

    @Override
    public Node createNodeContent() {
        countLabel = new Label();
        updateLabel();

        Button fewer = new Button("−");
        fewer.setOnAction(event -> setInputCount(inputCount - 1));
        Button more = new Button("+");
        more.setOnAction(event -> setInputCount(inputCount + 1));

        HBox box = new HBox(6, fewer, countLabel, more);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void setInputCount(int requested) {
        int clamped = clamp(requested);
        if (clamped == inputCount) {
            return;
        }
        inputCount = clamped;
        rebuildPorts();
        updateLabel();
    }

    private void updateLabel() {
        if (countLabel != null) {
            countLabel.setText("Inputs: " + inputCount);
        }
    }

    private static int clamp(int value) {
        return Math.max(MIN_INPUTS, Math.min(value, MAX_INPUTS));
    }

    private static int parseOr(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
