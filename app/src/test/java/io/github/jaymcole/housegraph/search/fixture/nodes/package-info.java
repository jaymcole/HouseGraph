/**
 * Fixture node types standing in for one node library, so the search tests assert against a known,
 * stable set rather than whatever happens to ship in the app.
 *
 * <p>This is the same discipline {@code NodeRegistryTest} follows, for the same reason: a ranking
 * test written against the real built-in library would change its answers every time somebody adds
 * a node, and would fail for a reason that has nothing to do with the search code.
 *
 * <p>The set is chosen to exercise one thing each — a keyword synonym the name does not contain,
 * an acronym that must tokenise, a node with no annotations at all, and the two ways a node can
 * end up a resource.
 */
package io.github.jaymcole.housegraph.search.fixture.nodes;
