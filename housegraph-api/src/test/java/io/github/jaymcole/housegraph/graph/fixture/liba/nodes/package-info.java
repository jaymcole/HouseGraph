/**
 * Fixture node types standing in for one node library, so {@code NodeRegistryTest} can assert
 * against a known, stable set instead of whatever happens to ship in the app. That matters more
 * than it sounds: the app's node library is being emptied into external repositories, so a test
 * written against it would churn on every extraction and would not be runnable from this module
 * at all.
 *
 * <p>Paired with {@code fixture.libb.nodes}, which deliberately declares a node with the same
 * simple name to exercise type-id collisions between two independently-written libraries.
 */
package io.github.jaymcole.housegraph.graph.fixture.liba.nodes;
