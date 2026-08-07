package io.github.jaymcole.housegraph.ui.snapshot;

import io.github.jaymcole.housegraph.graph.BaseNode;

/**
 * One node plus its canvas position, as captured for copy/paste or save/load.
 * A plain data carrier: it holds nothing from {@code GraphCanvas}'s internals, so the
 * canvas, the clipboard, and {@code GraphFileIO} can all build and read it independently.
 * <p>
 * {@code node} is normally non-null. A save-file node whose <em>type</em> can't be resolved — the
 * library providing it isn't installed — is carried as a {@code MissingNode}, which is a real node:
 * it reaches the canvas, keeps its ports and edges, and is written back out unchanged on the next
 * save. That matters because it used to be a {@code null} slot instead, which {@code GraphCanvas.place}
 * dropped and no save ever wrote back, so opening such a graph and pressing Quick Save destroyed the
 * node and everything attached to it.
 * <p>
 * {@code null} now means only "the factory genuinely failed to build a type we <em>do</em> have" —
 * an internal error with no user data to preserve. The slot is still kept so later nodes stay at
 * their original index and the edges referencing them resolve correctly.
 */
public record ClipboardNode(BaseNode node, double x, double y) {
}
