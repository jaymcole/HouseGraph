/**
 * Ranked search over the node types the registry can offer.
 * <p>
 * {@link io.github.jaymcole.housegraph.search.NodeSearchIndex} is the entry point: it builds a
 * {@link io.github.jaymcole.housegraph.search.NodeDescriptor} per discovered node type, caches
 * them until a library changes, and returns
 * {@link io.github.jaymcole.housegraph.search.SearchResult}s best first.
 * {@code QueryParser} turns a raw string into a
 * {@link io.github.jaymcole.housegraph.search.SearchQuery} of ranking text plus filtering facets,
 * and {@code NodeScorer} combines character-trigram similarity with whole-word rarity to rank.
 * <p>
 * Pure logic, no JavaFX: the package is headless-testable, and a picker built on it would live
 * under {@code ui/}. Nothing here ever constructs a node — all metadata is read reflectively.
 * <p>
 * See {@code docs/engine/node-search.md}.
 */
package io.github.jaymcole.housegraph.search;
