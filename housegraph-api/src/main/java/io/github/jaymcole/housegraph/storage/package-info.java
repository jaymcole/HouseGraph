/**
 * On-disk concerns.
 * <p>
 * {@link io.github.jaymcole.housegraph.storage.AppDirectories} is the single source of
 * truth for OS-appropriate file locations;
 * {@link io.github.jaymcole.housegraph.storage.SecretsStore} is a key/value secret store
 * encrypted at rest with AES-256-GCM; and
 * {@link io.github.jaymcole.housegraph.storage.AppPreferences} holds non-sensitive UX
 * state. Credentials live only in the secret store, never in save files or plaintext
 * config.
 * <p>
 * See {@code docs/architecture/storage-and-secrets.md}.
 */
package io.github.jaymcole.housegraph.storage;
