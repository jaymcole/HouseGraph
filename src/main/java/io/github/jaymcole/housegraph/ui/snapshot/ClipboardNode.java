package io.github.jaymcole.housegraph.ui.snapshot;

import io.github.jaymcole.housegraph.graph.BaseNode;

/**
 * One node plus its canvas position, as captured for copy/paste or save/load.
 * A plain data carrier: it holds nothing from {@code GraphCanvas}'s internals, so the
 * canvas, the clipboard, and {@code GraphFileIO} can all build and read it independently.
 */
public record ClipboardNode(BaseNode node, double x, double y) {
}
