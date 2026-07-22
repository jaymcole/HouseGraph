package io.github.jaymcole.housegraph.graph.nodes.control;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the repeating trigger persists whether its timer was running so it can auto-start
 * on load (see {@code AutoStartable}). The timer itself is a JavaFX {@code Timeline}, so this
 * stays on the headless persistence contract — the running flag round-tripping through
 * {@code saveState}/{@code loadState} — rather than driving the UI.
 */
class TriggerRepeatingNodeTest {

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
