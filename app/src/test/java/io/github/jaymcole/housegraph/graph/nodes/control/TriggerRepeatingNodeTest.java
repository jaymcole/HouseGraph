package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.GraphExecutionListener;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the repeating trigger's whole running lifecycle with <b>no view and no JavaFX toolkit</b>:
 * nothing here calls {@code createNodeContent()}, so every control field on the node stays null
 * throughout. That is the point — the clock is a {@code NodeTimer}, the running flag is a field of
 * the node, and every write to a control goes through {@code BaseNode.present(Runnable)}, which
 * discards the update when nothing is drawing the node.
 * <p>
 * The waits below are bounded polls on an actual condition (a firing counted, a flag cleared), not
 * fixed sleeps: a slow machine takes longer to satisfy them but never reports a different result.
 */
class TriggerRepeatingNodeTest {

    /** Long enough that a loaded CI machine still gets there; the tests return as soon as it holds. */
    private static final long AWAIT_MILLIS = 10_000;

    private NodeGraph graph;

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.dispose();
        }
    }

    @Test
    void exposesNamedStartAndStopFlowInputs() {
        List<FlowPort> flowInputs = new TriggerRepeatingNode().getFlowInputs();

        assertEquals(2, flowInputs.size(), "Start and Stop, and nothing else");
        assertEquals("Start", flowInputs.get(0).name);
        assertEquals("Stop", flowInputs.get(1).name);
    }

    @Test
    void remainsAnExecutionEntryPointDespiteNowHavingFlowInputs() {
        assertTrue(new TriggerRepeatingNode().isExecutionEntryPoint(),
                "the buttons and the countdown still self-trigger it directly, regardless of Start/Stop wiring");
    }

    @Test
    void aStoppedTriggerWritesNoRunningFlag() {
        assertFalse(new TriggerRepeatingNode().saveState().containsKey("running"),
                "a trigger whose timer isn't running must not persist a running flag");
    }

    @Test
    void aRunningFlagInSavedStateSchedulesAutoStart() {
        TriggerRepeatingNode trigger = new TriggerRepeatingNode();
        assertFalse(trigger.wasRunning(), "a fresh node has no pending auto-start");

        trigger.loadState(Map.of("running", "true"));

        assertTrue(trigger.wasRunning(), "a graph saved while the timer ran reloads with auto-start pending");
    }

    @Test
    void autoStartResumesTheTimerWithNoViewAndActuallyFires() throws InterruptedException {
        TriggerRepeatingNode trigger = placeTrigger();
        trigger.loadState(Map.of("running", "true"));
        CountDownLatch fired = firingLatch(trigger, 1);

        assertDoesNotThrow(trigger::autoStartIfWasRunning,
                "resuming a saved-running node must not need controls that only a view builds");

        assertTrue(trigger.isRunning(), "the resumed node owns its running state");
        assertTrue(fired.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "the resumed clock must actually drive a run with no toolkit behind it");
    }

    @Test
    void aRunningTimerPersistsItsRunningFlagAndAStoppedOneDropsIt() {
        TriggerRepeatingNode trigger = startedTrigger();

        assertEquals("true", trigger.saveState().get("running"),
                "saving a live graph must record that the timer was running, without reading a control");

        stopVia(trigger, "Stop");

        assertFalse(trigger.saveState().containsKey("running"),
                "once stopped it is saved as stopped again");
    }

    @Test
    void startAndStopThroughTheFlowPortsLeaveNoRunningClock() throws InterruptedException {
        TriggerRepeatingNode trigger = placeTrigger();
        AtomicInteger firings = countFirings(trigger);

        triggerVia(trigger, "Start");
        assertTrue(trigger.isRunning(), "the Start flow port arms the timer with no view present");
        // The Start cascade is itself one firing of this node; wait for one the clock caused.
        int afterArming = firings.get();
        awaitTrue(() -> firings.get() > afterArming, "the armed clock fires on its own");

        stopVia(trigger, "Stop");

        assertFalse(trigger.isRunning(), "the Stop flow port disarms it");
        assertNoFurtherFirings(firings);
    }

    @Test
    void removalFromTheGraphStopsTheClock() throws InterruptedException {
        TriggerRepeatingNode trigger = startedTrigger();
        AtomicInteger firings = countFirings(trigger);
        awaitTrue(() -> firings.get() > 0, "the clock is running before removal");

        graph.removeNode(trigger);

        assertFalse(trigger.isRunning(), "onRemoved() clears the node's own running state");
        assertNoFurtherFirings(firings);
    }

    // --- helpers ------------------------------------------------------------------

    /** A repeating trigger on a live graph, wired to the shortest interval its editor allows. */
    private TriggerRepeatingNode placeTrigger() {
        graph = new NodeGraph();
        TriggerRepeatingNode trigger = new TriggerRepeatingNode();
        graph.addNode(trigger);
        interval(trigger).setValue(1);
        return trigger;
    }

    private TriggerRepeatingNode startedTrigger() {
        TriggerRepeatingNode trigger = placeTrigger();
        triggerVia(trigger, "Start");
        assertTrue(trigger.isRunning(), "precondition: the timer started");
        return trigger;
    }

    /** Fires one of the trigger's own named flow-in ports, the way an upstream cascade would. */
    private void triggerVia(TriggerRepeatingNode trigger, String portName) {
        TriggerNode source = new TriggerNode();
        graph.addNode(source);
        graph.registerFlowEdge(new FlowEdge(
                source, source.getFlowOutputs().get(0), trigger, flowInput(trigger, portName)));
        source.execute();
        awaitTrue(() -> trigger.isRunning() == portName.equals("Start"),
                "the " + portName + " port took effect");
    }

    private void stopVia(TriggerRepeatingNode trigger, String portName) {
        triggerVia(trigger, portName);
    }

    private CountDownLatch firingLatch(TriggerRepeatingNode trigger, int firings) {
        CountDownLatch latch = new CountDownLatch(firings);
        graph.addExecutionListener(new GraphExecutionListener() {
            @Override
            public void onNodeExecuted(BaseNode node) {
                if (node == trigger) {
                    latch.countDown();
                }
            }
        });
        return latch;
    }

    private AtomicInteger countFirings(TriggerRepeatingNode trigger) {
        AtomicInteger count = new AtomicInteger();
        graph.addExecutionListener(new GraphExecutionListener() {
            @Override
            public void onNodeExecuted(BaseNode node) {
                if (node == trigger) {
                    count.incrementAndGet();
                }
            }
        });
        return count;
    }

    /**
     * A stopped clock stays stopped. The short settle first lets the stopping run's own
     * {@code onNodeExecuted} land before the count is sampled — the engine dispatches
     * {@code onExecuted()} (which is what stops the timer) immediately before it. The window then
     * spans more than two tick periods, so a clock still running would have fired inside it.
     */
    private void assertNoFurtherFirings(AtomicInteger firings) throws InterruptedException {
        Thread.sleep(200);
        int afterStop = firings.get();
        Thread.sleep(2_500);
        assertEquals(afterStop, firings.get(), "a stopped timer must not fire again");
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
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

    @SuppressWarnings("unchecked")
    private static NodeVariable<Integer> interval(TriggerRepeatingNode trigger) {
        return trigger.getInputs().get(0);
    }

    private static FlowPort flowInput(TriggerRepeatingNode trigger, String name) {
        return trigger.getFlowInputs().stream()
                .filter(port -> port.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No flow input named " + name));
    }
}
