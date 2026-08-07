/**
 * Small persistent data stores that back data-store resource nodes.
 * <p>
 * {@link io.github.jaymcole.housegraph.store.JsonDocumentStore} persists a single JSON
 * document to disk (atomically) — the shared, server-side storage a hosted website reads
 * and writes through the web server's {@code /api/data} endpoint. Like the other
 * integration client packages, it is JavaFX-free; the node in {@code graph.nodes.resource}
 * wraps it and publishes it under a name via {@code ResourceRegistry}.
 * <p>
 * See {@code docs/architecture/resources.md} and {@code docs/architecture/storage-and-secrets.md}.
 */
package io.github.jaymcole.housegraph.store;
