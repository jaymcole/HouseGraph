/**
 * Loading a saved graph: {@link io.github.jaymcole.housegraph.loader.GraphLoader} builds the nodes
 * and edges of a {@code GraphSnapshot} onto a {@code NodeGraph}.
 *
 * <p>Its own package, headless like {@code plugin}, {@code cli}, {@code remote} and
 * {@code catalog}, because of who needs it. It cannot live in {@code housegraph-api}: the snapshot
 * records carry manual edge routing as {@code javafx.geometry.Point2D}, and {@code graph/} never
 * imports JavaFX. It must not live in {@code ui/} either — the callers are headless ones, and
 * dependencies point downward, so a node or a CLI command reaching up into the canvas layer to
 * open a graph would invert the layering. See {@code docs/engine/architecture.md}.
 *
 * <p>The seam below it is {@code GraphSnapshot}: {@code ui/io/GraphFileIO} owns JSON and stops
 * there, so nothing here knows the file format. The seam above it is
 * {@link io.github.jaymcole.housegraph.loader.LoadedGraph}, which hands back the built nodes and
 * the edges paired with the snapshot entries that produced them — enough for a host to apply the
 * view-only state a snapshot carries, with no view concept in the type itself.
 */
package io.github.jaymcole.housegraph.loader;
