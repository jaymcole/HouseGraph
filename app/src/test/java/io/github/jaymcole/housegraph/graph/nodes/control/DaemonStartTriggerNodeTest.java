package io.github.jaymcole.housegraph.graph.nodes.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the daemon-only gate on {@code sdk.RuntimeMode#isDaemon()} rather than driving a real
 * {@code NodeGraph}: {@code execute()} requires the node to be attached to one first and throws
 * {@link IllegalStateException} otherwise, which doubles as proof the node actually tried to
 * fire when (and only when) {@code housegraph.daemon} is set.
 */
class DaemonStartTriggerNodeTest {

    @AfterEach
    void clearDaemonProperty() {
        System.clearProperty("housegraph.daemon");
    }

    @Test
    void firesWhenOpenedByTheDaemon() {
        System.setProperty("housegraph.daemon", "true");

        assertThrows(IllegalStateException.class, () -> new DaemonStartTriggerNode().autoStartIfWasRunning(),
                "daemon mode should attempt execute(), which requires a NodeGraph this bare node doesn't have");
    }

    @Test
    void staysSilentWhenOpenedByHand() {
        System.clearProperty("housegraph.daemon");

        assertDoesNotThrow(() -> new DaemonStartTriggerNode().autoStartIfWasRunning(),
                "outside daemon mode, autoStartIfWasRunning() must be a no-op");
    }
}
