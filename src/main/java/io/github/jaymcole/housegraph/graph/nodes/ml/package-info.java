/**
 * Machine-learning nodes: graph nodes backed by locally-run models.
 * <p>
 * These drive the JVM-native inference clients in
 * {@link io.github.jaymcole.housegraph.ml} (models run through Deep Java Library — no
 * Python), the same way {@code graph.nodes.camera} drives the {@code camera} clients.
 * {@link io.github.jaymcole.housegraph.graph.nodes.ml.AnimalClassifierNode} is the first:
 * a squirrel/bird/other/none image classifier. Detectors and other model-backed nodes
 * will join this category as feature parity with the Python sibling project grows.
 */
package io.github.jaymcole.housegraph.graph.nodes.ml;
