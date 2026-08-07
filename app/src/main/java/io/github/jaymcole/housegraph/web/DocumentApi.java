package io.github.jaymcole.housegraph.web;

/**
 * The narrow surface {@link LocalWebServer} needs to expose a JSON document over
 * {@code /api/data}, without depending on the store or the resource registry. The
 * web-server node supplies an implementation that resolves the backing
 * {@code JsonDocumentStore} by name at call time.
 * <p>
 * Failures are signalled with standard unchecked exceptions the HTTP layer maps to status
 * codes: {@link IllegalStateException} when the backing store isn't available (→ 503),
 * {@link IllegalArgumentException} when the written body isn't valid JSON (→ 400).
 */
public interface DocumentApi {

    /**
     * @return the current document as JSON text
     * @throws IllegalStateException if the backing store isn't available
     */
    String read();

    /**
     * Replaces the document.
     *
     * @param json the new document as JSON text
     * @throws IllegalArgumentException if {@code json} isn't valid JSON
     * @throws IllegalStateException    if the backing store isn't available
     */
    void write(String json);
}
