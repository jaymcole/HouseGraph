package io.github.jaymcole.housegraph.camera;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraDiscoveryTest {

    @Test
    void parsesWindowsArpOutput() {
        String output = """
                Interface: 192.168.1.10 --- 0x5
                  Internet Address      Physical Address      Type
                  192.168.1.1           aa-bb-cc-dd-ee-ff     dynamic
                  192.168.1.50          bc-09-b9-e5-9c-3c     dynamic
                """;
        Map<String, String> table = CameraDiscovery.parseArpTable(output);
        assertEquals("AA:BB:CC:DD:EE:FF", table.get("192.168.1.1"));
        assertEquals("BC:09:B9:E5:9C:3C", table.get("192.168.1.50"));
    }

    @Test
    void parsesUnixArpOutput() {
        String output = "? (192.168.1.50) at bc:09:b9:e5:9c:3c [ether] on eth0";
        assertEquals("BC:09:B9:E5:9C:3C", CameraDiscovery.parseArpTable(output).get("192.168.1.50"));
    }

    @Test
    void normalizesMacToUppercaseColons() {
        assertEquals("BC:09:B9:E5:9C:3C", CameraDiscovery.normalizeMac("bc-09-b9-e5-9c-3c"));
    }

    @Test
    void pullsValuesAndReolinkFlagFromScopes() {
        List<String> scopes = List.of(
                "onvif://www.onvif.org/name/Reolink",
                "onvif://www.onvif.org/hardware/RLC-810A",
                "onvif://www.onvif.org/manufacturer/Reolink");
        assertEquals("Reolink", CameraDiscovery.scopeValue(scopes, "name"));
        assertEquals("RLC-810A", CameraDiscovery.scopeValue(scopes, "hardware"));
        assertNull(CameraDiscovery.scopeValue(scopes, "location"));
        assertTrue(CameraDiscovery.looksReolink(scopes));
        assertFalse(CameraDiscovery.looksReolink(List.of("onvif://www.onvif.org/name/SomeCam")));
    }

    @Test
    void parsesXaddrsAndScopesFromAProbeMatch() {
        String reply = """
                <SOAP-ENV:Envelope xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
                  <SOAP-ENV:Body><d:ProbeMatches><d:ProbeMatch>
                    <d:XAddrs>http://192.168.1.50:8000/onvif/device_service</d:XAddrs>
                    <d:Scopes>onvif://www.onvif.org/name/Reolink onvif://www.onvif.org/hardware/RLC-810A</d:Scopes>
                  </d:ProbeMatch></d:ProbeMatches></SOAP-ENV:Body>
                </SOAP-ENV:Envelope>""";
        assertEquals(List.of("http://192.168.1.50:8000/onvif/device_service"), CameraDiscovery.parseXaddrs(reply));
        assertEquals(
                List.of("onvif://www.onvif.org/name/Reolink", "onvif://www.onvif.org/hardware/RLC-810A"),
                CameraDiscovery.parseScopes(reply));
    }
}
