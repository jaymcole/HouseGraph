package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.graph.nodes.camera.DiscoverCamerasNode;
import io.github.jaymcole.housegraph.graph.nodes.control.IfNode;
import io.github.jaymcole.housegraph.graph.nodes.control.JoinNode;
import io.github.jaymcole.housegraph.graph.nodes.control.TriggerNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantIntegerNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeGraphTest {

    @Test
    void dataEdgePropagatesValueAcrossNodes() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);

        output(constant).setValue(5f);
        graph.registerEdge(new Edge(constant, output(constant), add, input(add, "V1")));

        add.beginProcessing();

        assertEquals(5f, output(add).getValue());
    }

    @Test
    void resolveIsFreshEveryCallNotAStaleCache() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);
        graph.registerEdge(new Edge(constant, output(constant), add, input(add, "V1")));

        output(constant).setValue(2f);
        add.beginProcessing();
        assertEquals(2f, output(add).getValue());

        output(constant).setValue(9f);
        add.beginProcessing();
        assertEquals(9f, output(add).getValue(), "a second beginProcessing() call must not serve a stale cached value");
    }

    @Test
    void dataCycleThrowsInsteadOfOverflowingTheStack() {
        NodeGraph graph = new NodeGraph();
        AddNode a = new AddNode();
        AddNode b = new AddNode();
        graph.addNode(a);
        graph.addNode(b);

        graph.registerEdge(new Edge(a, output(a), b, input(b, "V1")));
        graph.registerEdge(new Edge(b, output(b), a, input(a, "V1")));

        assertThrows(IllegalStateException.class, a::beginProcessing);
    }

    @Test
    void triggerCascadesAlongFlowEdgesAndRunsProcess() {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(trigger);
        graph.addNode(constant);
        graph.addNode(add);

        output(constant).setValue(4f);
        graph.registerEdge(new Edge(constant, output(constant), add, input(add, "V1")));
        graph.registerFlowEdge(flowEdge(trigger, add));

        // execute() now runs on a background thread and returns immediately (see
        // NodeGraph's class Javadoc); awaitIdle() blocks until that background work
        // is done, so the assertion below sees its effects.
        trigger.execute();
        graph.awaitIdle();

        assertEquals(4f, output(add).getValue(), "add was reached only via a flow edge, with no direct trigger->add data edge");
    }

    @Test
    void flowCycleIsDedupedInsteadOfInfiniteLooping() {
        NodeGraph graph = new NodeGraph();
        AddNode a = new AddNode();
        AddNode b = new AddNode();
        graph.addNode(a);
        graph.addNode(b);

        graph.registerFlowEdge(flowEdge(a, b));
        graph.registerFlowEdge(flowEdge(b, a));

        // A cycle in the flow graph must be deduped (each node cascaded through once
        // per pass) rather than infinite-looping - awaitIdle() returning at all,
        // rather than hanging, is the real assertion here.
        a.execute();
        assertDoesNotThrow(graph::awaitIdle);
    }

    @Test
    void independentFlowBranchesDontBlockOnEachOther() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();

        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        BlockingNode slowBranch = new BlockingNode(slowStarted, releaseSlow);

        // The fast branch's release latch starts already open, so it runs to completion
        // as soon as its process() is entered.
        CountDownLatch fastStarted = new CountDownLatch(1);
        BlockingNode fastBranch = new BlockingNode(fastStarted, new CountDownLatch(0));

        graph.addNode(trigger);
        graph.addNode(fastBranch);
        graph.addNode(slowBranch);
        graph.registerFlowEdge(flowEdge(trigger, fastBranch));
        graph.registerFlowEdge(flowEdge(trigger, slowBranch));

        trigger.execute();

        // The two branches must run side by side, not one after the other: we wait for the
        // slow branch to reach (and stay blocked in) process(), and for the fast branch to
        // run in that same window. If a slow branch held up its siblings, the fast branch's
        // latch would never fire and the await below would time out - so this can't pass by
        // luck the way an immediate status check could.
        assertTrue(slowStarted.await(2, TimeUnit.SECONDS), "slow branch should have started");
        assertTrue(fastStarted.await(2, TimeUnit.SECONDS), "fast branch should run while the slow one is still blocked");

        releaseSlow.countDown();
        graph.awaitIdle();
        assertTrue(fastBranch.getStatus().isComplete());
        assertTrue(slowBranch.getStatus().isComplete());
    }

    /** A node whose process() blocks until released, for deterministically testing concurrent flow branches. */
    private static final class BlockingNode extends BaseNode {
        private final CountDownLatch started;
        private final CountDownLatch release;

        BlockingNode(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        @Override
        public void process() {
            started.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS), "test never released the blocking node");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
    }

    @Test
    void ifNodeRoutesToTrueOrFalseBranchByCondition() {
        assertTrue(ifNodeTookTrueBranch(1f), "a non-zero condition should cascade down the True branch only");
        assertFalse(ifNodeTookTrueBranch(0f), "a zero condition should cascade down the False branch only");
    }

    /**
     * Runs an {@link IfNode} with the given condition, wired to a distinct target on
     * each branch, and returns whether the True branch's target ran. Asserts exactly
     * one branch ran (proving selective cascade, not just that the right one fired).
     */
    private static boolean ifNodeTookTrueBranch(float condition) {
        NodeGraph graph = new NodeGraph();
        IfNode ifNode = new IfNode();
        BaseNode trueTarget = new AddNode();
        BaseNode falseTarget = new AddNode();
        graph.addNode(ifNode);
        graph.addNode(trueTarget);
        graph.addNode(falseTarget);

        FlowPort truePort = ifNode.getFlowOutputs().get(0);
        FlowPort falsePort = ifNode.getFlowOutputs().get(1);
        graph.registerFlowEdge(new FlowEdge(ifNode, truePort, trueTarget, trueTarget.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(ifNode, falsePort, falseTarget, falseTarget.getFlowInputs().get(0)));

        input(ifNode, "Condition").setValue(condition);
        ifNode.execute();
        graph.awaitIdle();

        boolean trueRan = trueTarget.getStatus().isComplete();
        boolean falseRan = falseTarget.getStatus().isComplete();
        assertTrue(trueRan != falseRan, "exactly one branch should run, not both/neither");
        return trueRan;
    }

    @Test
    void removingANodePurgesItsEdges() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);
        graph.registerEdge(new Edge(constant, output(constant), add, input(add, "V1")));

        graph.removeNode(constant);

        assertTrue(graph.getIncomingDataEdges(add).isEmpty());
        assertFalse(graph.getNodes().contains(constant));
    }

    @Test
    void registeringAnEdgeForAnUnregisteredNodeFails() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(add); // constant deliberately never added

        Edge edge = new Edge(constant, output(constant), add, input(add, "V1"));
        assertThrows(IllegalStateException.class, () -> graph.registerEdge(edge));
    }

    @Test
    void mismatchedVariableTypesAreRejected() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);

        // A String output into a Float input: no converter bridges the pair, so it stays rejected.
        // (Integer/Double/Boolean -> Float would now be accepted via a hidden converter.)
        NodeVariable<String> notAFloat = new NodeVariable<>("bogus", String.class);
        Edge edge = new Edge(constant, notAFloat, add, input(add, "V1"));

        assertThrows(IllegalArgumentException.class, () -> graph.registerEdge(edge));
    }

    @Test
    void convertibleVariableTypesAreAcceptedAndCoercedOnPropagation() {
        NodeGraph graph = new NodeGraph();
        ConstantIntegerNode constant = new ConstantIntegerNode();
        AddNode add = new AddNode();
        graph.addNode(constant);
        graph.addNode(add);

        @SuppressWarnings("unchecked")
        NodeVariable<Integer> intOut = constant.getOutputs().get(0);
        intOut.setValue(3);

        // An Integer output feeding a Float input: not assignable, but a hidden converter bridges it,
        // so the edge attaches and the value arrives at the Float input already coerced to 3f.
        Edge edge = new Edge(constant, intOut, add, input(add, "V1"));
        assertDoesNotThrow(() -> graph.registerEdge(edge));

        add.beginProcessing();

        assertEquals(3f, output(add).getValue());
    }

    @Test
    void assignableButNotExactTypesAreAccepted() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        EdgeHookNode sink = new EdgeHookNode();
        graph.addNode(constant);
        graph.addNode(sink);

        // A Float output into an Object input: not an exact type match, but assignable.
        Edge edge = new Edge(constant, output(constant), sink, sink.anyInput);
        assertDoesNotThrow(() -> graph.registerEdge(edge));
    }

    @Test
    void registeringAndRemovingAnEdgeFiresTheTargetsEdgeHooks() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        EdgeHookNode sink = new EdgeHookNode();
        graph.addNode(constant);
        graph.addNode(sink);

        Edge edge = new Edge(constant, output(constant), sink, sink.anyInput);
        graph.registerEdge(edge);
        assertEquals(1, sink.added, "onInputEdgeAdded fires when an edge into the node is registered");
        assertEquals(edge, sink.lastAdded);

        graph.removeEdge(edge);
        assertEquals(1, sink.removed, "onInputEdgeRemoved fires when that edge is removed");
        assertEquals(edge, sink.lastRemoved);
    }

    @Test
    void deletingASourceNodeFiresTheTargetsEdgeRemovedHook() {
        NodeGraph graph = new NodeGraph();
        ConstantFloatNode constant = new ConstantFloatNode();
        EdgeHookNode sink = new EdgeHookNode();
        graph.addNode(constant);
        graph.addNode(sink);
        graph.registerEdge(new Edge(constant, output(constant), sink, sink.anyInput));

        graph.removeNode(constant);

        assertEquals(1, sink.removed, "cascading an edge removal via node deletion still notifies the target");
    }

    /** A node with a single Object input that records its edge-hook calls. */
    private static final class EdgeHookNode extends BaseNode {
        final NodeVariable<Object> anyInput = new NodeVariable<>("Any", Object.class);
        int added = 0;
        int removed = 0;
        Edge lastAdded;
        Edge lastRemoved;

        @Override
        public void process() {
        }

        @Override
        public void configureInputs() {
            addInput(anyInput);
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        protected void onInputEdgeAdded(Edge edge) {
            added++;
            lastAdded = edge;
        }

        @Override
        protected void onInputEdgeRemoved(Edge edge) {
            removed++;
            lastRemoved = edge;
        }
    }

    @Test
    void runningBeforeBeingAddedToAGraphFailsClearly() {
        AddNode add = new AddNode();
        assertThrows(IllegalStateException.class, add::beginProcessing);
    }

    @Test
    void eachTriggerAppliesItsOwnPayloadSoRapidEventsDontClobber() {
        NodeGraph graph = new NodeGraph();
        PayloadSourceNode source = new PayloadSourceNode();
        RecorderNode recorder = new RecorderNode();
        graph.addNode(source);
        graph.addNode(recorder);
        graph.registerEdge(new Edge(source, source.out, recorder, recorder.in));
        graph.registerFlowEdge(flowEdge(source, recorder));

        // Two triggers submitted back-to-back, each with its own payload applied inside
        // the pass. If the value were written from here (the "event" side) before the
        // passes ran, both passes would see the last value ("b"); per-pass preparation
        // makes each pass see its own.
        graph.execute(source, () -> source.out.setValue("a"));
        graph.execute(source, () -> source.out.setValue("b"));
        graph.awaitIdle();

        assertEquals(List.of("a", "b"), recorder.received);
    }

    /** A trigger-style source whose String output is set per-pass by the trigger's prepare step. */
    private static final class PayloadSourceNode extends BaseNode {
        final NodeVariable<String> out = new NodeVariable<>("Out", String.class);

        @Override
        public void process() {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(out);
        }

        @Override
        public void configureFlowOutputs() {
            addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
        }
    }

    /** Records the value pulled onto its input each time it runs. */
    private static final class RecorderNode extends BaseNode {
        final NodeVariable<String> in = new NodeVariable<>("In", String.class);
        final List<String> received = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void process() {
            received.add(in.getValue());
        }

        @Override
        public void configureInputs() {
            addInput(in);
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowInputs() {
            addFlowInput(new FlowPort("", FlowPort.Direction.IN));
        }
    }

    @Test
    void addingAndRemovingANodeFiresItsLifecycleHooks() {
        NodeGraph graph = new NodeGraph();
        LifecycleNode node = new LifecycleNode();

        graph.addNode(node);
        assertEquals(1, node.activatedCount, "onActivated fires when the node joins the graph");

        graph.removeNode(node);
        assertEquals(1, node.removedCount, "onRemoved fires when the node leaves the graph");
    }

    @Test
    void removingAnAbsentNodeDoesNotFireOnRemoved() {
        NodeGraph graph = new NodeGraph();
        LifecycleNode node = new LifecycleNode();

        graph.removeNode(node); // never added
        assertEquals(0, node.removedCount);
    }

    @Test
    void disposeCleansUpEveryNode() {
        NodeGraph graph = new NodeGraph();
        LifecycleNode a = new LifecycleNode();
        LifecycleNode b = new LifecycleNode();
        graph.addNode(a);
        graph.addNode(b);

        graph.dispose();

        assertEquals(1, a.removedCount);
        assertEquals(1, b.removedCount);
        assertTrue(graph.getNodes().isEmpty());
    }

    /** A node that counts its lifecycle-hook calls, for asserting activate/dispose behaviour. */
    private static final class LifecycleNode extends BaseNode {
        int activatedCount = 0;
        int removedCount = 0;

        @Override
        protected void onActivated() {
            activatedCount++;
        }

        @Override
        protected void onRemoved() {
            removedCount++;
        }

        @Override
        public void process() {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }
    }

    @Test
    void reconfigureRebuildsPortsFromCurrentSettings() {
        DynamicNode node = new DynamicNode();
        assertEquals(List.of("a"), outputNames(node));

        node.optionNames = List.of("x", "y");
        node.reconfigure();

        assertEquals(List.of("x", "y"), outputNames(node), "outputs should mirror the new settings after reconfigure");
    }

    @SuppressWarnings("rawtypes")
    private static List<String> outputNames(BaseNode node) {
        List<String> names = new ArrayList<>();
        for (NodeVariable variable : node.getOutputs()) {
            names.add(variable.name);
        }
        return names;
    }

    /** A node whose outputs mirror an editable list, for testing reconfigure(). */
    private static final class DynamicNode extends BaseNode {
        List<String> optionNames = List.of("a");

        @Override
        public void process() {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            for (String name : optionNames) {
                addOutput(new NodeVariable<>(name, String.class));
            }
        }
    }

    @Test
    void executionEntryPointsAreFlowSourcesOrDeclaredSelfTriggers() {
        // Flow source (flow-out, no flow-in): can only ever run via a direct execute().
        assertTrue(new TriggerNode().isExecutionEntryPoint());
        // Reached along an incoming flow edge (has a flow-in), so not an entry point.
        assertFalse(new AddNode().isExecutionEntryPoint(), "a mid-cascade node is not an entry point");
        // No flow ports at all: pulled as a data dependency, never executed.
        assertFalse(new ConstantFloatNode().isExecutionEntryPoint(), "a pure data node is not an entry point");
        // Self-triggers via its Discover button despite also having a flow-in; declared explicitly.
        assertTrue(new DiscoverCamerasNode().isExecutionEntryPoint(), "a self-triggering node overrides to true");
    }

    @Test
    void separateTriggersRunConcurrently() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode slowTrigger = new TriggerNode();
        TriggerNode fastTrigger = new TriggerNode();

        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        BlockingNode slow = new BlockingNode(slowStarted, releaseSlow);
        CountDownLatch fastStarted = new CountDownLatch(1);
        BlockingNode fast = new BlockingNode(fastStarted, new CountDownLatch(0));

        graph.addNode(slowTrigger);
        graph.addNode(fastTrigger);
        graph.addNode(slow);
        graph.addNode(fast);
        graph.registerFlowEdge(flowEdge(slowTrigger, slow));
        graph.registerFlowEdge(flowEdge(fastTrigger, fast));

        slowTrigger.execute(); // starts a run that blocks in `slow`
        assertTrue(slowStarted.await(2, TimeUnit.SECONDS), "the slow trigger's run should reach its blocking node");
        // A separate trigger's run must not wait behind the blocked one (it would, on the old
        // single serialized execution thread) - the fast run proves it runs concurrently.
        fastTrigger.execute();
        assertTrue(fastStarted.await(2, TimeUnit.SECONDS), "a second trigger runs concurrently while the first is blocked");

        releaseSlow.countDown();
        graph.awaitIdle();
        assertTrue(slow.getStatus().isComplete());
        assertTrue(fast.getStatus().isComplete());
    }

    @Test
    void parallelPolicyRunsConcurrentRunsOfTheSameTrigger() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();

        // Blocks until TWO runs have entered its process() at once. Only reachable if both
        // PARALLEL runs are in flight concurrently AND the per-run resolution lock lets two
        // runs into the same shared node at the same time - a global node lock would deadlock
        // this at one entry. The shared node is itself set PARALLEL: its default QUEUE policy would
        // otherwise gate the second run's re-entrant arrival (see the mid-cascade policy tests),
        // serializing it and never letting both enter at once.
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ConcurrentNode shared = new ConcurrentNode(bothEntered, release);

        graph.addNode(trigger);
        graph.addNode(shared);
        graph.registerFlowEdge(flowEdge(trigger, shared));
        trigger.setExecutionPolicy(ExecutionPolicy.PARALLEL);
        shared.setExecutionPolicy(ExecutionPolicy.PARALLEL);

        trigger.execute();
        trigger.execute();
        assertTrue(bothEntered.await(2, TimeUnit.SECONDS), "both PARALLEL runs should enter the shared node concurrently");

        release.countDown();
        graph.awaitIdle();
        assertEquals(2, shared.processCount.get(), "PARALLEL runs both execute");
    }

    /** Records how many runs are inside process() at once by blocking until an expected count arrives. */
    private static final class ConcurrentNode extends BaseNode {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        final AtomicInteger processCount = new AtomicInteger();

        ConcurrentNode(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void process() {
            processCount.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS), "test never released the concurrent node");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
    }

    @Test
    void joinFiresOnlyAfterAllBranchesArrive() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        CountDownLatch releaseSlow = new CountDownLatch(1);
        GateNode slow = new GateNode(releaseSlow);
        GateNode fast = new GateNode(new CountDownLatch(0));
        JoinNode join = new JoinNode();
        SignalSink sink = new SignalSink();
        graph.addNode(trigger);
        graph.addNode(slow);
        graph.addNode(fast);
        graph.addNode(join);
        graph.addNode(sink);

        // trigger fans out to both branches; each branch feeds a different join input.
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), slow, slow.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), fast, fast.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(slow, slow.getFlowOutputs().get(0), join, join.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(fast, fast.getFlowOutputs().get(0), join, join.getFlowInputs().get(1)));
        graph.registerFlowEdge(flowEdge(join, sink));

        trigger.execute();
        assertTrue(slow.started.await(2, TimeUnit.SECONDS), "the slow branch should start");
        // The fast branch has arrived at the join, but an AND-join must wait for the slow branch too.
        assertFalse(sink.ran.await(300, TimeUnit.MILLISECONDS), "the join must not fire on the first branch alone");

        releaseSlow.countDown();
        assertTrue(sink.ran.await(2, TimeUnit.SECONDS), "the join fires once every branch has arrived");
        graph.awaitIdle();
        assertEquals(1, sink.processCount.get(), "the join fires its downstream exactly once");
    }

    @Test
    void joinCountsOnlyWiredPortsNotEmptyOnes() {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        JoinNode join = new JoinNode();
        join.loadState(Map.of("inputs", "3")); // three ports, but we wire only two
        GateNode a = new GateNode(new CountDownLatch(0));
        GateNode b = new GateNode(new CountDownLatch(0));
        CountingSinkNode sink = new CountingSinkNode();
        graph.addNode(trigger);
        graph.addNode(join);
        graph.addNode(a);
        graph.addNode(b);
        graph.addNode(sink);

        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), a, a.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), b, b.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(a, a.getFlowOutputs().get(0), join, join.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(b, b.getFlowOutputs().get(0), join, join.getFlowInputs().get(1)));
        graph.registerFlowEdge(flowEdge(join, sink));

        trigger.execute();
        graph.awaitIdle();
        assertEquals(1, sink.processCount.get(), "expected arrivals = wired edges (2); the third, unwired port doesn't hold the join up");
    }

    @Test
    void andJoinDoesNotFireWhenABranchIsPrunedYetTheRunStillFinishes() {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        IfNode ifNode = new IfNode();
        JoinNode join = new JoinNode();
        CountingSinkNode sink = new CountingSinkNode();
        graph.addNode(trigger);
        graph.addNode(ifNode);
        graph.addNode(join);
        graph.addNode(sink);

        graph.registerFlowEdge(flowEdge(trigger, ifNode));
        graph.registerFlowEdge(new FlowEdge(ifNode, ifNode.getFlowOutputs().get(0), join, join.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(ifNode, ifNode.getFlowOutputs().get(1), join, join.getFlowInputs().get(1)));
        graph.registerFlowEdge(flowEdge(join, sink));

        input(ifNode, "Condition").setValue(1f); // only the True branch fires; the False branch is pruned
        trigger.execute();

        // The join wants both branches but only one arrives - it never fires, but the run must
        // still quiesce rather than hang waiting for the branch that will never come.
        assertDoesNotThrow(graph::awaitIdle);
        assertEquals(0, sink.processCount.get(), "an AND-join with a pruned branch does not fire");
    }

    /** A flow sink that counts its runs and signals a latch, for asserting when (and whether) it fired. */
    private static final class SignalSink extends BaseNode {
        final AtomicInteger processCount = new AtomicInteger();
        final CountDownLatch ran = new CountDownLatch(1);

        @Override
        public void process() {
            processCount.incrementAndGet();
            ran.countDown();
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
    }

    @Test
    void concurrencyLimitKeepsConcurrentRunsOutOfANodeAtOnce() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ConcurrentNode shared = new ConcurrentNode(bothEntered, release);
        shared.setMaxConcurrency(1);

        graph.addNode(trigger);
        graph.addNode(shared);
        graph.registerFlowEdge(flowEdge(trigger, shared));
        trigger.setExecutionPolicy(ExecutionPolicy.PARALLEL);

        trigger.execute();
        trigger.execute();
        // Two PARALLEL runs, but a limit of 1 means only one may be inside process() at a time;
        // the second is parked on the permit, so they never both enter (contrast with the
        // parallel test, where they do).
        assertFalse(bothEntered.await(400, TimeUnit.MILLISECONDS), "the limit must hold the second run out while the first is inside the node");
        assertEquals(1, shared.processCount.get(), "exactly one run is inside the limited node");

        release.countDown(); // first run finishes and frees the permit
        graph.awaitIdle();
        assertEquals(2, shared.processCount.get(), "the second run enters once the first releases the node");
    }

    @Test
    void timeoutAbortsAnOverrunningNodeAndMarksItFailed() {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        SleepNode sleeper = new SleepNode(4000); // would block for 4s
        sleeper.setTimeoutMillis(150);

        graph.addNode(trigger);
        graph.addNode(sleeper);
        graph.registerFlowEdge(flowEdge(trigger, sleeper));

        long startNanos = System.nanoTime();
        trigger.execute();
        graph.awaitIdle();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(NodeProcessingStatus.FAILED, sleeper.getStatus(), "an overrun node ends FAILED");
        assertTrue(sleeper.getLastError() instanceof TimeoutException, "the failure is reported as a TimeoutException");
        assertTrue(elapsedMillis < 2000, "the node was aborted well before its 4s block would finish (took " + elapsedMillis + " ms)");
    }

    /** Sleeps for a fixed time, restoring the interrupt flag if interrupted - for testing the process timeout. */
    private static final class SleepNode extends BaseNode {
        private final long millis;

        SleepNode(long millis) {
            this.millis = millis;
        }

        @Override
        public void process() {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
    }

    @Test
    void dropPolicyIgnoresARetriggerWhileAPassIsInFlight() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        CountDownLatch release = new CountDownLatch(1);
        GateNode gate = new GateNode(release);
        CountingSinkNode sink = new CountingSinkNode();
        graph.addNode(trigger);
        graph.addNode(gate);
        graph.addNode(sink);
        graph.registerFlowEdge(flowEdge(trigger, gate));
        graph.registerFlowEdge(flowEdge(gate, sink));

        trigger.setExecutionPolicy(ExecutionPolicy.DROP);

        trigger.execute(); // pass 1: blocks in the gate
        assertTrue(gate.started.await(2, TimeUnit.SECONDS), "first pass should reach the gate");
        trigger.execute(); // dropped: a pass from this node is already in flight

        release.countDown();
        graph.awaitIdle();

        assertEquals(1, gate.processCount.get(), "the dropped trigger must not start a second pass");
        assertEquals(1, sink.processCount.get());
    }

    @Test
    void queuePolicyCoalescesToTheLatestPendingTrigger() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        PayloadSourceNode source = new PayloadSourceNode();
        CountDownLatch release = new CountDownLatch(1);
        GateNode gate = new GateNode(release);
        RecorderNode recorder = new RecorderNode();
        graph.addNode(source);
        graph.addNode(gate);
        graph.addNode(recorder);
        graph.registerEdge(new Edge(source, source.out, recorder, recorder.in));
        graph.registerFlowEdge(flowEdge(source, gate));
        graph.registerFlowEdge(flowEdge(gate, recorder));

        // QUEUE is the default, but set it explicitly to document intent.
        source.setExecutionPolicy(ExecutionPolicy.QUEUE);

        graph.execute(source, () -> source.out.setValue("a")); // pass 1: blocks in the gate
        assertTrue(gate.started.await(2, TimeUnit.SECONDS), "first pass should reach the gate");
        // Two triggers arrive while pass 1 is blocked; only the latest survives coalescing.
        graph.execute(source, () -> source.out.setValue("b"));
        graph.execute(source, () -> source.out.setValue("c"));

        release.countDown();
        graph.awaitIdle();

        assertEquals(List.of("a", "c"), recorder.received, "the middle trigger \"b\" should be coalesced away");
    }

    @Test
    void restartPolicyCancelsTheInFlightCascadeAndRunsAFreshPass() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        CountDownLatch release = new CountDownLatch(1);
        GateNode gate = new GateNode(release);
        CountingSinkNode sink = new CountingSinkNode();
        graph.addNode(trigger);
        graph.addNode(gate);
        graph.addNode(sink);
        graph.registerFlowEdge(flowEdge(trigger, gate));
        graph.registerFlowEdge(flowEdge(gate, sink));

        trigger.setExecutionPolicy(ExecutionPolicy.RESTART);

        trigger.execute(); // pass 1: blocks in the gate, before ever reaching the sink
        assertTrue(gate.started.await(2, TimeUnit.SECONDS), "first pass should reach the gate");
        trigger.execute(); // restart: cancel pass 1's remaining cascade, queue a fresh pass

        release.countDown();
        graph.awaitIdle();

        assertEquals(2, gate.processCount.get(), "the gate runs once per pass (the cancelled one plus the restart)");
        assertEquals(1, sink.processCount.get(),
                "pass 1's cascade to the sink is cancelled after the gate; only the restarted pass reaches it");
    }

    // --- Per-node (mid-cascade) execution policy ------------------------------------------------
    //
    // Unlike the entry-node tests above (whose policy decision runs synchronously on the calling
    // thread, so a re-trigger's outcome is settled the instant execute() returns), a mid-cascade
    // node's gate is consulted on a run's firing thread. These tests therefore use the same
    // bounded-wait idiom as the concurrent-runs tests (parallelPolicy..., concurrencyLimit...): the
    // entry trigger is PARALLEL so two runs overlap, and one holds the gated node's process() while
    // the other's re-entrant arrival is observed.

    @Test
    void dropPolicyOnMidCascadeNodeDropsAReentrantBranchWhileTheFastSiblingKeepsRunning() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        trigger.setExecutionPolicy(ExecutionPolicy.PARALLEL); // let both triggers run as concurrent runs

        CountDownLatch releaseSlow = new CountDownLatch(1);
        GateNode slow = new GateNode(releaseSlow);
        slow.setExecutionPolicy(ExecutionPolicy.DROP);
        CountingSinkNode afterSlow = new CountingSinkNode();     // downstream of the slow branch
        GateNode fast = new GateNode(new CountDownLatch(0));     // sibling branch, never blocks

        graph.addNode(trigger);
        graph.addNode(slow);
        graph.addNode(afterSlow);
        graph.addNode(fast);
        graph.registerFlowEdge(flowEdge(trigger, slow));
        graph.registerFlowEdge(flowEdge(slow, afterSlow));
        graph.registerFlowEdge(flowEdge(trigger, fast));

        trigger.execute(); // run 1: enters the slow node's process() and holds its gate
        assertTrue(slow.started.await(2, TimeUnit.SECONDS), "run 1 should reach and hold the slow node");
        trigger.execute(); // run 2: concurrent (PARALLEL entry)
        awaitAtLeast(fast.processCount, 2); // run 2 has fanned out (its fast sibling fired)

        // Run 2's arrival at the DROP node finds it busy with run 1 and is dropped: the node never
        // runs a second time, and run 2's branch is abandoned before the node downstream of it.
        Thread.sleep(300);
        assertEquals(1, slow.processCount.get(), "run 2's re-entrant arrival at the DROP node is dropped while run 1 holds it");

        releaseSlow.countDown();
        graph.awaitIdle();
        assertEquals(1, slow.processCount.get(), "the slow branch ran exactly once");
        assertEquals(1, afterSlow.processCount.get(), "run 2 never got past the dropped slow node");
        assertEquals(2, fast.processCount.get(), "the fast sibling branch ran for both triggers, unaffected by the slow branch's gate");
    }

    @Test
    void queuePolicyOnMidCascadeNodeCoalescesReentrantArrivalsBehindTheInFlightOne() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        trigger.setExecutionPolicy(ExecutionPolicy.PARALLEL);

        CountDownLatch releaseSlow = new CountDownLatch(1);
        GateNode slow = new GateNode(releaseSlow);
        slow.setExecutionPolicy(ExecutionPolicy.QUEUE);
        GateNode fast = new GateNode(new CountDownLatch(0));

        graph.addNode(trigger);
        graph.addNode(slow);
        graph.addNode(fast);
        graph.registerFlowEdge(flowEdge(trigger, slow));
        graph.registerFlowEdge(flowEdge(trigger, fast));

        trigger.execute(); // run 1: holds the slow node's gate
        assertTrue(slow.started.await(2, TimeUnit.SECONDS), "run 1 should reach and hold the slow node");
        trigger.execute(); // run 2: queues behind run 1 at the slow node
        trigger.execute(); // run 3: coalesces run 2 away (latest wins)
        awaitAtLeast(fast.processCount, 3); // all three runs have fanned out

        // The queued arrivals wait for the gate rather than running alongside run 1.
        Thread.sleep(300);
        assertEquals(1, slow.processCount.get(), "queued arrivals wait behind run 1, not run concurrently");

        releaseSlow.countDown();
        graph.awaitIdle();
        assertEquals(2, slow.processCount.get(),
                "runs 2 and 3 coalesce into a single follow-up behind run 1 (unlike DROP, which would leave it at 1)");
    }

    @Test
    void parallelPolicyOnMidCascadeNodeRunsReentrantArrivalsConcurrently() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        trigger.setExecutionPolicy(ExecutionPolicy.PARALLEL);

        // Blocks until two runs are inside its process() at once - only reachable if the mid-cascade
        // node is PARALLEL (the default QUEUE would hold the second out, and this would time out).
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ConcurrentNode shared = new ConcurrentNode(bothEntered, release);
        shared.setExecutionPolicy(ExecutionPolicy.PARALLEL);

        graph.addNode(trigger);
        graph.addNode(shared);
        graph.registerFlowEdge(flowEdge(trigger, shared));

        trigger.execute();
        trigger.execute();
        assertTrue(bothEntered.await(2, TimeUnit.SECONDS), "two PARALLEL runs enter the mid-cascade node concurrently");

        release.countDown();
        graph.awaitIdle();
        assertEquals(2, shared.processCount.get(), "both re-entrant arrivals ran");
    }

    @Test
    void restartPolicyOnMidCascadeNodeInterruptsTheInFlightProcess() throws InterruptedException {
        NodeGraph graph = new NodeGraph();
        TriggerNode trigger = new TriggerNode();
        trigger.setExecutionPolicy(ExecutionPolicy.PARALLEL);

        CountDownLatch finish = new CountDownLatch(1);
        InterruptibleGateNode slow = new InterruptibleGateNode(finish);
        slow.setExecutionPolicy(ExecutionPolicy.RESTART);

        graph.addNode(trigger);
        graph.addNode(slow);
        graph.registerFlowEdge(flowEdge(trigger, slow));

        trigger.execute(); // run 1: enters the node's process() and blocks
        assertTrue(slow.started.await(2, TimeUnit.SECONDS), "run 1 should reach and hold the node");
        trigger.execute(); // run 2: RESTART interrupts run 1's process, then takes the gate
        awaitAtLeast(slow.processCount, 2); // run 2 entered after run 1 was interrupted

        finish.countDown(); // let run 2 finish promptly
        graph.awaitIdle();
        assertEquals(2, slow.processCount.get(), "the interrupted first run and the restart both ran the node");
        assertTrue(slow.interrupts.get() >= 1, "RESTART interrupted the in-flight process()");
    }

    /** Like {@link GateNode} but exits its block when interrupted (recording it) - for the RESTART mid-cascade test. */
    private static final class InterruptibleGateNode extends BaseNode {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish;
        final AtomicInteger processCount = new AtomicInteger();
        final AtomicInteger interrupts = new AtomicInteger();

        InterruptibleGateNode(CountDownLatch finish) {
            this.finish = finish;
        }

        @Override
        public void process() {
            processCount.incrementAndGet();
            started.countDown();
            try {
                finish.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupts.incrementAndGet();
                Thread.currentThread().interrupt();
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
    }

    /** Spins until {@code counter} reaches {@code target} (or fails after 2s) - for observing an async mid-cascade run. */
    private static void awaitAtLeast(AtomicInteger counter, int target) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (counter.get() < target) {
            assertTrue(System.nanoTime() < deadline,
                    "counter did not reach " + target + " within 2s (was " + counter.get() + ")");
            Thread.sleep(5);
        }
    }

    /** Blocks in process() until released, counting each entry - holds a pass in flight at a known point. */
    private static final class GateNode extends BaseNode {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release;
        final AtomicInteger processCount = new AtomicInteger();

        GateNode(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void process() {
            processCount.incrementAndGet();
            started.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS), "test never released the gate");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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

    /** Counts how many times it was reached via a flow cascade. */
    private static final class CountingSinkNode extends BaseNode {
        final AtomicInteger processCount = new AtomicInteger();

        @Override
        public void process() {
            processCount.incrementAndGet();
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
    }

    /** A flow edge between two nodes' first (single) flow ports - the common single-flow-port shape. */
    private static FlowEdge flowEdge(BaseNode source, BaseNode target) {
        return new FlowEdge(source, source.getFlowOutputs().get(0), target, target.getFlowInputs().get(0));
    }

    @SuppressWarnings("unchecked")
    private static NodeVariable<Float> output(BaseNode node) {
        return node.getOutputs().get(0);
    }

    @SuppressWarnings("unchecked")
    private static NodeVariable<Float> input(BaseNode node, String name) {
        for (NodeVariable variable : node.getInputs()) {
            if (variable.name.equals(name)) {
                return variable;
            }
        }
        throw new IllegalArgumentException("No input named " + name + " on " + node.getName());
    }
}
