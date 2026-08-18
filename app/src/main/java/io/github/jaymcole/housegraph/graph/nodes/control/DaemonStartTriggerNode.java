package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import io.github.jaymcole.housegraph.sdk.RuntimeMode;
import javafx.scene.Node;
import javafx.scene.control.Label;

/**
 * Entry-point node that fires the moment the remote daemon's supervisor opens this graph, and
 * stays silent everywhere else - the desktop editor, {@code housegraph run}, a Load button
 * click, copy/paste, undo/redo.
 * <p>
 * {@link TriggerRepeatingNode} and every other {@link AutoStartable} node only resume a state
 * that was running when the graph was saved. That is the wrong shape for a node that binds a
 * port or opens a connection a desktop editor and a deployed server must never hold at once on
 * the same LAN: leaving it running so it survives a save would make editing and deploying fight
 * each other over the same resource. This node instead has no running state to persist at all -
 * {@link #autoStartIfWasRunning()} checks {@link RuntimeMode#isDaemon()} unconditionally, so it
 * fires on every load under the supervisor and never otherwise.
 */
@Display.Name("On Daemon Start")
@Display.Description("Fires once when the daemon starts, so a graph can run unattended on a server.")
@Kind(NodeKind.CONTROL)
@Keywords({"startup", "boot", "launch", "daemon", "server", "autostart", "entry point"})
public class DaemonStartTriggerNode extends BaseNode implements NodeContentProvider, AutoStartable {

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
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }

    @Override
    public void autoStartIfWasRunning() {
        if (RuntimeMode.isDaemon()) {
            execute();
        }
    }

    @Override
    public Node createNodeContent() {
        Label label = new Label("Fires only when opened by the daemon");
        label.setWrapText(true);
        label.setMaxWidth(140);
        label.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        return label;
    }
}
