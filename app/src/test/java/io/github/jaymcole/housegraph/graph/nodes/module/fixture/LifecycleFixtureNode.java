package io.github.jaymcole.housegraph.graph.nodes.module.fixture;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.ProcessContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the lifecycle hooks the engine calls on it, so a test can tell whether a module's graph was
 * ever stood up and whether it was torn down again.
 *
 * <p>Static counters because the instance under test is built by {@code GraphLoader} from a save
 * file — the test never holds a reference to it, which is exactly the situation a leaked module
 * graph would leave behind too.
 */
@Display.Name("Lifecycle Fixture")
public class LifecycleFixtureNode extends BaseNode {

    public static final AtomicInteger ACTIVATIONS = new AtomicInteger();
    public static final AtomicInteger REMOVALS = new AtomicInteger();

    /** Counted down by {@code releaseResources()}, which runs on a worker and so has to be waited for. */
    public static final CountDownLatch RELEASED = new CountDownLatch(1);

    @Override
    public void process(ProcessContext ctx) {
    }

    @Override
    protected void onActivated() {
        ACTIVATIONS.incrementAndGet();
    }

    @Override
    protected void onRemoved() {
        REMOVALS.incrementAndGet();
    }

    @Override
    protected void releaseResources() {
        RELEASED.countDown();
    }

    @Override
    public void configureInputs() {
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
