package io.github.jaymcole.housegraph.ui.snapshot;

import io.github.jaymcole.housegraph.graph.BaseNode;

/**
 * One node plus its canvas position, as captured for copy/paste or save/load.
 * A plain data carrier: it holds nothing from {@code GraphCanvas}'s internals, so the
 * canvas, the clipboard, and {@code GraphFileIO} can all build and read it independently.
 * <p>
 * {@code node} is normally non-null, but load tolerates a {@code null} node as an
 * index-preserving placeholder for a save-file node that couldn't be rebuilt (an unknown
 * type). It keeps later nodes at their original index so edges still resolve; {@code GraphCanvas.place}
 * drops the placeholder and any edge attached to it. Placeholders exist only transiently during a
 * load - they never reach the canvas and so are never re-saved.
 */
public record ClipboardNode(BaseNode node, double x, double y) {
}
