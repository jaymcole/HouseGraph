package io.github.jaymcole.housegraph.graph.nodes.module.fixture;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ExecutionPolicy;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Copies its input to its output, but not until <b>two</b> invocations are inside {@code process()}
 * at once — which is how a test proves that two concurrent invocations of one module really do
 * overlap, rather than passing because they happened to run one after the other.
 *
 * <p>Set to {@link ExecutionPolicy#PARALLEL} in its constructor, because the default {@code QUEUE}
 * would serialize the two runs at this node — each waiting for the other to leave {@code process()}
 * — and the barrier would then never trip.
 */
@Display.Name("Barrier Fixture")
public class BarrierFixtureNode extends BaseNode {

    /** Tripped by two invocations meeting inside {@code process()}. */
    public static final CyclicBarrier MEETING = new CyclicBarrier(2);

    /** Long enough that a loaded machine still gets there; a test that is going to pass returns at once. */
    private static final long MEET_TIMEOUT_MILLIS = 10_000;

    private final NodeVariable<Float> in = new NodeVariable<>("In", Float.class);
    private final NodeVariable<Float> out = new NodeVariable<>("Out", Float.class);

    public BarrierFixtureNode() {
        setExecutionPolicy(ExecutionPolicy.PARALLEL);
    }

    @Override
    public void process(ProcessContext ctx) {
        try {
            MEETING.await(MEET_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for a second invocation", e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("A second invocation never arrived, so the two did not overlap", e);
        }
        out.setValue(in.getValue());
    }

    @Override
    public void configureInputs() {
        addInput(in);
    }

    @Override
    public void configureOutputs() {
        addOutput(out);
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
