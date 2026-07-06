package io.github.jaymcole.housegraph.camera;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraConfigStoreTest {

    @Test
    void addsANewCameraWithABlankPassword(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cameras.json");
        CameraConfigStore.MergeResult result = CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.50", "BC:09:B9:E5:9C:3C", "Front Door", "RLC-810A", true)), file);

        assertEquals(1, result.added());
        JSONObject entry = registry(file).getJSONObject("BC:09:B9:E5:9C:3C");
        assertEquals("Front Door", entry.getString("name"));
        assertEquals("192.168.1.50", entry.getString("lastKnownIp"));
        assertEquals("", entry.getString("password"));
    }

    @Test
    void refreshesIpButPreservesAnAddedPassword(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cameras.json");
        CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.50", "BC:09:B9:E5:9C:3C", "Front Door", "RLC-810A", true)), file);
        // The user fills in a password by hand.
        JSONObject data = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
        data.getJSONObject("cameras").getJSONObject("BC:09:B9:E5:9C:3C").put("password", "hunter2");
        Files.writeString(file, data.toString(), StandardCharsets.UTF_8);

        // Rediscovered at a new IP (DHCP moved it).
        CameraConfigStore.MergeResult result = CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.77", "BC:09:B9:E5:9C:3C", "Front Door", "RLC-810A", true)), file);

        assertEquals(0, result.added());
        assertEquals(1, result.updated());
        JSONObject entry = registry(file).getJSONObject("BC:09:B9:E5:9C:3C");
        assertEquals("192.168.1.77", entry.getString("lastKnownIp"), "IP should be refreshed");
        assertEquals("hunter2", entry.getString("password"), "the hand-entered password must survive");
    }

    @Test
    void skipsCamerasWithoutAResolvedMac(@TempDir Path dir) {
        Path file = dir.resolve("cameras.json");
        CameraConfigStore.MergeResult result = CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.99", null, null, null, false)), file);

        assertEquals(0, result.added());
        assertEquals(1, result.skipped());
        assertTrue(registry(file).isEmpty());
    }

    @Test
    void refusesToClobberAMalformedFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cameras.json");
        Files.writeString(file, "{ not valid json ", StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class, () -> CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.50", "BC:09:B9:E5:9C:3C", "x", "y", true)), file));
    }

    private static JSONObject registry(Path file) {
        try {
            return new JSONObject(Files.readString(file, StandardCharsets.UTF_8)).getJSONObject("cameras");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
