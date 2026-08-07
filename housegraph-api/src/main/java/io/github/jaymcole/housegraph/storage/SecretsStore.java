package io.github.jaymcole.housegraph.storage;

import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A key/value secret store, encrypted at rest with AES-256-GCM.
 * <p>
 * The encryption key is a random 256-bit key generated once and kept in a
 * {@value #KEY_FILE} file alongside the encrypted {@value #DATA_FILE}, both under
 * {@link AppDirectories#secrets()}. This keeps secrets off disk in plaintext and out of
 * saved graphs, and defends against casual inspection or an accidentally-synced file —
 * but not against someone who can already read the secrets folder, since the key lives
 * there too. (Swapping in a password- or OS-keychain-derived key later only means
 * changing how the key is obtained; the on-disk format is unaffected.)
 * <p>
 * GCM authenticates every read: a tampered or truncated file fails to decrypt with a
 * {@link SecretsException} rather than yielding garbage. A fresh random IV is used on
 * every {@link #save()}.
 * <p>
 * Not thread-safe; intended to be opened, used, and (if writing) saved on one thread.
 */
public final class SecretsStore {

    private static final String KEY_FILE = "secret.key";
    private static final String DATA_FILE = "secrets.enc";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path dataFile;
    private final SecretKey key;
    private final Map<String, String> entries;
    private final SecureRandom random = new SecureRandom();

    private SecretsStore(Path dataFile, SecretKey key, Map<String, String> entries) {
        this.dataFile = dataFile;
        this.key = key;
        this.entries = entries;
    }

    /** Opens (or initialises) the machine's secret store under {@link AppDirectories#secrets()}. */
    public static SecretsStore open() {
        return openIn(AppDirectories.get().secrets());
    }

    /** Package-visible: opens a store rooted in an explicit directory (so tests can use a temp dir). */
    static SecretsStore openIn(Path secretsDir) {
        SecretKey key = loadOrCreateKey(secretsDir.resolve(KEY_FILE));
        Path dataFile = secretsDir.resolve(DATA_FILE);
        return new SecretsStore(dataFile, key, readEntries(dataFile, key));
    }

    // --- In-memory access ---------------------------------------------------------

    /** The secret names, sorted — never the values. */
    public List<String> keys() {
        List<String> sorted = new ArrayList<>(entries.keySet());
        sorted.sort(String::compareTo);
        return sorted;
    }

    public boolean contains(String name) {
        return entries.containsKey(name);
    }

    /** The secret value for {@code name}, or null if there's no such secret. */
    public String get(String name) {
        return entries.get(name);
    }

    public void put(String name, String value) {
        entries.put(name, value);
    }

    public void remove(String name) {
        entries.remove(name);
    }

    /** Encrypts the current entries and writes them to disk (replacing the previous file). */
    public void save() {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        byte[] blob = encrypt(json.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.createDirectories(dataFile.getParent());
            Files.write(dataFile, blob);
            restrict(dataFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write secrets file: " + dataFile, e);
        }
    }

    // --- Crypto -------------------------------------------------------------------

    private byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            // File layout: [12-byte IV][ciphertext + 16-byte GCM tag].
            byte[] blob = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
            return blob;
        } catch (GeneralSecurityException e) {
            throw new SecretsException("Failed to encrypt secrets", e);
        }
    }

    private static Map<String, String> readEntries(Path dataFile, SecretKey key) {
        if (!Files.isRegularFile(dataFile)) {
            return new LinkedHashMap<>();
        }
        byte[] blob;
        try {
            blob = Files.readAllBytes(dataFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read secrets file: " + dataFile, e);
        }
        if (blob.length < IV_BYTES) {
            throw new SecretsException("Secrets file is corrupt (too short): " + dataFile);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);

            JSONObject json = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            Map<String, String> entries = new LinkedHashMap<>();
            for (String name : json.keySet()) {
                entries.put(name, json.getString(name));
            }
            return entries;
        } catch (GeneralSecurityException e) {
            throw new SecretsException("Could not decrypt secrets (wrong key or tampered file): " + dataFile, e);
        }
    }

    private static SecretKey loadOrCreateKey(Path keyFile) {
        if (Files.isRegularFile(keyFile)) {
            try {
                byte[] encoded = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
                return new SecretKeySpec(encoded, ALGORITHM);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read secrets key: " + keyFile, e);
            } catch (IllegalArgumentException e) {
                throw new SecretsException("Secrets key file is corrupt: " + keyFile, e);
            }
        }
        try {
            KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
            generator.init(KEY_BITS);
            SecretKey key = generator.generateKey();
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key.getEncoded()), StandardCharsets.UTF_8);
            restrict(keyFile);
            return key;
        } catch (GeneralSecurityException e) {
            throw new SecretsException("Could not generate secrets key", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write secrets key: " + keyFile, e);
        }
    }

    /** Best-effort owner-only permissions; silently a no-op on non-POSIX filesystems (e.g. Windows), which rely on the user profile's own ACLs. */
    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem: nothing to do.
        }
    }
}
