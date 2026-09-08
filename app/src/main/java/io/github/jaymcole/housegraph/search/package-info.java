/**
 * Two searches over nodes, answering two different questions.
 * <p>
 * {@link io.github.jaymcole.housegraph.search.NodeSearchIndex} is the entry point: it builds a
 * {@link io.github.jaymcole.housegraph.search.NodeDescriptor} per discovered node type, caches
 * them until a library changes, and returns
 * {@link io.github.jaymcole.housegraph.search.SearchResult}s best first.
 * {@code QueryParser} turns a raw string into a
 * {@link io.github.jaymcole.housegraph.search.SearchQuery} of ranking text plus filtering facets,
 * and {@code NodeScorer} combines character-trigram similarity with whole-word rarity to rank.
 * <p>
 * {@link io.github.jaymcole.housegraph.search.GraphSearch} is the other one: find-in-graph, over
 * the nodes <em>already on the canvas</em>. It filters rather than ranks, because a find
 * highlights a set instead of picking a winner, and it may read a node's ports and authored
 * values because those nodes are already built. It never reads a secret or a computed value.
 * <p>
 * Pure logic, no JavaFX: the package is headless-testable, and the pickers and bars built on it
 * live under {@code ui/}. The type index never constructs a node — all its metadata is read
 * reflectively — while find-in-graph only ever inspects nodes someone else built.
 * <p>
 * See {@code docs/engine/node-search.md} and {@code docs/engine/find-in-graph.md}.
 */
package io.github.jaymcole.housegraph.search;
