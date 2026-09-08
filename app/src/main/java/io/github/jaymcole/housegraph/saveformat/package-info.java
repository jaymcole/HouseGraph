/**
 * The save format: a graph file's JSON, in both directions, and the snapshot model it round-trips
 * through.
 * <p>
 * {@link io.github.jaymcole.housegraph.saveformat.GraphFileIO} converts a
 * {@link io.github.jaymcole.housegraph.saveformat.GraphSnapshot} plus a
 * {@link io.github.jaymcole.housegraph.saveformat.CameraState} to a save file's root JSON and back
 * ({@code toJson}/{@code fromJson}, {@code readRoot}/{@code fromRoot}/{@code cameraFromJson}), with
 * no JavaFX/{@code GraphCanvas} dependency beyond the {@code Point2D} waypoints a snapshot already
 * carries, so it can be unit-tested headlessly. {@code ui.io.GraphFileIO}'s {@code save}/{@code load}
 * are the thin wrappers that touch a real canvas, calling into this package for everything else.
 * <p>
 * {@link io.github.jaymcole.housegraph.saveformat.GraphSnapshot} bundles
 * {@link io.github.jaymcole.housegraph.saveformat.ClipboardNode}s and the
 * {@link io.github.jaymcole.housegraph.saveformat.ClipboardDataEdge} /
 * {@link io.github.jaymcole.housegraph.saveformat.ClipboardFlowEdge} between them, each referenced
 * positionally by index — the shape shared by copy/paste and save/load. These are plain data
 * carriers with no dependency on {@code GraphCanvas}'s internals, so {@code GraphCanvas} (copy/paste),
 * {@code ui.command} (the paste command), {@code ui.io} (save/load) and the headless
 * {@link io.github.jaymcole.housegraph.loader} package all build on the same shape.
 * <p>
 * {@link io.github.jaymcole.housegraph.saveformat.NodeGroup} is the odd one out: a group frame is
 * pure canvas decoration the engine never sees, but it rides in the same snapshot for the same
 * reason an edge's waypoints do — a slice of the canvas is not faithfully copied, pasted or saved
 * without it. It carries the grouping rules themselves (what a frame commands, and how frames
 * nest and stack), so they are unit-testable with no display; {@code ui/view/GroupView} only draws
 * the answers.
 * <p>
 * Its own package, headless like {@code loader}, {@code plugin}, {@code cli} and {@code remote},
 * because of who reads it: {@code cli/}, {@code remote/}, {@code catalog/} and {@code headless/} all
 * parse a save file directly, and none of them may reach up into {@code ui/} to do it. It cannot live
 * in {@code housegraph-api} either — {@code ClipboardDataEdge}/{@code ClipboardFlowEdge} carry manual
 * edge routing as {@code javafx.geometry.Point2D}, and {@code graph/} never imports JavaFX. Living
 * outside {@code ui/} does not mean living without JavaFX types; it means being usable without a
 * display, which this package is. See {@code docs/engine/architecture.md}.
 * <p>
 * The seam above it is {@link io.github.jaymcole.housegraph.loader.GraphLoader}, which turns a
 * {@code GraphSnapshot} into live nodes and edges on a {@code NodeGraph} — nothing here knows what a
 * {@code NodeGraph} does with the snapshot it hands back. See {@code docs/engine/save-format.md}.
 */
package io.github.jaymcole.housegraph.saveformat;
