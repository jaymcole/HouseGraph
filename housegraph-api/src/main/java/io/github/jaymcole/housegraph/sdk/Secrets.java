package io.github.jaymcole.housegraph.sdk;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.storage.SecretsException;
import io.github.jaymcole.housegraph.storage.SecretsStore;

import java.util.List;

/**
 * The way a node reads a credential: by <em>reference</em> (a key the user picked), resolved
 * at the moment it is needed. A node stores the key, never the value — see
 * {@code NodeVariable#markSecret()} and the persistence rules in {@code NodeVariable}.
 *
 * <h2>Why this exists rather than calling {@link SecretsStore} directly</h2>
 * Nothing here does anything {@code SecretsStore.open()} would not do today. The point is the
 * <em>shape</em>. Once node libraries are fetched from arbitrary GitHub repositories, "which
 * code may read which secret" becomes a question worth being able to answer, and the honest
 * position today is that it has no answer: a loaded jar runs in this JVM with the user's full
 * privileges and can read the store, the filesystem, and the network directly. There is no
 * sandbox to hide behind — {@code SecurityManager} is gone, and JPMS carries no permission
 * model.
 * <p>
 * So this facade is not a security boundary and must not be described as one. It is the
 * <em>seam</em> a boundary could later be attached to: a per-library grant checked in
 * {@link #get(String)} is a host-side change, whereas retrofitting one after twenty published
 * libraries call {@code SecretsStore.open()} directly is not feasible. Putting the seam in
 * before the first out-of-tree node exists costs nothing; adding it afterwards costs a
 * breaking change to every one of them.
 * <p>
 * Treat installing a node library exactly as you would treat running any program you
 * downloaded.
 */
public final class Secrets {

    private static final Logger log = Log.get(Secrets.class);

    private Secrets() {
    }

    /**
     * The value stored under {@code key}, or {@code null} if there is no such secret.
     * <p>
     * Resolve at the point of use and do not cache the result: the user may change a secret
     * between runs, and holding the plaintext in a field is exactly what the reference-not-value
     * rule exists to avoid.
     *
     * @param key the secret's key, as chosen by the user
     * @return the secret value, or null if absent (or if the store could not be opened)
     */
    public static String get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return SecretsStore.open().get(key);
        } catch (SecretsException e) {
            // Deliberately not rethrown: a node asking for a secret that cannot be read should
            // fail on the missing value with its own message, not on a store-level exception.
            log.error("Could not read secret \"{}\"", key, e);
            return null;
        }
    }

    /**
     * The keys the user has stored, for populating a picker. Values are never returned here.
     *
     * @return the known secret keys, or an empty list if the store could not be opened
     */
    public static List<String> keys() {
        try {
            return SecretsStore.open().keys();
        } catch (SecretsException e) {
            log.error("Could not list secrets", e);
            return List.of();
        }
    }

    /**
     * Whether a secret is stored under {@code key}. Useful for reporting a misconfigured node
     * without reading the value.
     *
     * @param key the secret's key
     * @return true if a secret is stored under that key
     */
    public static boolean contains(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        try {
            return SecretsStore.open().contains(key);
        } catch (SecretsException e) {
            log.error("Could not check secret \"{}\"", key, e);
            return false;
        }
    }
}
