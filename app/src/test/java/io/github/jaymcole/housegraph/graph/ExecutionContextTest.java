package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Stage-A spike for the concurrent-runs design (docs/design/per-node-execution-policy.md).
 * De-risks the linchpin: routing {@link NodeVariable} value access through a per-run
 * {@link ExecutionContext} gives concurrent runs isolated computed values while leaving node
 * {@code process()} code untouched (here, {@link AddNode} is used exactly as-is).
 */
class ExecutionContextTest {

    @Test
    void concurrentRunsComputeIsolatedValuesOnASharedNode() throws InterruptedException {
        // One shared node instance, exercised by two runs at once - the situation the current
        // engine forbids by serializing, and the whole point of the refactor to allow.
        AddNode shared = new AddNode();
        @SuppressWarnings("unchecked")
        NodeVariable<Float> v1 = (NodeVariable<Float>) shared.getInputs().get(0);
        @SuppressWarnings("unchecked")
        NodeVariable<Float> v2 = (NodeVariable<Float>) shared.getInputs().get(1);
        @SuppressWarnings("unchecked")
        NodeVariable<Float> sum = (NodeVariable<Float>) shared.getOutputs().get(0);

        // Both runs set their inputs before either computes, so if values lived on the shared
        // node one run's inputs would clobber the other's - the barrier makes that failure
        // deterministic rather than a lucky pass.
        CyclicBarrier bothInputsSet = new CyclicBarrier(2);
        AtomicReference<Float> runA = new AtomicReference<>();
        AtomicReference<Float> runB = new AtomicReference<>();

        Thread a = new Thread(() -> new ExecutionContext().run(() -> {
            v1.setValue(2f);
            v2.setValue(3f);
            await(bothInputsSet);
            shared.process(ProcessContext.uncancelled());
            runA.set(sum.getValue());
        }));
        Thread b = new Thread(() -> new ExecutionContext().run(() -> {
            v1.setValue(10f);
            v2.setValue(20f);
            await(bothInputsSet);
            shared.process(ProcessContext.uncancelled());
            runB.set(sum.getValue());
        }));

        a.start();
        b.start();
        a.join(2000);
        b.join(2000);

        assertEquals(5f, runA.get(), "run A must see its own inputs (2 + 3)");
        assertEquals(30f, runB.get(), "run B must see its own inputs (10 + 20) despite sharing the node");
        assertNull(sum.getValue(), "no computed value leaks onto the shared node outside a run");
    }

    @Test
    void authoredValueIsAReadOnlySharedFallbackInsideARun() {
        NodeVariable<String> variable = new NodeVariable<>("V", String.class, true);
        variable.setValue("authored"); // outside any run: stored on the variable

        new ExecutionContext().run(() -> {
            assertEquals("authored", variable.getValue(), "an unset variable falls back to its authored value in a run");
            variable.setValue("computed"); // writes the run overlay, not the shared store
            assertEquals("computed", variable.getValue(), "the run sees its own override");
        });

        assertEquals("authored", variable.getValue(), "the authored value is untouched by the run's override");
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException("barrier interrupted", e);
        }
    }
}
