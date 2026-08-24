/**
 * Rendering the canvas to image files.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.export.GraphComponents} splits the graph into its
 * connected components — the distinct graphs one save file can hold — and is plain graph logic with
 * no JavaFX dependency, so it is unit-tested headlessly.
 * {@link io.github.jaymcole.housegraph.ui.export.GraphImageExport} renders one PNG per component,
 * tiling the render because a single whole-canvas snapshot fails above the graphics pipeline's
 * maximum texture size. The isolating — hiding everything outside the component being drawn — is
 * {@code GraphCanvas.withComponentIsolated}, since it touches the canvas's own views and selection.
 * <p>
 * Nothing here reaches into {@code housegraph-api}: node positions live in
 * {@link io.github.jaymcole.housegraph.ui.snapshot} and the save file, not on the node, so
 * rendering a picture of a graph needs no geometry from the node model.
 * See {@code docs/engine/ui-layer.md}.
 */
package io.github.jaymcole.housegraph.ui.export;
