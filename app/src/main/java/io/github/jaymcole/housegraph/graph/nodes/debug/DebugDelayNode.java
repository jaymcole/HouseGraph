package io.github.jaymcole.housegraph.graph.nodes.debug;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

/**
 * Blocks process() for the given number of milliseconds before returning. Useful for
 * simulating an expensive/slow node: dropping this into a flow chain shows how the
 * rest of the graph behaves around a slow node - in particular, any sibling
 * control-flow branch keeps running independently rather than waiting on this one
 * (see {@link io.github.jaymcole.housegraph.graph.NodeGraph}'s class Javadoc).
 * <p>
 * It waits in short slices and polls {@link ProcessContext#isCancelled()} between them, so a
 * superseding {@link io.github.jaymcole.housegraph.graph.ExecutionPolicy#RESTART} (which cancels a
 * run without necessarily interrupting the sleeping thread) or an elapsed timeout cuts the delay
 * short instead of running out the full duration — the canonical example of cooperative cancellation.
 */
@Display.Name("Debug Delay")
public class DebugDelayNode extends BaseNode {

    private static final long SLICE_MILLIS = 50;

    private final NodeVariable<Integer> delayMillis = new NodeVariable<>("Delay (ms)", Integer.class, true);

    @Override
    public void process(ProcessContext ctx) {
        Integer millis = delayMillis.getValue();
        if (millis == null || millis <= 0) {
            return;
        }
        long remaining = millis;
        try {
            while (remaining > 0) {
                if (ctx.isCancelled()) {
                    return; // superseded / timed out: stop waiting rather than run out the clock
                }
                long slice = Math.min(SLICE_MILLIS, remaining);
                Thread.sleep(slice);
                remaining -= slice;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void configureInputs() {
        addInput(delayMillis);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(new FlowPort("", FlowPort.Direction.IN));
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }
}
