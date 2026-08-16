package io.github.jaymcole.housegraph.graph;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The value-accessor and cancellation surface of {@link ProcessContext}, exercised without the engine. */
class ProcessContextTest {

    @Test
    void getReturnsFallbackOnlyWhenTheValueIsNull() {
        NodeVariable<Float> v = new NodeVariable<>("V", Float.class);
        ProcessContext ctx = ProcessContext.uncancelled();

        assertEquals(0f, ctx.get(v, 0f), "a null input reads as the fallback");

        v.setValue(3.5f);
        assertEquals(3.5f, ctx.get(v, 0f), "a present value is returned, not the fallback");
        assertEquals(3.5f, ctx.get(v), "the no-fallback read returns the value");
    }

    @Test
    void getWithoutFallbackReturnsNullForAnUnsetVariable() {
        NodeVariable<String> v = new NodeVariable<>("V", String.class);
        assertNull(ProcessContext.uncancelled().get(v), "an unset variable reads as null");
    }

    @Test
    void setWritesThroughToTheVariable() {
        NodeVariable<String> v = new NodeVariable<>("V", String.class);
        ProcessContext ctx = ProcessContext.uncancelled();

        ctx.set(v, "hello");
        assertEquals("hello", v.getValue(), "set is visible through the variable");
        assertEquals("hello", ctx.get(v), "and through the context");
    }

    @Test
    void anUncancelledContextNeverReportsCancellation() {
        ProcessContext ctx = ProcessContext.uncancelled();
        assertFalse(ctx.isCancelled(), "uncancelled() is never cancelled");
        assertDoesNotThrow(ctx::checkCancelled, "checkCancelled is a no-op when not cancelled");
    }

    @Test
    void aContextWithNoFlowArrivalReportsNothingRatherThanThrowing() {
        FlowPort port = new FlowPort("Start", FlowPort.Direction.IN);
        ProcessContext ctx = ProcessContext.uncancelled();

        assertEquals(Set.of(), ctx.triggeredVia(), "a pull-model invocation arrived through no port");
        assertFalse(ctx.wasTriggeredVia(port), "and reports false for any port, rather than throwing");
    }

    @Test
    void triggeredViaNamesOnlyThePortsThatArrived() {
        FlowPort start = new FlowPort("Start", FlowPort.Direction.IN);
        FlowPort stop = new FlowPort("Stop", FlowPort.Direction.IN);
        // The two-argument form is what the engine builds per firing, from the run's recorded arrivals.
        ProcessContext ctx = new ProcessContext(() -> false, Set.of(start));

        assertEquals(Set.of(start), ctx.triggeredVia());
        assertTrue(ctx.wasTriggeredVia(start), "the port control arrived through");
        assertFalse(ctx.wasTriggeredVia(stop), "a declared but unfired port");
    }

    @Test
    void checkCancelledThrowsOnceCancelled() {
        boolean[] cancelled = {false};
        // A context whose signal we can flip - mirrors how the engine wires a run's token in.
        ProcessContext ctx = new ProcessContext(() -> cancelled[0]);

        assertFalse(ctx.isCancelled());
        cancelled[0] = true;
        assertThrows(CancellationException.class, ctx::checkCancelled,
                "checkCancelled throws once the signal reports cancellation");
    }
}
