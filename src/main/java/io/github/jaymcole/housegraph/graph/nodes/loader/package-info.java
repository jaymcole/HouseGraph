/**
 * Nodes that load external data into the graph — an image from disk, a value from the
 * encrypted secret store (by key, so the secret itself is never wired or saved), or a
 * persisted JSON document store (its {@code Store} output wires into a web-server node to
 * back the site's {@code /api/data} storage).
 */
package io.github.jaymcole.housegraph.graph.nodes.loader;
