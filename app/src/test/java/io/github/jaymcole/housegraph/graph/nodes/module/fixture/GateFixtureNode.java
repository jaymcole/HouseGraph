package io.github.jaymcole.housegraph.graph.nodes.module.fixture;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.ProcessContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Waits inside {@code process()} until it is released or cancelled, so a test can hold a module's
 * run open and then ask whether cancelling the consumer reached in here.
 *
 * <p>Polls in short slices exactly as a well-behaved slow node does, which is the whole point: the
 * cancellation a driving {@code ModuleNode} passes down is cooperative, so what is being tested is
 * that a node inside the module <em>sees</em> it.
 */
@Display.Name("Gate Fixture")
public class GateFixtureNode extends BaseNode {

    /** Counted down as soon as a firing is inside {@code process()}. */
    public static final CountDownLatch ENTERED = new CountDownLatch(1);

    /** Counted down when a firing left because it was cancelled rather than released. */
    public static final CountDownLatch CANCELLED = new CountDownLatch(1);

    /** Released by a test that wants the wait to end normally. */
    public static final CountDownLatch RELEASE = new CountDownLatch(1);

    @Override
    public void process(ProcessContext ctx) {
        ENTERED.countDown();
        try {
            while (!RELEASE.await(5, TimeUnit.MILLISECONDS)) {
                if (ctx.isCancelled()) {
                    CANCELLED.countDown();
                    ctx.checkCancelled();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CANCELLED.countDown();
        }
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
