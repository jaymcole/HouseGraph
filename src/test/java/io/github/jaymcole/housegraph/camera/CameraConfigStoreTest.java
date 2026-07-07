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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraConfigStoreTest {

    @Test
    void addsANewCameraWithoutStoringCredentials(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cameras.json");
        CameraConfigStore.MergeResult result = CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.50", "BC:09:B9:E5:9C:3C", "Front Door", "RLC-810A", true)), file);

        assertEquals(1, result.added());
        JSONObject entry = registry(file).getJSONObject("BC:09:B9:E5:9C:3C");
        assertEquals("Front Door", entry.getString("name"));
        assertEquals("192.168.1.50", entry.getString("lastKnownIp"));
        // Credentials live in the encrypted SecretsStore, never this plaintext file.
        assertFalse(entry.has("password"), "the unencrypted registry must not carry a password");
    }

    @Test
    void refreshesIpButPreservesHandAddedFields(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cameras.json");
        CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.50", "BC:09:B9:E5:9C:3C", "Front Door", "RLC-810A", true)), file);
        // The user annotates the entry by hand with a non-secret field.
        JSONObject data = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
        data.getJSONObject("cameras").getJSONObject("BC:09:B9:E5:9C:3C").put("notes", "garage");
        Files.writeString(file, data.toString(), StandardCharsets.UTF_8);

        // Rediscovered at a new IP (DHCP moved it).
        CameraConfigStore.MergeResult result = CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.77", "BC:09:B9:E5:9C:3C", "Front Door", "RLC-810A", true)), file);

        assertEquals(0, result.added());
        assertEquals(1, result.updated());
        JSONObject entry = registry(file).getJSONObject("BC:09:B9:E5:9C:3C");
        assertEquals("192.168.1.77", entry.getString("lastKnownIp"), "IP should be refreshed");
        assertEquals("garage", entry.getString("notes"), "the hand-added field must survive");
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

    @Test
    void listsStoredCamerasSortedByLabel(@TempDir Path dir) {
        Path file = dir.resolve("cameras.json");
        CameraConfigStore.merge(List.of(
                new DiscoveredCamera("192.168.1.50", "AA:AA:AA:AA:AA:AA", "Zebra", "RLC-810A", true),
                new DiscoveredCamera("192.168.1.51", "BB:BB:BB:BB:BB:BB", "Aardvark", "RLC-820A", true)), file);

        List<CameraConfigStore.KnownCamera> cameras = CameraConfigStore.list(file);
        assertEquals(2, cameras.size());
        assertEquals("Aardvark", cameras.get(0).name(), "sorted by label");
        assertEquals("192.168.1.51", cameras.get(0).lastKnownIp());
        assertEquals("Zebra", cameras.get(1).name());
    }

    @Test
    void listToleratesAMissingOrMalformedFile(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("nope.json");
        assertTrue(CameraConfigStore.list(missing).isEmpty());

        Path malformed = dir.resolve("cameras.json");
        Files.writeString(malformed, "{ not valid json ", StandardCharsets.UTF_8);
        assertTrue(CameraConfigStore.list(malformed).isEmpty(), "a bad file must not break the dropdown");
    }

    @Test
    void findReturnsTheCameraByMac(@TempDir Path dir) {
        Path file = dir.resolve("cameras.json");
        CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.50", "BC:09:B9:E5:9C:3C", "Front Door", "RLC-810A", true)), file);

        assertTrue(CameraConfigStore.find("BC:09:B9:E5:9C:3C", file).isPresent());
        assertEquals("Front Door", CameraConfigStore.find("BC:09:B9:E5:9C:3C", file).get().label());
        assertTrue(CameraConfigStore.find("00:00:00:00:00:00", file).isEmpty());
        assertTrue(CameraConfigStore.find(null, file).isEmpty());
    }

    @Test
    void updateIpChangesOnlyWhenItMoves(@TempDir Path dir) {
        Path file = dir.resolve("cameras.json");
        CameraConfigStore.merge(
                List.of(new DiscoveredCamera("192.168.1.50", "BC:09:B9:E5:9C:3C", "Front Door", "RLC-810A", true)), file);

        assertTrue(CameraConfigStore.updateIp("BC:09:B9:E5:9C:3C", "192.168.1.77", file));
        assertEquals("192.168.1.77", CameraConfigStore.find("BC:09:B9:E5:9C:3C", file).get().lastKnownIp());

        assertFalse(CameraConfigStore.updateIp("BC:09:B9:E5:9C:3C", "192.168.1.77", file), "unchanged IP is a no-op");
        assertFalse(CameraConfigStore.updateIp("00:00:00:00:00:00", "192.168.1.99", file), "unknown camera is a no-op");
    }

    private static JSONObject registry(Path file) {
        try {
            return new JSONObject(Files.readString(file, StandardCharsets.UTF_8)).getJSONObject("cameras");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
