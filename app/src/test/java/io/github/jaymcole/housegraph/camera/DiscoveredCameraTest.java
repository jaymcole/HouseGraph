package io.github.jaymcole.housegraph.camera;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredCameraTest {

    private static final List<String> REOLINK_SCOPES = List.of(
            "onvif://www.onvif.org/name/Reolink",
            "onvif://www.onvif.org/hardware/RLC-810A",
            "onvif://www.onvif.org/manufacturer/Reolink");

    @Test
    void derivesNameHardwareManufacturerAndReolinkFromScopes() {
        DiscoveredCamera camera = new DiscoveredCamera("192.168.1.50", "AA:BB:CC:DD:EE:FF", REOLINK_SCOPES);
        assertEquals("Reolink", camera.name());
        assertEquals("RLC-810A", camera.hardware());
        assertEquals("Reolink", camera.manufacturer());
        assertTrue(camera.reolink());
    }

    @Test
    void nonReolinkScopesAreNotFlaggedReolink() {
        DiscoveredCamera camera = new DiscoveredCamera("192.168.1.50", "AA:BB:CC:DD:EE:FF",
                List.of("onvif://www.onvif.org/name/SomeCam"));
        assertFalse(camera.reolink());
    }

    @Test
    void unenrichedCameraHasNoCustomNameOrModel() {
        DiscoveredCamera camera = new DiscoveredCamera("192.168.1.50", "AA:BB:CC:DD:EE:FF", REOLINK_SCOPES);
        assertNull(camera.customName());
        assertNull(camera.model());
        // Falls back through the scope hardware for a display label.
        assertEquals("RLC-810A", camera.displayName());
    }

    @Test
    void enrichmentSuppliesCustomNameCleanModelAndAuthoritativeScopes() {
        DiscoveredCamera camera = new DiscoveredCamera("192.168.1.50", "AA:BB:CC:DD:EE:FF", REOLINK_SCOPES)
                .enriched("Front Door", "Reolink RLC-810A", List.of("odm:name:Front%20Door"));

        assertEquals("Front Door", camera.customName());
        assertEquals("Reolink RLC-810A", camera.model());
        assertEquals("Front Door", camera.displayName(), "the app-set name wins the display label");
        assertEquals(List.of("odm:name:Front%20Door"), camera.scopes(), "authoritative scopes replace discovery's");
    }

    @Test
    void enrichmentWithNullsLeavesExistingValues() {
        DiscoveredCamera camera = new DiscoveredCamera("192.168.1.50", "AA:BB:CC:DD:EE:FF", REOLINK_SCOPES)
                .enriched(null, null, null);
        assertNull(camera.customName());
        assertNull(camera.model());
        assertEquals(REOLINK_SCOPES, camera.scopes(), "empty enrichment keeps the discovery scopes");
    }

    @Test
    void withMacResolvesTheStableKeyAfterDiscovery() {
        DiscoveredCamera camera = new DiscoveredCamera("192.168.1.50", null, REOLINK_SCOPES).withMac("AA:BB:CC:DD:EE:FF");
        assertEquals("AA:BB:CC:DD:EE:FF", camera.mac());
    }

    @Test
    void labelFallsBackToIpWhenNothingElseIsKnown() {
        DiscoveredCamera camera = new DiscoveredCamera("192.168.1.99", null, List.of());
        assertEquals("192.168.1.99", camera.label());
    }
}
