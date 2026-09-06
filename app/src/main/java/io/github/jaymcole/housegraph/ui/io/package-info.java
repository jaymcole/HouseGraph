/**
 * Save / load: the canvas-facing half of getting a file open.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.io.GraphFileIO} is a thin wrapper — {@code save}/
 * {@code load}, the only two methods that touch a real {@code GraphCanvas} — over
 * {@link io.github.jaymcole.housegraph.saveformat.GraphFileIO}, which owns the actual JSON
 * conversion and everything about the format. That class lives outside {@code ui/} because its
 * other callers (`cli/`, `remote/`, `catalog/`, `headless/`) are headless; see
 * {@code docs/engine/architecture.md}. When you change the JSON format, keep the
 * forgiving-read/back-compat behavior and update its Javadoc and
 * {@code docs/engine/save-format.md}.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.io.RecentGraphs} is the other half of getting a file
 * open: the most-recently-used list behind File ▸ Open Recent, persisted in
 * {@code AppPreferences}. It is free of JavaFX for the same reason — {@code App} owns the menu, this
 * owns the list.
 */
package io.github.jaymcole.housegraph.ui.io;
