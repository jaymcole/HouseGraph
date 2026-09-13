package io.github.jaymcole.housegraph.graph.nodes.engine;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.sdk.RuntimeMode;

/**
 * Reports {@link RuntimeMode#isDaemon()} as a wireable value, for a graph that wants to log or
 * branch on how it was started rather than change its own startup behaviour — that belongs to
 * {@code AutoStartable}, not to reading this node's output.
 */
@Display.Name("Runtime Mode")
@Display.Description("Whether this process was started by the remote daemon's supervisor, as opposed to opened by hand.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"daemon", "server", "headless", "unattended", "desktop", "engine", "mode"})
public class RuntimeModeNode extends BaseNode {

    private final NodeVariable<Boolean> isDaemon = new NodeVariable<>("Is Daemon", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        isDaemon.setValue(RuntimeMode.isDaemon());
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(isDaemon);
    }
}
