package io.github.jaymcole.housegraph.graph.nodes.debug;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Executable;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;

/**
 * Blocks process() for the given number of milliseconds before returning. Useful for
 * simulating an expensive/slow node: process() always runs synchronously on whatever
 * thread called resolve()/execute() (in practice, the JavaFX Application Thread), so
 * dropping this into a data or flow chain shows exactly how the rest of the app
 * behaves while a node takes a long time - including that the UI freezes for the
 * duration, since nothing in this codebase runs node execution off-thread.
 */
@Display.Name("Debug Delay")
@Executable.ExecutableIn
@Executable.ExecutableOut
public class DebugDelayNode extends BaseNode {

    private final NodeVariable<Integer> delayMillis = new NodeVariable<>("Delay (ms)", Integer.class, true);

    @Override
    public void process() {
        Integer millis = delayMillis.getValue();
        if (millis == null || millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
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
}
