package io.github.jaymcole.housegraph.camera;

import io.github.jaymcole.housegraph.camera.ReolinkClient.DetectionState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReolinkClientTest {

    @Test
    void nothingDetectedIsNone() {
        DetectionState state = new DetectionState(false, false, false, false);
        assertEquals("none", state.topStatus());
        assertFalse(state.anyDetected());
    }

    @Test
    void topStatusPrefersMostSpecificDetection() {
        assertEquals("human", new DetectionState(true, true, true, true).topStatus());
        assertEquals("vehicle", new DetectionState(false, true, true, true).topStatus());
        assertEquals("animal", new DetectionState(false, false, true, true).topStatus());
        assertEquals("motion", new DetectionState(false, false, false, true).topStatus());
    }

    @Test
    void anyDetectedTrueWhenAnyFlagSet() {
        assertTrue(new DetectionState(false, false, false, true).anyDetected());
        assertTrue(new DetectionState(true, false, false, false).anyDetected());
    }
}
