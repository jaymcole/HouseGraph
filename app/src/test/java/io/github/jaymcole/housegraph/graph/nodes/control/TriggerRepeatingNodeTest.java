package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.FlowPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the repeating trigger persists whether its timer was running so it can auto-start
 * on load (see {@code AutoStartable}), and the shape of its Start/Stop flow-in ports. The timer
 * itself is a JavaFX {@code Timeline} built by {@code createNodeContent()}, so this stays on the
 * headless contract — persistence and port wiring — rather than driving the UI; the Start/Stop
 * cascade-suppression behaviour ({@code activateNone()}) is exercised JavaFX-free against an
 * equivalently-shaped node in {@code NodeGraphTest}.
 */
class TriggerRepeatingNodeTest {

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
}
