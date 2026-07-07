package io.github.jaymcole.housegraph.camera;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnvifEnrichmentTest {

    @Test
    void extractsAModelFromGetDeviceInformation() {
        String xml = """
                <s:Envelope><s:Body>
                  <tds:GetDeviceInformationResponse>
                    <tds:Manufacturer>Reolink</tds:Manufacturer>
                    <tds:Model>Reolink RLC-810A</tds:Model>
                  </tds:GetDeviceInformationResponse>
                </s:Body></s:Envelope>""";
        assertEquals("Reolink RLC-810A", OnvifEnrichment.xmlField(xml, "Model"));
        assertNull(OnvifEnrichment.xmlField(xml, "SerialNumber"), "an absent tag is null");
        assertNull(OnvifEnrichment.xmlField(null, "Model"));
    }

    @Test
    void parsesScopeItemsFromGetScopes() {
        String xml = """
                <s:Envelope><s:Body><tds:GetScopesResponse>
                  <tds:Scopes><tt:ScopeItem>onvif://www.onvif.org/name/Reolink</tt:ScopeItem></tds:Scopes>
                  <tds:Scopes><tt:ScopeItem>odm:name:Front%20Door</tt:ScopeItem></tds:Scopes>
                  <tds:Scopes><tt:ScopeItem></tt:ScopeItem></tds:Scopes>
                </tds:GetScopesResponse></s:Body></s:Envelope>""";
        List<String> items = OnvifEnrichment.scopeItems(xml);
        assertEquals(List.of("onvif://www.onvif.org/name/Reolink", "odm:name:Front%20Door"), items,
                "empty scope items are dropped");
    }

    @Test
    void readsAndUrlDecodesTheAppSetCustomName() {
        assertEquals("Front Door", OnvifEnrichment.customName(List.of(
                "onvif://www.onvif.org/name/Reolink", "odm:name:Front%20Door")));
        assertNull(OnvifEnrichment.customName(List.of("onvif://www.onvif.org/hardware/RLC-810A")),
                "no odm:name scope means no custom name");
        assertNull(OnvifEnrichment.customName(List.of("odm:name:")), "a blank custom name is null");
    }

    @Test
    void securityHeaderCarriesADigestNonceAndCreatedTimestamp() {
        String header = OnvifEnrichment.securityHeader("admin", "secret");
        assertTrue(header.contains("<Username>admin</Username>"));
        assertTrue(header.contains("PasswordDigest"), "password is sent as a digest, never in the clear");
        assertTrue(header.contains("<Nonce"));
        assertTrue(header.contains("<Created"));
        assertTrue(header.contains("</UsernameToken>"));
    }
}
