package io.github.jaymcole.housegraph.graph;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
