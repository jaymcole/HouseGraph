package io.github.jaymcole.housegraph.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The error path: what a run does when a node's {@code process()} fails, and what it deliberately
 * does not do when a node was merely cancelled.
 *
 * @see FailurePolicy
 */
class FailurePathTest {

    // --- The cascade stops, and goes down the Error port instead -----------------------------------

    @Test
    void aFailedNodeDoesNotFireItsOrdinaryFlowOuts() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingFixture failing = new FailingFixture();
        RecordingFixture downstream = new RecordingFixture();
        addAll(graph, trigger, failing, downstream);
        graph.registerFlowEdge(flow(trigger, failing));
        graph.registerFlowEdge(flow(failing, downstream));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(NodeProcessingStatus.FAILED, failing.getStatus());
        assertEquals(0, downstream.runs.get(),
                "the branch past a failed node must not run against values it never produced");
    }

    @Test
    void aFailedNodeFiresItsErrorPortAndTheHandlerRuns() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingFixture failing = new FailingFixture();
        RecordingFixture handler = new RecordingFixture();
        RecordingFixture happyPath = new RecordingFixture();
        addAll(graph, trigger, failing, handler, happyPath);
        graph.registerFlowEdge(flow(trigger, failing));
        graph.registerFlowEdge(flow(failing, happyPath));
        graph.registerFlowEdge(new FlowEdge(
                failing, failing.getErrorFlowPort(), handler, handler.getFlowInputs().get(0)));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(1, handler.runs.get(), "the Error port is what a failure fires");
        assertEquals(0, happyPath.runs.get(), "and it fires instead of, not as well as, the rest");
    }

    @Test
    void theErrorMessageOutputCarriesTheFailure() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingFixture failing = new FailingFixture();
        addAll(graph, trigger, failing);
        graph.registerFlowEdge(flow(trigger, failing));

        trigger.execute();
        graph.awaitIdle();

        assertEquals("camera unreachable", failing.getErrorMessageOutput().getValue());
    }

    @Test
    void aFailureWithNoMessageStillNamesSomething() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingFixture failing = new FailingFixture();
        failing.thrown = new NullPointerException();
        addAll(graph, trigger, failing);
        graph.registerFlowEdge(flow(trigger, failing));

        trigger.execute();
        graph.awaitIdle();

        assertEquals("NullPointerException", failing.getErrorMessageOutput().getValue(),
                "an empty message downstream would read as 'no error' rather than as an undescribed one");
    }

    @Test
    void portsActivatedBeforeTheThrowAreDiscarded() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        ActivateThenFailFixture failing = new ActivateThenFailFixture();
        RecordingFixture downstream = new RecordingFixture();
        addAll(graph, trigger, failing, downstream);
        graph.registerFlowEdge(flow(trigger, failing));
        graph.registerFlowEdge(new FlowEdge(
                failing, failing.declared, downstream, downstream.getFlowInputs().get(0)));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(0, downstream.runs.get(),
                "a node should not have to order its activate() calls defensively around what might throw");
    }

    @Test
    void continueRestoresThePreviousBehaviour() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingFixture failing = new FailingFixture();
        failing.setFailurePolicy(FailurePolicy.CONTINUE);
        RecordingFixture downstream = new RecordingFixture();
        RecordingFixture handler = new RecordingFixture();
        addAll(graph, trigger, failing, downstream, handler);
        graph.registerFlowEdge(flow(trigger, failing));
        graph.registerFlowEdge(flow(failing, downstream));
        graph.registerFlowEdge(new FlowEdge(
                failing, failing.getErrorFlowPort(), handler, handler.getFlowInputs().get(0)));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(1, downstream.runs.get(), "CONTINUE cascades as though the node had succeeded");
        assertEquals(0, handler.runs.get(), "and so never routes to the error path");
    }

    // --- Cancellation is not a failure ------------------------------------------------------------

    @Test
    void aCancelledNodeDoesNotFireTheErrorPort() throws Exception {
        NodeGraph graph = new NodeGraph();
        BlockingFixture blocking = new BlockingFixture();
        blocking.setExecutionPolicy(ExecutionPolicy.RESTART);
        RecordingFixture handler = new RecordingFixture();
        addAll(graph, blocking, handler);
        graph.registerFlowEdge(new FlowEdge(
                blocking, blocking.getErrorFlowPort(), handler, handler.getFlowInputs().get(0)));

        blocking.execute();
        assertTrue(blocking.entered.await(5, TimeUnit.SECONDS), "the first run reached process()");
        blocking.execute();   // supersedes the first, cancelling it
        blocking.release.countDown();
        graph.awaitIdle();

        assertEquals(0, handler.runs.get(),
                "a run superseded by RESTART is the engine's own decision, not something a graph handles");
    }

    @Test
    void aTimedOutNodeDoesFireTheErrorPort() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        BlockingFixture slow = new BlockingFixture();
        slow.setTimeoutMillis(50);
        RecordingFixture handler = new RecordingFixture();
        addAll(graph, trigger, slow, handler);
        graph.registerFlowEdge(flow(trigger, slow));
        graph.registerFlowEdge(new FlowEdge(
                slow, slow.getErrorFlowPort(), handler, handler.getFlowInputs().get(0)));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(1, handler.runs.get(), "overrunning a timeout is a fault, unlike being superseded");
    }

    // --- Failure through required data inputs -----------------------------------------------------

    @Test
    void aRequiredInputWhoseProducerFailedFailsTheConsumerWithoutRunningIt() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingValueFixture producer = new FailingValueFixture();
        ConsumerFixture consumer = new ConsumerFixture(true);
        addAll(graph, trigger, producer, consumer);
        graph.registerEdge(new Edge(producer, producer.value, consumer, consumer.value));
        graph.registerFlowEdge(flow(trigger, consumer));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(0, consumer.runs.get(), "there is no honest value to run it against");
        assertEquals(NodeProcessingStatus.FAILED, consumer.getStatus());
        assertInstanceOf(IllegalStateException.class, consumer.getLastError());
        assertNotNull(consumer.getLastError().getCause(), "the upstream failure rides along as the cause");
        assertEquals("sensor offline", consumer.getLastError().getCause().getMessage());
    }

    @Test
    void anOptionalInputWhoseProducerFailedLeavesTheConsumerAlone() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingValueFixture producer = new FailingValueFixture();
        ConsumerFixture consumer = new ConsumerFixture(false);
        addAll(graph, trigger, producer, consumer);
        graph.registerEdge(new Edge(producer, producer.value, consumer, consumer.value));
        graph.registerFlowEdge(flow(trigger, consumer));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(1, consumer.runs.get(),
                "declaring an input optional is the author saying the node copes without it");
    }

    @Test
    void failureTravelsTheWholeChainOfRequiredInputs() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingValueFixture producer = new FailingValueFixture();
        ConsumerFixture middle = new ConsumerFixture(true);
        ConsumerFixture end = new ConsumerFixture(true);
        RecordingFixture handler = new RecordingFixture();
        addAll(graph, trigger, producer, middle, end, handler);
        graph.registerEdge(new Edge(producer, producer.value, middle, middle.value));
        graph.registerEdge(new Edge(middle, middle.passedThrough, end, end.value));
        graph.registerFlowEdge(flow(trigger, end));
        graph.registerFlowEdge(new FlowEdge(
                end, end.getErrorFlowPort(), handler, handler.getFlowInputs().get(0)));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(0, middle.runs.get());
        assertEquals(0, end.runs.get());
        assertEquals(1, handler.runs.get(), "it reaches the first Error port wired anywhere along the chain");
    }

    @Test
    void aHandlerReadingTheErrorMessageIsNotItselfFailedByIt() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingFixture failing = new FailingFixture();
        TextConsumerFixture reporter = new TextConsumerFixture();
        addAll(graph, trigger, failing, reporter);
        graph.registerFlowEdge(flow(trigger, failing));
        graph.registerFlowEdge(new FlowEdge(
                failing, failing.getErrorFlowPort(), reporter, reporter.getFlowInputs().get(0)));
        graph.registerEdge(new Edge(failing, failing.getErrorMessageOutput(), reporter, reporter.text));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(1, reporter.runs.get(),
                "failing a handler for reading the failure would make the error path unusable");
        assertEquals("camera unreachable", reporter.seen,
                "and it reads the message rather than a stale value");
    }

    @Test
    void aNodeFailedByAPlainPullStillSetsItsErrorMessage() {
        NodeGraph graph = new NodeGraph();
        FailingFixture failing = new FailingFixture();
        addAll(graph, failing);

        failing.beginProcessing();   // a synchronous resolve, with no cascade to route

        assertEquals("camera unreachable", failing.getErrorMessageOutput().getValue(),
                "the message belongs to the failure, not to the cascade that would have carried it");
    }

    @Test
    void aContinueNodeStillSetsItsErrorMessage() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingFixture failing = new FailingFixture();
        failing.setFailurePolicy(FailurePolicy.CONTINUE);
        addAll(graph, trigger, failing);
        graph.registerFlowEdge(flow(trigger, failing));

        trigger.execute();
        graph.awaitIdle();

        assertEquals("camera unreachable", failing.getErrorMessageOutput().getValue(),
                "carrying on does not mean the downstream may not know what went wrong");
    }

    @Test
    void aConsumerFailedByItsDependencyReportsWhichInputAndWhichProducer() throws Exception {
        NodeGraph graph = new NodeGraph();
        TriggerFixture trigger = new TriggerFixture();
        FailingValueFixture producer = new FailingValueFixture();
        ConsumerFixture consumer = new ConsumerFixture(true);
        addAll(graph, trigger, producer, consumer);
        graph.registerEdge(new Edge(producer, producer.value, consumer, consumer.value));
        graph.registerFlowEdge(flow(trigger, consumer));

        trigger.execute();
        graph.awaitIdle();

        String message = consumer.getErrorMessageOutput().getValue();
        assertNotNull(message);
        assertTrue(message.contains("Value"), "names the input that could not be resolved: " + message);
    }

    // --- Isolation ---------------------------------------------------------------------------------

    @Test
    void concurrentRunsEachCarryTheirOwnErrorMessage() throws Exception {
        NodeGraph graph = new NodeGraph();
        CountingFailureFixture failing = new CountingFailureFixture();
        failing.setExecutionPolicy(ExecutionPolicy.PARALLEL);
        MessageCollectingFixture collector = new MessageCollectingFixture();
        // PARALLEL on the collector too: at a mid-cascade node the default QUEUE coalesces
        // overlapping arrivals into one, which would drop messages here whenever the runs actually
        // overlap - making this a test that passes alone and flakes under load. The isolation being
        // asserted is of each run's error message, not of the gate.
        collector.setExecutionPolicy(ExecutionPolicy.PARALLEL);
        addAll(graph, failing, collector);
        graph.registerFlowEdge(new FlowEdge(
                failing, failing.getErrorFlowPort(), collector, collector.getFlowInputs().get(0)));
        graph.registerEdge(new Edge(failing, failing.getErrorMessageOutput(), collector, collector.text));

        int runs = 8;
        for (int i = 0; i < runs; i++) {
            failing.execute();
        }
        graph.awaitIdle();

        List<String> seen = collector.seen();
        assertEquals(runs, seen.size());
        assertEquals(runs, new java.util.TreeSet<>(seen).size(),
                "two runs failing the same node must not overwrite one another's message");
    }

    // --- Fixtures ----------------------------------------------------------------------------------

    private static void addAll(NodeGraph graph, BaseNode... nodes) {
        for (BaseNode node : nodes) {
            graph.addNode(node);
        }
    }

    private static FlowEdge flow(BaseNode source, BaseNode target) {
        return new FlowEdge(source, source.getFlowOutputs().get(0), target, target.getFlowInputs().get(0));
    }

    /** A flow source: no flow-in, one flow-out, run with {@code execute()}. */
    private static class TriggerFixture extends BaseNode {
        @Override public void process(ProcessContext ctx) { }
        @Override public void configureInputs() { }
        @Override public void configureOutputs() { }
        @Override public void configureFlowOutputs() { addFlowOutput(new FlowPort("", FlowPort.Direction.OUT)); }
    }

    /** Counts how often it ran; one flow-in, one flow-out. */
    private static class RecordingFixture extends BaseNode {
        final AtomicInteger runs = new AtomicInteger();
        @Override public void process(ProcessContext ctx) { runs.incrementAndGet(); }
        @Override public void configureInputs() { }
        @Override public void configureOutputs() { }
        @Override public void configureFlowInputs() { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
        @Override public void configureFlowOutputs() { addFlowOutput(new FlowPort("", FlowPort.Direction.OUT)); }
    }

    /** Throws from {@code process()}, standing in for a camera or an HTTP call that failed. */
    private static class FailingFixture extends BaseNode {
        RuntimeException thrown = new IllegalStateException("camera unreachable");
        @Override public void process(ProcessContext ctx) { throw thrown; }
        @Override public void configureInputs() { }
        @Override public void configureOutputs() { }
        @Override public void configureFlowInputs() { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
        @Override public void configureFlowOutputs() { addFlowOutput(new FlowPort("", FlowPort.Direction.OUT)); }
    }

    /** Activates a declared flow-out and then throws — the shape node authors used to write defensively. */
    private static class ActivateThenFailFixture extends BaseNode {
        final FlowPort declared = new FlowPort("Checked", FlowPort.Direction.OUT);
        @Override public void process(ProcessContext ctx) {
            activate(declared);
            throw new IllegalStateException("sync failed");
        }
        @Override public void configureInputs() { }
        @Override public void configureOutputs() { }
        @Override public void configureFlowInputs() { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
        @Override public void configureFlowOutputs() { addFlowOutput(declared); }
    }

    /** Blocks in {@code process()} until released, polling cancellation so it can be superseded. */
    private static class BlockingFixture extends BaseNode {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public void process(ProcessContext ctx) {
            entered.countDown();
            while (release.getCount() > 0) {
                ctx.checkCancelled();
                try {
                    if (release.await(20, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        @Override public void configureInputs() { }
        @Override public void configureOutputs() { }
        @Override public void configureFlowInputs() { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
        @Override public void configureFlowOutputs() { addFlowOutput(new FlowPort("", FlowPort.Direction.OUT)); }
    }

    /** A pure data producer that fails instead of producing. */
    private static class FailingValueFixture extends BaseNode {
        final NodeVariable<String> value = new NodeVariable<>("Value", String.class);
        @Override public void process(ProcessContext ctx) { throw new IllegalStateException("sensor offline"); }
        @Override public void configureInputs() { }
        @Override public void configureOutputs() { addOutput(value); }
    }

    /** Consumes one value, required or not, and passes it through. */
    private static class ConsumerFixture extends BaseNode {
        final AtomicInteger runs = new AtomicInteger();
        final NodeVariable<String> value = new NodeVariable<>("Value", String.class);
        final NodeVariable<String> passedThrough = new NodeVariable<>("Out", String.class);
        ConsumerFixture(boolean required) {
            if (required) {
                value.required();
            }
        }
        @Override public void process(ProcessContext ctx) {
            runs.incrementAndGet();
            passedThrough.setValue(value.getValue());
        }
        @Override public void configureInputs() { addInput(value); }
        @Override public void configureOutputs() { addOutput(passedThrough); }
        @Override public void configureFlowInputs() { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
    }

    /** A handler that reads a required text input — what an error reporter looks like. */
    private static class TextConsumerFixture extends BaseNode {
        final AtomicInteger runs = new AtomicInteger();
        final NodeVariable<String> text = new NodeVariable<>("Text", String.class).required();
        volatile String seen;
        @Override public void process(ProcessContext ctx) {
            runs.incrementAndGet();
            seen = text.getValue();
        }
        @Override public void configureInputs() { addInput(text); }
        @Override public void configureOutputs() { }
        @Override public void configureFlowInputs() { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
    }

    /** A flow source that fails with a different message every run. */
    private static class CountingFailureFixture extends BaseNode {
        private final AtomicInteger attempt = new AtomicInteger();
        @Override public void process(ProcessContext ctx) {
            throw new IllegalStateException("failure " + attempt.incrementAndGet());
        }
        @Override public void configureInputs() { }
        @Override public void configureOutputs() { }
        @Override public void configureFlowOutputs() { addFlowOutput(new FlowPort("", FlowPort.Direction.OUT)); }
    }

    /** Records every error message it is handed, across concurrent runs. */
    private static class MessageCollectingFixture extends BaseNode {
        final NodeVariable<String> text = new NodeVariable<>("Text", String.class).required();
        private final List<String> collected = Collections.synchronizedList(new ArrayList<>());
        @Override public void process(ProcessContext ctx) { collected.add(text.getValue()); }
        List<String> seen() { return new ArrayList<>(collected); }
        @Override public void configureInputs() { addInput(text); }
        @Override public void configureOutputs() { }
        @Override public void configureFlowInputs() { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
    }
}
