package io.github.jaymcole.housegraph.graph.nodes.resource;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the echo resource persists whether it was running alongside its name, so it can
 * auto-start on load (see {@code AutoStartable}). Stays on the headless persistence contract —
 * the running flag round-tripping through {@code saveState}/{@code loadState} — since starting
 * drives a JavaFX {@code Timeline}.
 */
class EchoResourceNodeTest {

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
}
