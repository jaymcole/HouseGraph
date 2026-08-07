package io.github.jaymcole.housegraph.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsStoreTest {

    @Test
    void putGetRoundTripsThroughTheEncryptedFile(@TempDir Path dir) {
        SecretsStore store = SecretsStore.openIn(dir);
        store.put("API_KEY", "s3cr3t-value");
        store.put("OTHER", "another");
        store.save();

        SecretsStore reopened = SecretsStore.openIn(dir);
        assertEquals("s3cr3t-value", reopened.get("API_KEY"));
        assertEquals("another", reopened.get("OTHER"));
        assertEquals(List.of("API_KEY", "OTHER"), reopened.keys());
    }

    @Test
    void neitherKeyNameNorValueAppearsInPlaintextOnDisk(@TempDir Path dir) throws IOException {
        SecretsStore store = SecretsStore.openIn(dir);
        store.put("MY_TOKEN", "PLAINTEXT_MARKER_VALUE");
        store.save();

        byte[] raw = Files.readAllBytes(dir.resolve("secrets.enc"));
        String asText = new String(raw, StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains("PLAINTEXT_MARKER_VALUE"), "secret value must not be readable in the file");
        assertFalse(asText.contains("MY_TOKEN"), "secret name must not be readable in the file");
    }

    @Test
    void removeDeletesAKey(@TempDir Path dir) {
        SecretsStore store = SecretsStore.openIn(dir);
        store.put("A", "1");
        store.put("B", "2");
        store.remove("A");
        store.save();

        SecretsStore reopened = SecretsStore.openIn(dir);
        assertFalse(reopened.contains("A"));
        assertEquals("2", reopened.get("B"));
        assertNull(reopened.get("A"));
    }

    @Test
    void aTamperedFileFailsToDecryptRatherThanReturningGarbage(@TempDir Path dir) throws IOException {
        SecretsStore store = SecretsStore.openIn(dir);
        store.put("A", "1");
        store.save();

        // Flip a bit in the ciphertext/tag region (past the 12-byte IV) — GCM auth must reject it.
        Path dataFile = dir.resolve("secrets.enc");
        byte[] raw = Files.readAllBytes(dataFile);
        raw[raw.length - 1] ^= 0x01;
        Files.write(dataFile, raw);

        assertThrows(SecretsException.class, () -> SecretsStore.openIn(dir));
    }

    @Test
    void anEmptyStoreHasNoKeys(@TempDir Path dir) {
        assertTrue(SecretsStore.openIn(dir).keys().isEmpty());
    }
}
