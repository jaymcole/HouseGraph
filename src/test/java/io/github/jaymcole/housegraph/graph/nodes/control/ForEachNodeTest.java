package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link ForEachNode}: the body fires once per item (in order), the per-item outputs
 * carry the right values, {@code Completed} fires exactly once after the last item, and the whole
 * thing composes when nested — which together verify the seeded-sub-run loop mechanism in
 * {@code NodeGraph.runFlowBranchToCompletion}.
 */
class ForEachNodeTest {

    @Test
    void firesBodyOncePerItemInOrderWithCorrectValues() {
        NodeGraph graph = new NodeGraph();
        ForEachNode loop = new ForEachNode();
        RecordingSinkNode body = new RecordingSinkNode();
        CountingSinkNode completed = new CountingSinkNode();
        graph.addNode(loop);
        graph.addNode(body);
        graph.addNode(completed);

        listInput(loop).setValue(List.of(10, 20, 30));
        wireBody(graph, loop, body);
        // Pull the per-item outputs into the body so it can record what it saw each iteration.
        graph.registerEdge(new Edge(loop, loop.getOutputs().get(0), body, body.item));
        graph.registerEdge(new Edge(loop, loop.getOutputs().get(1), body, body.idx));
        wireCompleted(graph, loop, completed);

        loop.execute();
        graph.awaitIdle();

        assertEquals(3, body.processCount.get(), "body should run once per item");
        assertEquals(List.of(10, 20, 30), body.items, "body sees each item in list order");
        assertEquals(List.of(0, 1, 2), body.indices, "index increments per item");
        assertEquals(1, completed.processCount.get(), "Completed fires exactly once, after the loop");
    }

    @Test
    void emptyListRunsNoBodyButStillCompletes() {
        NodeGraph graph = new NodeGraph();
        ForEachNode loop = new ForEachNode();
        CountingSinkNode body = new CountingSinkNode();
        CountingSinkNode completed = new CountingSinkNode();
        graph.addNode(loop);
        graph.addNode(body);
        graph.addNode(completed);

        listInput(loop).setValue(Collections.emptyList());
        wireBody(graph, loop, body);
        wireCompleted(graph, loop, completed);

        loop.execute();
        graph.awaitIdle();

        assertEquals(0, body.processCount.get(), "empty list runs the body zero times");
        assertEquals(1, completed.processCount.get(), "Completed still fires once for an empty list");
    }

    @Test
    void nullListRunsNoBodyButStillCompletes() {
        NodeGraph graph = new NodeGraph();
        ForEachNode loop = new ForEachNode();
        CountingSinkNode body = new CountingSinkNode();
        CountingSinkNode completed = new CountingSinkNode();
        graph.addNode(loop);
        graph.addNode(body);
        graph.addNode(completed);

        // No list wired and no authored value: the input is null at run time.
        wireBody(graph, loop, body);
        wireCompleted(graph, loop, completed);

        loop.execute();
        graph.awaitIdle();

        assertEquals(0, body.processCount.get(), "a null list runs the body zero times");
        assertEquals(1, completed.processCount.get(), "Completed still fires once for a null list");
    }

    @Test
    void nestedLoopsRunTheInnerBodyOncePerPair() {
        NodeGraph graph = new NodeGraph();
        ForEachNode outer = new ForEachNode();
        ForEachNode inner = new ForEachNode();
        CountingSinkNode innerBody = new CountingSinkNode();
        CountingSinkNode outerCompleted = new CountingSinkNode();
        graph.addNode(outer);
        graph.addNode(inner);
        graph.addNode(innerBody);
        graph.addNode(outerCompleted);

        listInput(outer).setValue(List.of(0, 1));          // 2 outer items
        listInput(inner).setValue(List.of("a", "b", "c")); // 3 inner items each
        wireBody(graph, outer, inner);                     // outer body drives the inner loop
        wireBody(graph, inner, innerBody);
        wireCompleted(graph, outer, outerCompleted);

        outer.execute();
        graph.awaitIdle();

        assertEquals(6, innerBody.processCount.get(), "inner body runs outer*inner times");
        assertEquals(1, outerCompleted.processCount.get(), "outer Completed fires once, after all iterations");
    }

    // --- wiring helpers -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static NodeVariable<Object> listInput(ForEachNode loop) {
        return (NodeVariable<Object>) (NodeVariable<?>) loop.getInputs().get(0);
    }

    /** Wires the loop's Body flow-out to {@code body}'s flow-in. */
    private static void wireBody(NodeGraph graph, ForEachNode loop, BaseNode body) {
        FlowPort bodyPort = loop.getFlowOutputs().get(0);
        graph.registerFlowEdge(new FlowEdge(loop, bodyPort, body, body.getFlowInputs().get(0)));
    }

    /** Wires the loop's Completed flow-out to {@code completed}'s flow-in. */
    private static void wireCompleted(NodeGraph graph, ForEachNode loop, BaseNode completed) {
        FlowPort completedPort = loop.getFlowOutputs().get(1);
        graph.registerFlowEdge(new FlowEdge(loop, completedPort, completed, completed.getFlowInputs().get(0)));
    }

    /** A flow sink that also pulls and records the loop's Current Item / Index each time it runs. */
    private static final class RecordingSinkNode extends BaseNode {
        final NodeVariable<Object> item = new NodeVariable<>("Item", Object.class);
        final NodeVariable<Integer> idx = new NodeVariable<>("Idx", Integer.class);
        final AtomicInteger processCount = new AtomicInteger();
        final List<Object> items = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> indices = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void process(ProcessContext ctx) {
            processCount.incrementAndGet();
            items.add(item.getValue());
            indices.add(idx.getValue());
        }

        @Override
        public void configureInputs() {
            addInput(item);
            addInput(idx);
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowInputs() {
            addFlowInput(new FlowPort("", FlowPort.Direction.IN));
        }
    }

    /** Counts how many times it was reached via a flow cascade. */
    private static final class CountingSinkNode extends BaseNode {
        final AtomicInteger processCount = new AtomicInteger();

        @Override
        public void process(ProcessContext ctx) {
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
}
