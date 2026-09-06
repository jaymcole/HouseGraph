package io.github.jaymcole.housegraph.graph.nodes.resource;

import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.resource.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the echo resource's whole running lifecycle with <b>no view and no JavaFX toolkit</b>:
 * nothing here calls {@code createNodeContent()}, so its name field, buttons and status label stay
 * null throughout. Starting registers it and drives its once-a-second publish from a
 * {@code NodeTimer}; stopping unregisters it and leaves no clock behind. Every control update in
 * the node goes through {@code BaseNode.present(Runnable)}, which discards it when nothing is
 * drawing the node.
 * <p>
 * Each test publishes under a name of its own, so a stray tick from another test cannot reach it.
 */
class EchoResourceNodeTest {

    private static final long AWAIT_MILLIS = 10_000;

    private NodeGraph graph;
    private Subscription subscription;
    private String name;

    @AfterEach
    void tearDown() {
        if (subscription != null) {
            subscription.cancel();
        }
        if (graph != null) {
            graph.dispose();
        }
        if (name != null) {
            ResourceRegistry.shared().unregister(name);
        }
    }

    @Test
    void aStoppedResourceWritesItsNameButNoRunningFlag() {
        Map<String, String> state = new EchoResourceNode().saveState();
        assertEquals("echo", state.get("name"), "the resource name is always persisted");
        assertFalse(state.containsKey("running"), "a stopped resource must not persist a running flag");
    }

    @Test
    void aRunningFlagInSavedStateSchedulesAutoStart() {
        EchoResourceNode echo = new EchoResourceNode();
        assertFalse(echo.wasRunning(), "a fresh node has no pending auto-start");

        echo.loadState(Map.of("name", "echo", "running", "true"));

        assertTrue(echo.wasRunning(), "a graph saved while running reloads with auto-start pending");
    }

    @Test
    void autoStartRegistersAndPublishesWithNoViewEverBuilt() throws InterruptedException {
        EchoResourceNode echo = place("autostart");
        CountDownLatch published = new CountDownLatch(1);
        subscription = ResourceRegistry.shared().subscribe(name, payload -> published.countDown());

        assertDoesNotThrow(echo::autoStartIfWasRunning,
                "resuming a saved-running resource must not need controls that only a view builds");

        assertTrue(echo.isRunning(), "the resumed node owns its running state");
        assertSame(echo, ResourceRegistry.shared().find(name, EchoResourceNode.class).orElse(null),
                "resuming registers it under its saved name");
        assertTrue(published.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "the resumed clock must actually publish with no toolkit behind it");
        assertEquals("true", echo.saveState().get("running"),
                "a live resource saves as running, read from the node rather than a control");
    }

    @Test
    void removalUnregistersItAndStopsThePublishing() throws InterruptedException {
        EchoResourceNode echo = started("removal");
        AtomicInteger published = new AtomicInteger();
        subscription = ResourceRegistry.shared().subscribe(name, payload -> published.incrementAndGet());
        awaitTrue(() -> published.get() > 0, "the running resource publishes");

        graph.removeNode(echo);

        assertFalse(echo.isRunning(), "onRemoved() clears the node's own running state");
        assertTrue(ResourceRegistry.shared().find(name, EchoResourceNode.class).isEmpty(),
                "teardown unregisters the name, so nothing can still find a dead resource");
        assertFalse(echo.saveState().containsKey("running"), "a stopped resource saves as stopped");

        int afterStop = published.get();
        Thread.sleep(2_500);
        assertEquals(afterStop, published.get(), "a stopped resource must not keep publishing");
    }

    @Test
    void aSecondStartOnALiveResourceIsIgnored() {
        EchoResourceNode echo = started("idempotent");

        echo.autoStartIfWasRunning();

        assertTrue(echo.isRunning(), "still the same live resource, not a second clock");
        assertSame(echo, ResourceRegistry.shared().find(name, EchoResourceNode.class).orElse(null));
    }

    // --- helpers ------------------------------------------------------------------

    /** An echo resource on a live graph, loaded as if its graph had been saved while running. */
    private EchoResourceNode place(String suffix) {
        graph = new NodeGraph();
        name = "echo-test-" + suffix;
        EchoResourceNode echo = new EchoResourceNode();
        echo.loadState(Map.of("name", name, "running", "true"));
        graph.addNode(echo);
        return echo;
    }

    private EchoResourceNode started(String suffix) {
        EchoResourceNode echo = place(suffix);
        echo.autoStartIfWasRunning();
        assertTrue(echo.isRunning(), "precondition: the resource started");
        assertEquals(name, echo.resourceName(), "precondition: it publishes under the loaded name");
        return echo;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MILLIS);
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting until " + what, e);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting until " + what);
    }
}
