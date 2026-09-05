/**
 * Save / load: serializing a canvas to JSON and back.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.io.GraphFileIO} reuses the same index-based
 * {@link io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot} shape ({@code ClipboardNode} /
 * {@code ClipboardDataEdge} / {@code ClipboardFlowEdge}) built for copy/paste. Its
 * {@code toJson}/{@code fromJson} conversion
 * is deliberately free of any JavaFX/{@code GraphCanvas} dependency so it can be unit-tested
 * headlessly; {@code save}/{@code load} are the thin wrappers that touch a real canvas. When you
 * change the JSON format, keep the forgiving-read/back-compat behavior and update the
 * {@code GraphFileIO} Javadoc and {@code docs/engine/ui-layer.md}.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.io.RecentGraphs} is the other half of getting a file
 * open: the most-recently-used list behind the toolbar's Recent menu, persisted in
 * {@code AppPreferences}. It is free of JavaFX for the same reason — {@code App} owns the menu, this
 * owns the list.
 */
package io.github.jaymcole.housegraph.ui.io;
