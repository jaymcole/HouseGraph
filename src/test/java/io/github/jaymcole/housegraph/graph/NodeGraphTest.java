package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.graph.nodes.control.IfNode;
import io.github.jaymcole.housegraph.graph.nodes.control.TriggerNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        BaseNode fastBranch = new AddNode();
        CountDownLatch slowBranchStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowBranch = new CountDownLatch(1);
        BlockingNode slowBranch = new BlockingNode(slowBranchStarted, releaseSlowBranch);
        graph.addNode(trigger);
        graph.addNode(fastBranch);
        graph.addNode(slowBranch);
        graph.registerFlowEdge(flowEdge(trigger, fastBranch));
        graph.registerFlowEdge(flowEdge(trigger, slowBranch));

        trigger.execute();

        // Proves the two branches actually run side by side rather than one after the
        // other: the slow branch has reached process() and is deliberately still
        // blocked there, yet the fast, independent sibling branch has already finished
        // - which could never be observed reliably if a slow branch held up its
        // siblings the way a single fully-sequential traversal would.
        assertTrue(slowBranchStarted.await(2, TimeUnit.SECONDS), "slow branch never started");
        assertTrue(fastBranch.getStatus().isComplete(), "fast branch should not wait behind its slow sibling");

        releaseSlowBranch.countDown();
        graph.awaitIdle();
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

        NodeVariable<Integer> notAFloat = new NodeVariable<>("bogus", Integer.class);
        Edge edge = new Edge(constant, notAFloat, add, input(add, "V1"));

        assertThrows(IllegalArgumentException.class, () -> graph.registerEdge(edge));
    }

    @Test
    void runningBeforeBeingAddedToAGraphFailsClearly() {
        AddNode add = new AddNode();
        assertThrows(IllegalStateException.class, add::beginProcessing);
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
